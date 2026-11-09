package com.thelastascension.ascendentCombat.mechanic;

import com.thelastascension.ascendentCombat.AscendentCombat;
import com.thelastascension.ascendentCombat.weapon.WeaponCombatTags;
import com.thelastascension.ascendentCore.i18n.LangManager;
import com.thelastascension.ascendentCore.model.PlayerProfile;
import com.thelastascension.ascendentCore.model.StatType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema de Parry con Clic Derecho, Bloqueo con mitigación gradual y Barra de Stun
 * inspirado en Grounded / Grounded 2.
 */
public class ParryAndStunManager implements Listener {

    private final AscendentCombat plugin;
    private final WeaponCombatTags weaponTags;

    // Timestamps de inicio de guardia por jugador (Clic Derecho)
    private final Map<UUID, Long> guardStartMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> parryCooldownUntilMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerStunUntilMs = new ConcurrentHashMap<>();

    // Estado de Stun de entidades enemigas (UUID -> Stun actual y timestamp de fin de aturdimiento)
    private final Map<UUID, Double> enemyCurrentStun = new ConcurrentHashMap<>();
    private final Map<UUID, Long> enemyStunUntilMs = new ConcurrentHashMap<>();

    private BukkitTask decayTask;

    public ParryAndStunManager(AscendentCombat plugin, WeaponCombatTags weaponTags) {
        this.plugin = plugin;
        this.weaponTags = weaponTags;
    }

    public void startTasks() {
        this.decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickStunDecayAndEffects, 20L, 20L);
    }

    public void shutdown() {
        if (this.decayTask != null) {
            this.decayTask.cancel();
        }
        guardStartMs.clear();
        enemyCurrentStun.clear();
        enemyStunUntilMs.clear();
    }

    /**
     * Detecta el Clic Derecho con armas que permiten bloqueo o parry.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickGuard(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
        if (profile == null || profile.isStunned() || profile.isDowned()) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        boolean canBlock = weaponTags.canBlock(held);
        boolean canParry = weaponTags.canParry(held);
        if (!canBlock && !canParry) return;

        long now = System.currentTimeMillis();
        Long lastGuard = guardStartMs.get(player.getUniqueId());

        // Si el jugador acaba de levantar la guardia (no estaba manteniéndola en los últimos 350ms),
        // abre nueva ventana de Parry (si no está en cooldown de parry).
        if (lastGuard == null || (now - lastGuard) > 350L) {
            guardStartMs.put(player.getUniqueId(), now);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.35f);
        } else {
            // Mantiene el bloqueo activo desplazando ligeramente la marca fuera de la ventana de parry
            long parryWindow = plugin.getConfig().getLong("parry-and-block.parry-window-ms", 260L);
            guardStartMs.put(player.getUniqueId(), now - parryWindow - 10L);
        }

        String msg = plugin.getCore().getLang().format(
                profile.getLanguage(),
                "combat.guard-raised",
                LangManager.placeholders(
                        "stun", String.format("%.0f", profile.getCurrentStun()),
                        "max_stun", String.format("%.0f", profile.getStat(StatType.MAX_STUN))
                )
        );
        player.sendActionBar(Component.text(msg));
    }

    /**
     * Evalúa ataques entrantes contra un jugador (Parry vs Bloqueo vs Ataque Imparable)
     * y ataques salientes contra enemigos (acumulación de Stun y control de entidades aturdidas).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        Entity rawDamager = event.getDamager();
        LivingEntity attacker = resolveLivingAttacker(rawDamager);

        // 1. Si el atacante está Aturdido (Stunned) o Abatido (Downed), cancelar su ataque
        if (attacker instanceof Player attackerPlayer) {
            PlayerProfile attProfile = plugin.getCore().getProfileManager().getCachedProfile(attackerPlayer.getUniqueId());
            if (attProfile != null && (attProfile.isStunned() || attProfile.isDowned())) {
                event.setCancelled(true);
                return;
            }
        } else if (attacker != null && isEnemyStunned(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // 2. Si el defensor es un Jugador: evaluar Parry / Bloqueo con Clic Derecho
        if (event.getEntity() instanceof Player defender) {
            handlePlayerDefending(event, defender, attacker);
        }
    }

    private void handlePlayerDefending(EntityDamageByEntityEvent event, Player defender, LivingEntity attacker) {
        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(defender.getUniqueId());
        if (profile == null || profile.isStunned() || profile.isDowned()) return;

        ItemStack weapon = defender.getInventory().getItemInMainHand();
        boolean canBlock = weaponTags.canBlock(weapon);
        boolean canParry = weaponTags.canParry(weapon);
        if (!canBlock && !canParry) return;

        long now = System.currentTimeMillis();
        Long guardStart = guardStartMs.get(defender.getUniqueId());
        long parryWindowMs = plugin.getConfig().getLong("parry-and-block.parry-window-ms", 260L);
        long blockActiveMs = plugin.getConfig().getLong("parry-and-block.block-active-ms", 1100L);

        boolean inGuardWindow = (guardStart != null && (now - guardStart) <= blockActiveMs) || defender.isBlocking();
        if (!inGuardWindow) return;

        long cooldownUntil = parryCooldownUntilMs.getOrDefault(defender.getUniqueId(), 0L);
        boolean inParryWindow = canParry
                && guardStart != null
                && (now - guardStart) <= parryWindowMs
                && now >= cooldownUntil;

        boolean attackParryable = weaponTags.isAttackParryable(attacker);

        // CASO A: ¡PARRY PERFECTO! (Dentro de la ventana de parry + arma con parry + ataque parreable)
        if (inParryWindow && attackParryable) {
            event.setDamage(0.0);
            guardStartMs.remove(defender.getUniqueId());

            double enemyStunGain = plugin.getConfig().getDouble("parry-and-block.enemy-stun-on-parry", 45.0)
                    + profile.getStat(StatType.STUN_DAMAGE);

            // Recupera un poco de postura propia al clavar un parry
            profile.setCurrentStun(Math.max(0.0, profile.getCurrentStun() - 10.0));

            defender.getWorld().playSound(defender.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.75f, 1.85f);
            defender.getWorld().playSound(defender.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.4f);
            defender.getWorld().spawnParticle(Particle.CRIT, defender.getEyeLocation().add(defender.getLocation().getDirection()), 25, 0.25, 0.25, 0.25, 0.15);

            if (attacker != null) {
                Vector knockback = attacker.getLocation().toVector().subtract(defender.getLocation().toVector()).normalize().multiply(0.65).setY(0.25);
                attacker.setVelocity(knockback);
                addEnemyStun(attacker, enemyStunGain, defender);
            }

            plugin.getCore().getLang().send(defender, "combat.parry-success", LangManager.placeholders(
                    "stun", String.format("%.0f", enemyStunGain)
            ));
            return;
        }

        // CASO B: Bloqueo (o intento de Parry contra un Ataque Imparable)
        if (!canBlock && !inParryWindow) return;

        double defaultMitigation = plugin.getConfig().getDouble("parry-and-block.default-block-mitigation", 0.60);
        double mitigation = weaponTags.getBlockMitigation(weapon, defaultMitigation);

        // Si el ataque era imparable, mitiga menos daño y sube más el Stun propio
        double selfStunGain;
        if (!attackParryable) {
            mitigation *= 0.65;
            selfStunGain = plugin.getConfig().getDouble("parry-and-block.self-stun-on-unparryable", 38.0);
            plugin.getCore().getLang().send(defender, "combat.unparryable-blocked", LangManager.placeholders(
                    "stun", String.format("%.0f", selfStunGain)
            ));
            defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.6f);
        } else {
            selfStunGain = plugin.getConfig().getDouble("parry-and-block.self-stun-on-block", 22.0);
            String barMsg = plugin.getCore().getLang().format(
                    profile.getLanguage(),
                    "combat.block-mitigated",
                    LangManager.placeholders(
                            "mitigated", String.format("%.0f", mitigation * 100.0),
                            "stun", String.format("%.0f", selfStunGain)
                    )
            );
            defender.sendActionBar(Component.text(barMsg));
            defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.0f);
        }

        // Aplicamos la reducción de daño del bloqueo (el jugador sí recibe daño reducido estilo Grounded)
        double reducedDamage = event.getDamage() * (1.0 - mitigation);
        event.setDamage(Math.max(0.5, reducedDamage));

        // Subimos el Stun propio del jugador
        double newStun = profile.getCurrentStun() + selfStunGain;
        double maxStun = profile.getStat(StatType.MAX_STUN);
        profile.setCurrentStun(newStun);

        long cooldownMs = plugin.getConfig().getLong("parry-and-block.parry-cooldown-ms", 450L);
        parryCooldownUntilMs.put(defender.getUniqueId(), now + cooldownMs);

        // Si la barra de Stun del jugador se llena -> Guardia Rota / Aturdido (Stunned)
        if (newStun >= maxStun) {
            triggerPlayerStun(defender, profile);
        }
    }

    public void triggerPlayerStun(Player player, PlayerProfile profile) {
        int durationSec = plugin.getConfig().getInt("stun.player-stun-duration-seconds", 3);
        profile.setStunned(true);
        profile.setCurrentStun(profile.getStat(StatType.MAX_STUN));
        guardStartMs.remove(player.getUniqueId());
        playerStunUntilMs.put(player.getUniqueId(), System.currentTimeMillis() + (durationSec * 1000L));

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.2f, 0.75f);
        player.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, player.getEyeLocation().add(0, 0.4, 0), 12, 0.3, 0.2, 0.3, 0.0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationSec * 20, 6, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationSec * 20, 4, false, false, true));

        plugin.getCore().getLang().send(player, "combat.stunned", LangManager.placeholders(
                "seconds", String.valueOf(durationSec)
        ));
    }

    /**
     * Incrementa el Stun de un enemigo; si llega a su umbral máximo, activa el estado Stunned estilo Grounded 2.
     */
    public void addEnemyStun(LivingEntity enemy, double amount, Player sourcePlayer) {
        if (enemy instanceof Player targetPlayer) {
            PlayerProfile targetProfile = plugin.getCore().getProfileManager().getCachedProfile(targetPlayer.getUniqueId());
            if (targetProfile != null && !targetProfile.isStunned()) {
                double next = targetProfile.getCurrentStun() + amount;
                targetProfile.setCurrentStun(next);
                if (next >= targetProfile.getStat(StatType.MAX_STUN)) {
                    triggerPlayerStun(targetPlayer, targetProfile);
                }
            }
            return;
        }

        if (isEnemyStunned(enemy.getUniqueId())) return;

        double maxStun = plugin.getConfig().getDouble("stun.default-enemy-max-stun", 100.0);
        double nextStun = enemyCurrentStun.getOrDefault(enemy.getUniqueId(), 0.0) + amount;

        if (nextStun >= maxStun) {
            int durationSec = plugin.getConfig().getInt("stun.enemy-stun-duration-seconds", 4);
            enemyCurrentStun.put(enemy.getUniqueId(), 0.0);
            enemyStunUntilMs.put(enemy.getUniqueId(), System.currentTimeMillis() + (durationSec * 1000L));

            enemy.getWorld().playSound(enemy.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.1f, 1.3f);
            enemy.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, enemy.getEyeLocation(), 30, 0.4, 0.4, 0.4, 0.15);
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationSec * 20, 10, false, false, true));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, durationSec * 20, 0, false, false, false));

            if (enemy instanceof Mob mob) {
                mob.setAware(false);
            }

            if (sourcePlayer != null) {
                plugin.getCore().getLang().send(sourcePlayer, "combat.enemy-stunned", LangManager.placeholders(
                        "target", enemy.getName(),
                        "seconds", String.valueOf(durationSec)
                ));
            }
        } else {
            enemyCurrentStun.put(enemy.getUniqueId(), nextStun);
        }
    }

    public boolean isEnemyStunned(UUID entityId) {
        Long until = enemyStunUntilMs.get(entityId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            enemyStunUntilMs.remove(entityId);
            Entity ent = Bukkit.getEntity(entityId);
            if (ent instanceof Mob mob) {
                mob.setAware(true);
            }
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStunnedPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
        if (profile != null && profile.isStunned()) {
            Long until = playerStunUntilMs.get(player.getUniqueId());
            if (until != null && System.currentTimeMillis() < until) {
                event.setTo(event.getFrom().setDirection(event.getTo().getDirection()));
            }
        }
    }

    private void tickStunDecayAndEffects() {
        long now = System.currentTimeMillis();
        double decay = plugin.getConfig().getDouble("stun.decay-per-second", 8.0);

        // 1. Actualizar jugadores (fin de Stun o recuperación pasiva de postura)
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
            if (profile == null) continue;

            if (profile.isStunned()) {
                Long until = playerStunUntilMs.get(player.getUniqueId());
                if (until == null || now >= until) {
                    profile.setStunned(false);
                    profile.setCurrentStun(0.0);
                    playerStunUntilMs.remove(player.getUniqueId());
                } else {
                    player.getWorld().spawnParticle(Particle.CRIT, player.getEyeLocation().add(0, 0.5, 0), 6, 0.3, 0.1, 0.3, 0.02);
                }
                continue;
            }

            Long lastGuard = guardStartMs.get(player.getUniqueId());
            boolean recentlyGuarding = lastGuard != null && (now - lastGuard) <= 1500L;
            if (!recentlyGuarding && profile.getCurrentStun() > 0.0) {
                profile.setCurrentStun(Math.max(0.0, profile.getCurrentStun() - decay));
            }
        }

        // 2. Actualizar enemigos aturdidos (halo de estrellas encima de la cabeza estilo Grounded 2)
        enemyStunUntilMs.entrySet().removeIf(entry -> {
            UUID id = entry.getKey();
            Entity ent = Bukkit.getEntity(id);
            if (ent == null || !ent.isValid() || now >= entry.getValue()) {
                if (ent instanceof Mob mob) {
                    mob.setAware(true);
                }
                return true;
            }
            if (ent instanceof LivingEntity living) {
                living.getWorld().spawnParticle(Particle.WAX_ON, living.getEyeLocation().add(0, 0.5, 0), 8, 0.35, 0.1, 0.35, 0.01);
            }
            return false;
        });

        // 3. Decaimiento pasivo de Stun de enemigos
        enemyCurrentStun.entrySet().removeIf(entry -> {
            double next = entry.getValue() - (decay * 0.5);
            if (next <= 0.0) return true;
            entry.setValue(next);
            return false;
        });
    }

    private LivingEntity resolveLivingAttacker(Entity damager) {
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity shooter) return shooter;
        return null;
    }
}

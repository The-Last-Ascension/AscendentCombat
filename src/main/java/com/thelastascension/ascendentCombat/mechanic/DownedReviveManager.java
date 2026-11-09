package com.thelastascension.ascendentCombat.mechanic;

import com.thelastascension.ascendentCombat.AscendentCombat;
import com.thelastascension.ascendentCore.i18n.LangManager;
import com.thelastascension.ascendentCore.model.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema de Abatido (Downed) y Revivir entre jugadores al caer a 0 de vida.
 */
public class DownedReviveManager implements Listener {

    private final AscendentCombat plugin;

    // Jugadores abatidos -> Timestamp en ms en el que se desangran definitivamente
    private final Map<UUID, Long> downedDeadlineMs = new ConcurrentHashMap<>();
    // Progreso de revivir acumulado sobre el jugador abatido (0.0 a segundos requeridos)
    private final Map<UUID, Double> reviveProgressSeconds = new ConcurrentHashMap<>();

    private BukkitTask reviveTask;

    public DownedReviveManager(AscendentCombat plugin) {
        this.plugin = plugin;
    }

    public void startTasks() {
        // Se ejecuta cada 5 ticks (0.25s) para canalizar el revivir de forma fluida
        this.reviveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDownedAndRevive, 5L, 5L);
    }

    public void shutdown() {
        if (this.reviveTask != null) {
            this.reviveTask.cancel();
        }
        downedDeadlineMs.clear();
        reviveProgressSeconds.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
        if (profile == null) return;

        // Si ya está abatido, es invulnerable al daño directo mientras espera ser revivido o desangrarse
        if (profile.isDowned()) {
            event.setCancelled(true);
            return;
        }

        if (player.getHealth() - event.getFinalDamage() <= 0.0) {
            event.setDamage(0.0);
            enterDownedState(player, profile);
        }
    }

    public void enterDownedState(Player player, PlayerProfile profile) {
        int bleedoutSec = plugin.getConfig().getInt("downed-and-revive.bleedout-seconds", 30);
        profile.setDowned(true);
        profile.setStunned(false);
        downedDeadlineMs.put(player.getUniqueId(), System.currentTimeMillis() + (bleedoutSec * 1000L));
        reviveProgressSeconds.put(player.getUniqueId(), 0.0);

        player.setHealth(Math.min(2.0, getMaxHealth(player)));
        player.setSwimming(true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, bleedoutSec * 20, 4, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_HURT, 1.0f, 0.7f);

        plugin.getCore().getLang().send(player, "combat.downed-self", LangManager.placeholders(
                "seconds", String.valueOf(bleedoutSec)
        ));

        for (Player ally : player.getWorld().getPlayers()) {
            if (!ally.equals(player) && ally.getLocation().distanceSquared(player.getLocation()) <= 900.0) {
                plugin.getCore().getLang().send(ally, "combat.downed-ally", LangManager.placeholders(
                        "player", player.getName()
                ));
            }
        }
    }

    public void revivePlayer(Player downedPlayer, PlayerProfile downedProfile, Player healer) {
        downedProfile.setDowned(false);
        downedProfile.setCurrentStun(0.0);
        downedDeadlineMs.remove(downedPlayer.getUniqueId());
        reviveProgressSeconds.remove(downedPlayer.getUniqueId());

        downedPlayer.setSwimming(false);
        downedPlayer.removePotionEffect(PotionEffectType.SLOWNESS);
        downedPlayer.removePotionEffect(PotionEffectType.BLINDNESS);

        double ratio = plugin.getConfig().getDouble("downed-and-revive.revive-health-ratio", 0.40);
        double restoredHp = Math.max(4.0, getMaxHealth(downedPlayer) * ratio);
        downedPlayer.setHealth(Math.min(getMaxHealth(downedPlayer), restoredHp));
        downedPlayer.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 2, false, false, true));
        downedPlayer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1, false, false, true));

        downedPlayer.getWorld().playSound(downedPlayer.getLocation(), Sound.ITEM_TOTEM_USE, 0.85f, 1.25f);
        downedPlayer.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, downedPlayer.getLocation().add(0, 1.0, 0), 35, 0.4, 0.5, 0.4, 0.15);

        String healerName = healer != null ? healer.getName() : "Ascendant";
        plugin.getCore().getLang().send(downedPlayer, "combat.revived-success", LangManager.placeholders(
                "healer", healerName
        ));
        if (healer != null && !healer.equals(downedPlayer)) {
            plugin.getCore().getLang().send(healer, "combat.revived-healer", LangManager.placeholders(
                    "target", downedPlayer.getName()
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDownedMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
        if (profile != null && profile.isDowned()) {
            player.setSwimming(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        downedDeadlineMs.remove(id);
        reviveProgressSeconds.remove(id);
    }

    private void tickDownedAndRevive() {
        if (downedDeadlineMs.isEmpty()) return;

        long now = System.currentTimeMillis();
        double requiredSec = plugin.getConfig().getDouble("downed-and-revive.revive-channel-seconds", 3.0);
        double radius = plugin.getConfig().getDouble("downed-and-revive.revive-radius-blocks", 3.5);
        double radiusSq = radius * radius;

        for (Map.Entry<UUID, Long> entry : downedDeadlineMs.entrySet()) {
            UUID downedId = entry.getKey();
            Player downed = Bukkit.getPlayer(downedId);
            if (downed == null || !downed.isOnline()) continue;

            PlayerProfile downedProfile = plugin.getCore().getProfileManager().getCachedProfile(downedId);
            if (downedProfile == null || !downedProfile.isDowned()) {
                downedDeadlineMs.remove(downedId);
                reviveProgressSeconds.remove(downedId);
                continue;
            }

            // 1. Buscar si hay un aliado vivo agachado (Shift) dentro del radio de revivir
            Player activeHealer = null;
            for (Player candidate : downed.getWorld().getPlayers()) {
                if (candidate.equals(downed) || !candidate.isSneaking() || candidate.isDead()) continue;
                PlayerProfile candProfile = plugin.getCore().getProfileManager().getCachedProfile(candidate.getUniqueId());
                if (candProfile != null && (candProfile.isDowned() || candProfile.isStunned())) continue;
                if (candidate.getLocation().distanceSquared(downed.getLocation()) <= radiusSq) {
                    activeHealer = candidate;
                    break;
                }
            }

            if (activeHealer != null) {
                double progress = reviveProgressSeconds.getOrDefault(downedId, 0.0) + 0.25;
                // Mientras es revivido se congela la cuenta atrás de desangrado
                downedDeadlineMs.put(downedId, entry.getValue() + 250L);

                if (progress >= requiredSec) {
                    revivePlayer(downed, downedProfile, activeHealer);
                    continue;
                }

                reviveProgressSeconds.put(downedId, progress);
                int percent = (int) Math.min(100, Math.round((progress / requiredSec) * 100.0));
                String bar = buildProgressBar(percent);

                String healerMsg = plugin.getCore().getLang().format(
                        plugin.getCore().getLang().resolveLanguage(activeHealer),
                        "combat.reviving-progress",
                        LangManager.placeholders("target", downed.getName(), "bar", bar, "percent", String.valueOf(percent))
                );
                String downedMsg = plugin.getCore().getLang().format(
                        downedProfile.getLanguage(),
                        "combat.being-revived",
                        LangManager.placeholders("healer", activeHealer.getName(), "bar", bar, "percent", String.valueOf(percent))
                );
                activeHealer.sendActionBar(Component.text(healerMsg));
                downed.sendActionBar(Component.text(downedMsg));
                downed.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, downed.getLocation().add(0, 0.6, 0), 5, 0.35, 0.25, 0.35, 0.0);
                continue;
            }

            // 2. Si nadie lo está reviviendo, decae el progreso de revivir y avanza el desangrado
            double currentProgress = reviveProgressSeconds.getOrDefault(downedId, 0.0);
            if (currentProgress > 0.0) {
                reviveProgressSeconds.put(downedId, Math.max(0.0, currentProgress - 0.25));
            }

            downed.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, downed.getLocation().add(0, 0.5, 0), 2, 0.25, 0.15, 0.25, 0.01);

            if (now >= entry.getValue()) {
                downedProfile.setDowned(false);
                downedDeadlineMs.remove(downedId);
                reviveProgressSeconds.remove(downedId);
                downed.setSwimming(false);
                plugin.getCore().getLang().send(downed, "combat.bleedout-death");
                downed.setHealth(0.0);
            }
        }
    }

    private String buildProgressBar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        return "&a" + "■".repeat(filled) + "&7" + "□".repeat(10 - filled);
    }

    private double getMaxHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }
}

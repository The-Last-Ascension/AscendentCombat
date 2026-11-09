package com.thelastascension.ascendentCombat.mechanic;

import com.thelastascension.ascendentCombat.AscendentCombat;
import com.thelastascension.ascendentCombat.weapon.WeaponCombatTags;
import com.thelastascension.ascendentCore.i18n.LangManager;
import com.thelastascension.ascendentCore.model.ElementType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona los 8 Elementos de The Last Ascension y su Doble Sistema de Reacciones:
 * - Tipo 1: Reacciones de Impregnación sobre el enemigo (1J o Cooperativo).
 * - Tipo 2: Sinergias de Habilidades y Definitivas Conjuntas entre amigos (Cooperativo).
 */
public class ElementalReactionManager implements Listener {

    public record ImpregnationState(ElementType element, UUID sourcePlayer, long expiresAtMs) {}
    public record CoopSynergyWindow(UUID initiatorUuid, String initiatorName, ElementType element, Location location, long expiresAtMs) {}

    private final AscendentCombat plugin;
    private final WeaponCombatTags weaponTags;
    private final ParryAndStunManager parryAndStunManager;

    // Estado de impregnación elemental activo sobre cada entidad enemiga (por defecto ningún enemigo viene impregnado)
    private final Map<UUID, ImpregnationState> activeImpregnations = new ConcurrentHashMap<>();

    // Ventanas activas de Sinergia Cooperativa / Definitiva Conjunta (Tipo 2)
    private final Map<UUID, CoopSynergyWindow> activeCoopWindows = new ConcurrentHashMap<>();

    public ElementalReactionManager(AscendentCombat plugin, WeaponCombatTags weaponTags, ParryAndStunManager parryAndStunManager) {
        this.plugin = plugin;
        this.weaponTags = weaponTags;
        this.parryAndStunManager = parryAndStunManager;
    }

    public void shutdown() {
        activeImpregnations.clear();
        activeCoopWindows.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onElementalAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target.equals(attacker)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        ElementType attackElement = weaponTags.getWeaponElement(weapon);
        if (attackElement == null) return;

        long now = System.currentTimeMillis();
        ImpregnationState current = activeImpregnations.get(target.getUniqueId());

        // 1. Si no tiene impregnación activa o expiró -> Impregnar con el primer elemento
        if (current == null || now >= current.expiresAtMs() || current.element() == attackElement) {
            int durationSec = plugin.getConfig().getInt("elements.impregnation-duration-seconds", 8);
            activeImpregnations.put(target.getUniqueId(), new ImpregnationState(
                    attackElement,
                    attacker.getUniqueId(),
                    now + (durationSec * 1000L)
            ));

            spawnElementParticles(target.getLocation().add(0, 1.0, 0), attackElement, 14);
            if (current == null || now >= current.expiresAtMs()) {
                String lang = plugin.getCore().getLang().resolveLanguage(attacker);
                String elemName = plugin.getCore().getLang().format(lang, attackElement.getLangKey(), null);
                plugin.getCore().getLang().send(attacker, "combat.impregnated", LangManager.placeholders(
                        "target", target.getName(),
                        "element", elemName,
                        "status", attackElement.getDefaultStatusName()
                ));
            }
            return;
        }

        // 2. ¡El enemigo YA estaba impregnado con otro elemento distinto! -> Detonar Reacción Tipo 1
        ElementType firstElement = current.element();
        activeImpregnations.remove(target.getUniqueId());

        String reactionName = resolveImpregnationReactionName(firstElement, attackElement);
        double bonusDamage = plugin.getConfig().getDouble("elements.impregnation-reaction-damage-bonus", 8.0)
                * attackElement.getMultiplierAgainst(firstElement);
        double bonusStun = plugin.getConfig().getDouble("elements.impregnation-reaction-stun-bonus", 25.0);

        event.setDamage(event.getDamage() + bonusDamage);
        parryAndStunManager.addEnemyStun(target, bonusStun, attacker);

        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.1f, 1.25f);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.65f, 1.4f);
        spawnElementParticles(target.getLocation().add(0, 1.1, 0), firstElement, 25);
        spawnElementParticles(target.getLocation().add(0, 1.1, 0), attackElement, 25);

        String lang = plugin.getCore().getLang().resolveLanguage(attacker);
        String elem1Name = plugin.getCore().getLang().format(lang, firstElement.getLangKey(), null);
        String elem2Name = plugin.getCore().getLang().format(lang, attackElement.getLangKey(), null);

        plugin.getCore().getLang().send(attacker, "combat.reaction-triggered", LangManager.placeholders(
                "reaction", reactionName,
                "elem1", elem1Name,
                "elem2", elem2Name,
                "target", target.getName()
        ));
    }

    /**
     * Activa o combina una Sinergia Cooperativa / Definitiva Conjunta (Tipo 2).
     * Si otro jugador cercano ya abrió una ventana elemental, fusiona ambas en una Definitiva Conjunta.
     */
    public void triggerCoopSkillSynergy(Player player, ElementType element, boolean allowSoloTest) {
        long now = System.currentTimeMillis();
        double maxRadius = plugin.getConfig().getDouble("elements.coop-synergy-radius-blocks", 12.0);

        // Buscar si algún aliado cercano tiene una ventana abierta con un elemento distinto
        for (Map.Entry<UUID, CoopSynergyWindow> entry : activeCoopWindows.entrySet()) {
            CoopSynergyWindow window = entry.getValue();
            if (now >= window.expiresAtMs()) {
                activeCoopWindows.remove(entry.getKey());
                continue;
            }
            if (!allowSoloTest && window.initiatorUuid().equals(player.getUniqueId())) {
                continue;
            }
            if (window.element() == element) {
                continue;
            }
            if (!window.location().getWorld().equals(player.getWorld())) {
                continue;
            }
            if (window.location().distanceSquared(player.getLocation()) <= (maxRadius * maxRadius)) {
                activeCoopWindows.remove(entry.getKey());
                executeJointUltimate(window, player, element);
                return;
            }
        }

        // Si no había ventana previa compatible, abrir nueva ventana de sinergia para los amigos cercanos
        int windowSec = plugin.getConfig().getInt("elements.coop-synergy-window-seconds", 4);
        CoopSynergyWindow newWindow = new CoopSynergyWindow(
                player.getUniqueId(),
                player.getName(),
                element,
                player.getLocation(),
                now + (windowSec * 1000L)
        );
        activeCoopWindows.put(player.getUniqueId(), newWindow);

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
        spawnElementParticles(player.getLocation().add(0, 1.0, 0), element, 35);

        for (Player nearby : player.getWorld().getPlayers()) {
            if (nearby.getLocation().distanceSquared(player.getLocation()) <= (maxRadius * maxRadius)) {
                String lang = plugin.getCore().getLang().resolveLanguage(nearby);
                String elemName = plugin.getCore().getLang().format(lang, element.getLangKey(), null);
                plugin.getCore().getLang().send(nearby, "combat.coop-window-opened", LangManager.placeholders(
                        "player", player.getName(),
                        "element", elemName
                ));
            }
        }
    }

    private void executeJointUltimate(CoopSynergyWindow first, Player secondPlayer, ElementType secondElement) {
        Location center = secondPlayer.getLocation();
        String jointName = resolveJointUltimateName(first.element(), secondElement);
        double radius = plugin.getConfig().getDouble("elements.coop-synergy-radius-blocks", 12.0);
        double damage = plugin.getConfig().getDouble("elements.coop-synergy-damage", 24.0);
        double stun = plugin.getConfig().getDouble("elements.coop-synergy-stun", 60.0);

        center.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.9f, 1.3f);
        center.getWorld().playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.1f);
        center.getWorld().strikeLightningEffect(center);
        spawnElementParticles(center.clone().add(0, 1.2, 0), first.element(), 60);
        spawnElementParticles(center.clone().add(0, 1.2, 0), secondElement, 60);

        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                living.damage(damage, secondPlayer);
                parryAndStunManager.addEnemyStun(living, stun, secondPlayer);
            }
        }

        for (Player nearby : center.getWorld().getPlayers()) {
            if (nearby.getLocation().distanceSquared(center) <= (radius * radius * 2)) {
                plugin.getCore().getLang().send(nearby, "combat.coop-ultimate-triggered", LangManager.placeholders(
                        "name", jointName,
                        "player1", first.initiatorName(),
                        "player2", secondPlayer.getName()
                ));
            }
        }
    }

    private String resolveImpregnationReactionName(ElementType a, ElementType b) {
        if (hasPair(a, b, ElementType.IGNEO, ElementType.CEFIRO)) return "Tormenta Ígnea";
        if (hasPair(a, b, ElementType.IGNEO, ElementType.SYLVA)) return "Combustión Abisal";
        if (hasPair(a, b, ElementType.IGNEO, ElementType.CRIO)) return "Fusión Térmica";
        if (hasPair(a, b, ElementType.FULMEN, ElementType.CRIO)) return "Superconductividad";
        if (hasPair(a, b, ElementType.TERRA, ElementType.FULMEN)) return "Pulso Magnético";
        if (hasPair(a, b, ElementType.SYLVA, ElementType.TERRA)) return "Raíz Petrficadora";
        if (hasPair(a, b, ElementType.UMBRA, ElementType.ASTRAL)) return "Eclipse Cósmico";
        return "Resonancia Elemental (" + a.name() + " + " + b.name() + ")";
    }

    private String resolveJointUltimateName(ElementType a, ElementType b) {
        if (hasPair(a, b, ElementType.IGNEO, ElementType.CEFIRO)) return "Cataclismo del Dragón de Ceniza";
        if (hasPair(a, b, ElementType.UMBRA, ElementType.ASTRAL)) return "Juicio del Horizonte de Sucesos";
        if (hasPair(a, b, ElementType.FULMEN, ElementType.CRIO)) return "Tempestad de Cero Absoluto";
        if (hasPair(a, b, ElementType.SYLVA, ElementType.TERRA)) return "Despertar del Titán Primordial";
        return "Ascensión Convergente: " + a.name() + " & " + b.name();
    }

    private boolean hasPair(ElementType a, ElementType b, ElementType x, ElementType y) {
        return (a == x && b == y) || (a == y && b == x);
    }

    private void spawnElementParticles(Location loc, ElementType element, int count) {
        if (loc.getWorld() == null) return;
        Particle particle = switch (element) {
            case IGNEO -> Particle.FLAME;
            case SYLVA -> Particle.SPORE_BLOSSOM_AIR;
            case TERRA -> Particle.CRIT;
            case FULMEN -> Particle.ELECTRIC_SPARK;
            case CRIO -> Particle.SNOWFLAKE;
            case CEFIRO -> Particle.CLOUD;
            case UMBRA -> Particle.SOUL_FIRE_FLAME;
            case ASTRAL -> Particle.END_ROD;
        };
        loc.getWorld().spawnParticle(particle, loc, count, 0.45, 0.45, 0.45, 0.05);
    }
}

package com.thelastascension.ascendentCombat.command;

import com.thelastascension.ascendentCombat.AscendentCombat;
import com.thelastascension.ascendentCore.i18n.LangManager;
import com.thelastascension.ascendentCore.model.ElementType;
import com.thelastascension.ascendentCore.model.PlayerProfile;
import com.thelastascension.ascendentCore.model.StatType;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Comando /combat (/asccombat) para inspeccionar y probar todas las mecánicas de AscendentCombat:
 * Parry, Bloqueo, Stun, Armas, Elementos (Tipo 1 y Tipo 2) y Abatido/Revivir.
 */
public class CombatCommand implements CommandExecutor, TabCompleter {

    private final AscendentCombat plugin;

    public CombatCommand(AscendentCombat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            plugin.getCore().getLang().send(sender, "system.player-only");
            return true;
        }

        PlayerProfile profile = plugin.getCore().getProfileManager().getCachedProfile(player.getUniqueId());
        if (profile == null) return true;

        switch (sub) {
            case "info" -> handleInfo(player, profile);
            case "weapon" -> handleWeapon(player, args);
            case "dummy" -> handleDummy(player, args);
            case "coop" -> handleCoop(player, args);
            case "downed" -> {
                if (!player.hasPermission("ascendent.combat.admin")) {
                    plugin.getCore().getLang().send(player, "system.no-permission");
                    return true;
                }
                plugin.getDownedReviveManager().enterDownedState(player, profile);
            }
            case "revive" -> {
                if (!player.hasPermission("ascendent.combat.admin")) {
                    plugin.getCore().getLang().send(player, "system.no-permission");
                    return true;
                }
                plugin.getDownedReviveManager().revivePlayer(player, profile, player);
            }
            default -> handleInfo(player, profile);
        }
        return true;
    }

    private void handleInfo(Player player, PlayerProfile profile) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String itemName = (held.getType() == Material.AIR) ? "Ninguna" : held.getType().name();
        boolean canBlock = plugin.getWeaponTags().canBlock(held);
        boolean canParry = plugin.getWeaponTags().canParry(held);
        ElementType elem = plugin.getWeaponTags().getWeaponElement(held);
        String lang = profile.getLanguage();
        String elemStr = elem == null ? "&7Físico" : plugin.getCore().getLang().format(lang, elem.getLangKey(), null);

        plugin.getCore().getLang().send(player, "combat.cmd-info-header", LangManager.placeholders("player", player.getName()));
        plugin.getCore().getLang().sendRaw(player, "combat.cmd-info-posture", LangManager.placeholders(
                "stun", String.format("%.0f", profile.getCurrentStun()),
                "max_stun", String.format("%.0f", profile.getStat(StatType.MAX_STUN)),
                "stunned", profile.isStunned() ? "&cSí" : "&aNo",
                "downed", profile.isDowned() ? "&cSí" : "&aNo"
        ));
        plugin.getCore().getLang().sendRaw(player, "combat.cmd-info-weapon", LangManager.placeholders(
                "item", itemName,
                "block", canBlock ? "Sí" : "No",
                "parry", canParry ? "Sí" : "No",
                "element", elemStr
        ));
    }

    private void handleWeapon(Player player, String[] args) {
        if (!player.hasPermission("ascendent.combat.admin")) {
            plugin.getCore().getLang().send(player, "system.no-permission");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            plugin.getCore().getLang().send(player, "combat.cmd-no-item");
            return;
        }
        if (args.length < 3) {
            player.sendMessage(LangManager.colorize("&eUso: /combat weapon <block|parry|element> <true|false|elemento|none>"));
            return;
        }

        String prop = args[1].toLowerCase(Locale.ROOT);
        String val = args[2].toLowerCase(Locale.ROOT);

        switch (prop) {
            case "block" -> {
                boolean b = Boolean.parseBoolean(val);
                plugin.getWeaponTags().setCanBlock(held, b);
                plugin.getCore().getLang().send(player, "combat.cmd-weapon-updated", LangManager.placeholders("prop", "can_block", "value", String.valueOf(b)));
            }
            case "parry" -> {
                boolean p = Boolean.parseBoolean(val);
                plugin.getWeaponTags().setCanParry(held, p);
                plugin.getCore().getLang().send(player, "combat.cmd-weapon-updated", LangManager.placeholders("prop", "can_parry", "value", String.valueOf(p)));
            }
            case "element" -> {
                if (val.equals("none") || val.equals("fisico")) {
                    plugin.getWeaponTags().setWeaponElement(held, null);
                    plugin.getCore().getLang().send(player, "combat.cmd-weapon-updated", LangManager.placeholders("prop", "element", "value", "Ninguno (Físico)"));
                } else {
                    try {
                        ElementType elem = ElementType.valueOf(val.toUpperCase(Locale.ROOT));
                        plugin.getWeaponTags().setWeaponElement(held, elem);
                        plugin.getCore().getLang().send(player, "combat.cmd-weapon-updated", LangManager.placeholders("prop", "element", "value", elem.name()));
                    } catch (IllegalArgumentException ex) {
                        player.sendMessage(LangManager.colorize("&cElemento inválido. Usa: igneo, sylva, terra, fulmen, crio, cefiro, umbra, astral, none."));
                    }
                }
            }
            default -> player.sendMessage(LangManager.colorize("&eUso: /combat weapon <block|parry|element> <valor>"));
        }
    }

    private void handleDummy(Player player, String[] args) {
        if (!player.hasPermission("ascendent.combat.admin")) {
            plugin.getCore().getLang().send(player, "system.no-permission");
            return;
        }
        boolean parryable = args.length < 2 || !args[1].equalsIgnoreCase("unparryable");
        Husk dummy = (Husk) player.getWorld().spawnEntity(player.getLocation().add(player.getLocation().getDirection().multiply(2.0).setY(0)), EntityType.HUSK);

        String modeLabel = parryable ? "Ataques Parreables" : "Ataques Imparables (Solo Bloqueo)";
        dummy.setCustomName(LangManager.colorize(parryable
                ? "&6&l[Ascendant] &eGuerrero de Prueba &7(&bParreable&7)"
                : "&4&l[Ascendant] &cColoso Imparable &7(&6No Parreable&7)"));
        dummy.setCustomNameVisible(true);
        dummy.setPersistent(true);
        dummy.setRemoveWhenFarAway(false);

        AttributeInstance maxHp = dummy.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(200.0);
            dummy.setHealth(200.0);
        }

        plugin.getWeaponTags().setAttackParryable(dummy, parryable);
        plugin.getCore().getLang().send(player, "combat.cmd-dummy-spawned", LangManager.placeholders("mode", modeLabel));
    }

    private void handleCoop(Player player, String[] args) {
        ElementType elem = plugin.getWeaponTags().getWeaponElement(player.getInventory().getItemInMainHand());
        if (args.length >= 2) {
            try {
                elem = ElementType.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (elem == null) {
            elem = ElementType.IGNEO;
        }
        boolean allowSolo = args.length >= 3 && args[2].equalsIgnoreCase("solo");
        plugin.getElementalReactionManager().triggerCoopSkillSynergy(player, elem, allowSolo);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "weapon", "dummy", "coop", "downed", "revive"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "weapon" -> filter(List.of("block", "parry", "element"), args[1]);
                case "dummy" -> filter(List.of("parryable", "unparryable"), args[1]);
                case "coop" -> filter(Arrays.stream(ElementType.values()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("weapon")) {
                if (args[1].equalsIgnoreCase("element")) {
                    List<String> elems = new ArrayList<>(Arrays.stream(ElementType.values()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList());
                    elems.add("none");
                    return filter(elems, args[2]);
                }
                return filter(List.of("true", "false"), args[2]);
            }
            if (args[0].equalsIgnoreCase("coop")) {
                return filter(List.of("solo"), args[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }
}

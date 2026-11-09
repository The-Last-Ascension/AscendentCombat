package com.thelastascension.ascendentCombat.weapon;

import com.thelastascension.ascendentCombat.AscendentCombat;
import com.thelastascension.ascendentCore.model.ElementType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Gestiona las propiedades de combate de armas y ataques enemigos mediante PersistentDataContainer:
 * - can_block (boolean): Si el arma puede mantener bloqueo con clic derecho.
 * - can_parry (boolean): Si el arma puede ejecutar Parry perfecto con clic derecho.
 * - element (ElementType): Elemento imbuido en el arma (opcional).
 * - unparryable (boolean en entidad): Si el ataque actual del enemigo NO admite parry.
 */
public class WeaponCombatTags {

    private final NamespacedKey keyCanBlock;
    private final NamespacedKey keyCanParry;
    private final NamespacedKey keyElement;
    private final NamespacedKey keyMitigation;
    private final NamespacedKey keyUnparryableAttack;

    public WeaponCombatTags(AscendentCombat plugin) {
        this.keyCanBlock = new NamespacedKey(plugin, "can_block");
        this.keyCanParry = new NamespacedKey(plugin, "can_parry");
        this.keyElement = new NamespacedKey(plugin, "element");
        this.keyMitigation = new NamespacedKey(plugin, "block_mitigation");
        this.keyUnparryableAttack = new NamespacedKey(plugin, "unparryable_attack");
    }

    public boolean canBlock(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Byte val = meta.getPersistentDataContainer().get(keyCanBlock, PersistentDataType.BYTE);
            if (val != null) return val == (byte) 1;
        }
        return isDefaultMeleeGuardWeapon(item.getType());
    }

    public boolean canParry(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Byte val = meta.getPersistentDataContainer().get(keyCanParry, PersistentDataType.BYTE);
            if (val != null) return val == (byte) 1;
        }
        return isDefaultMeleeGuardWeapon(item.getType());
    }

    public void setCanBlock(ItemStack item, boolean canBlock) {
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(keyCanBlock, PersistentDataType.BYTE, (byte) (canBlock ? 1 : 0));
        item.setItemMeta(meta);
    }

    public void setCanParry(ItemStack item, boolean canParry) {
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(keyCanParry, PersistentDataType.BYTE, (byte) (canParry ? 1 : 0));
        item.setItemMeta(meta);
    }

    public ElementType getWeaponElement(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(keyElement, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return null;
        try {
            return ElementType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void setWeaponElement(ItemStack item, ElementType element) {
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (element == null) {
            pdc.remove(keyElement);
        } else {
            pdc.set(keyElement, PersistentDataType.STRING, element.name());
        }
        item.setItemMeta(meta);
    }

    public double getBlockMitigation(ItemStack item, double defaultMitigation) {
        if (item == null || item.getType() == Material.AIR) return defaultMitigation;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return defaultMitigation;
        Double val = meta.getPersistentDataContainer().get(keyMitigation, PersistentDataType.DOUBLE);
        return val != null ? Math.max(0.0, Math.min(0.95, val)) : defaultMitigation;
    }

    public boolean isAttackParryable(Entity attacker) {
        if (attacker == null) return true;
        Byte unparryable = attacker.getPersistentDataContainer().get(keyUnparryableAttack, PersistentDataType.BYTE);
        return unparryable == null || unparryable == (byte) 0;
    }

    public void setAttackParryable(Entity attacker, boolean parryable) {
        if (attacker == null) return;
        attacker.getPersistentDataContainer().set(
                keyUnparryableAttack,
                PersistentDataType.BYTE,
                (byte) (parryable ? 0 : 1)
        );
    }

    private boolean isDefaultMeleeGuardWeapon(Material mat) {
        String name = mat.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || mat == Material.MACE
                || mat == Material.TRIDENT
                || mat == Material.SHIELD;
    }
}

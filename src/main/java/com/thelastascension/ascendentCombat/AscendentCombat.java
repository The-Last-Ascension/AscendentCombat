package com.thelastascension.ascendentCombat;

import com.thelastascension.ascendentCombat.command.CombatCommand;
import com.thelastascension.ascendentCombat.mechanic.DownedReviveManager;
import com.thelastascension.ascendentCombat.mechanic.ElementalReactionManager;
import com.thelastascension.ascendentCombat.mechanic.ParryAndStunManager;
import com.thelastascension.ascendentCombat.weapon.WeaponCombatTags;
import com.thelastascension.ascendentCore.AscendentCore;
import com.thelastascension.ascendentCore.module.AscendentModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AscendentCombat v1.0.0 — Módulo de combate de The Last Ascension (Ascendant).
 * Implementa el sistema de Parry y Bloqueo con Clic Derecho (estilo Grounded 2),
 * Barra de Stun de jugadores y enemigos, los 8 Elementos con Doble Sistema de Reacciones
 * (Impregnación sobre enemigos + Definitivas Conjuntas entre amigos) y Abatido/Revivir.
 */
public final class AscendentCombat extends JavaPlugin implements AscendentModule {

    private AscendentCore core;
    private WeaponCombatTags weaponTags;
    private ParryAndStunManager parryAndStunManager;
    private ElementalReactionManager elementalReactionManager;
    private DownedReviveManager downedReviveManager;

    @Override
    public void onEnable() {
        this.core = AscendentCore.getInstance();
        if (this.core == null) {
            getLogger().severe("No se encontró AscendentCore activo. Desactivando AscendentCombat.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        this.core.getLang().registerPluginLanguageFiles(this, "lang/es_es.yml", "lang/en_us.yml");

        this.weaponTags = new WeaponCombatTags(this);
        this.parryAndStunManager = new ParryAndStunManager(this, this.weaponTags);
        this.elementalReactionManager = new ElementalReactionManager(this, this.weaponTags, this.parryAndStunManager);
        this.downedReviveManager = new DownedReviveManager(this);

        getServer().getPluginManager().registerEvents(this.parryAndStunManager, this);
        getServer().getPluginManager().registerEvents(this.elementalReactionManager, this);
        getServer().getPluginManager().registerEvents(this.downedReviveManager, this);

        this.parryAndStunManager.startTasks();
        this.downedReviveManager.startTasks();

        PluginCommand cmd = getCommand("combat");
        if (cmd != null) {
            CombatCommand executor = new CombatCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        this.core.getModuleRegistry().registerModule(this);
        getLogger().info("AscendentCombat v" + getVersion() + " activado (Parry, Stun, 8 Elementos y Revivir listos).");
    }

    @Override
    public void onDisable() {
        if (this.core != null && this.core.getModuleRegistry() != null) {
            this.core.getModuleRegistry().unregisterModule(getModuleId());
        }
        if (this.parryAndStunManager != null) {
            this.parryAndStunManager.shutdown();
        }
        if (this.elementalReactionManager != null) {
            this.elementalReactionManager.shutdown();
        }
        if (this.downedReviveManager != null) {
            this.downedReviveManager.shutdown();
        }
    }

    @Override
    public String getModuleId() {
        return "AscendentCombat";
    }

    @Override
    public org.bukkit.plugin.Plugin getPlugin() {
        return this;
    }

    @Override
    public String getVersion() {
        return getPluginMeta().getVersion();
    }

    @Override
    public void onReload() {
        reloadConfig();
        if (this.core != null) {
            this.core.getLang().registerPluginLanguageFiles(this, "lang/es_es.yml", "lang/en_us.yml");
        }
    }

    public AscendentCore getCore() {
        return core;
    }

    public WeaponCombatTags getWeaponTags() {
        return weaponTags;
    }

    public ParryAndStunManager getParryAndStunManager() {
        return parryAndStunManager;
    }

    public ElementalReactionManager getElementalReactionManager() {
        return elementalReactionManager;
    }

    public DownedReviveManager getDownedReviveManager() {
        return downedReviveManager;
    }
}

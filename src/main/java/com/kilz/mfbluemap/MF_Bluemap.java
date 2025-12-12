package com.kilz.mfbluemap;

import com.dansplugins.factionsystem.MedievalFactions;
import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MF_Bluemap extends JavaPlugin {

    private BlueMapIntegration blueMapIntegration;
    private MedievalFactions medievalFactions;

    @Override
    public void onEnable() {
        // Obtain MedievalFactions instance
        if (Bukkit.getPluginManager().isPluginEnabled("MedievalFactions")) {
            this.medievalFactions = (MedievalFactions) Bukkit.getPluginManager().getPlugin("MedievalFactions");
        } else {
            getLogger().severe("MedievalFactions not found! Disabling MF_Bluemap.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Integration
        this.blueMapIntegration = new BlueMapIntegration(this);

        // Hook into BlueMap
        BlueMapAPI.onEnable(api -> {
            getLogger().info("BlueMap API enabled. Hooking MedievalFactions...");
            blueMapIntegration.enable(api, medievalFactions);
        });

        // Save default config
        saveDefaultConfig();

        getLogger().info("MF_Bluemap has been enabled!");
    }

    @Override
    public void onDisable() {
        if (this.blueMapIntegration != null) {
            this.blueMapIntegration.disable();
        }
    }
}

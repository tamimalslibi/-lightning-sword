package com.lightningsword;

import org.bukkit.plugin.java.JavaPlugin;

public class LightningSwordPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        LightningSwordFeature feature = new LightningSwordFeature(this);
        getServer().getPluginManager().registerEvents(feature, this);
        getCommand("lightningsword").setExecutor(feature);
        getLogger().info("Lightning Sword plugin enabled!");
    }
}

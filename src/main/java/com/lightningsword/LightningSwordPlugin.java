package com.lightningsword;

import org.bukkit.plugin.java.JavaPlugin;

public class LightningSwordPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        LightningSwordFeature feature = new LightningSwordFeature(this);
        getServer().getPluginManager().registerEvents(feature, this);
        getCommand("lightningsword").setExecutor(feature);

        IceSwordFeature iceSword = new IceSwordFeature(this);
        getServer().getPluginManager().registerEvents(iceSword, this);
        getCommand("icesword").setExecutor(iceSword);

        BloodSwordFeature bloodSword = new BloodSwordFeature(this);
        getServer().getPluginManager().registerEvents(bloodSword, this);
        getCommand("bloodsword").setExecutor(bloodSword);

        WardenSwordFeature wardenSword = new WardenSwordFeature(this);
        getServer().getPluginManager().registerEvents(wardenSword, this);
        getCommand("wardensword").setExecutor(wardenSword);

        getLogger().info("Lightning Sword plugin enabled!");
    }
}

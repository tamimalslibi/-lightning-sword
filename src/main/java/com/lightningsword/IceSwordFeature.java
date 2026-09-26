package com.lightningsword;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class IceSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;
    private final Random random = new Random();
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();

    private static final double FREEZE_CHANCE = 0.20;
    private static final int FREEZE_DURATION_TICKS = 60;
    private static final int FREEZE_AMPLIFIER = 4;
    private static final long MIN_HIT_INTERVAL_MS = 600;

    public IceSwordFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "ice_sword");
    }

    public ItemStack createIceSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();

        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Ice Sword");
        meta.setLore(List.of(
                ChatColor.AQUA + "Has a 20% chance to freeze enemies."
        ));

        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 1, true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isIceSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("icesword")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        player.getInventory().addItem(createIceSword());
        player.sendMessage(ChatColor.AQUA + "You have been given the " + ChatColor.BOLD + "Ice Sword" + ChatColor.AQUA + "!");
        return true;
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        ItemStack weapon = player.getInventory().getItemInMainHand();

        if (!isIceSword(weapon)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastCountedHit.getOrDefault(uuid, 0L);

        if (now - last < MIN_HIT_INTERVAL_MS) return;

        lastCountedHit.put(uuid, now);

        if (random.nextDouble() < FREEZE_CHANCE) {
            LivingEntity target = (LivingEntity) event.getEntity();
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, FREEZE_DURATION_TICKS, FREEZE_AMPLIFIER));
        }
    }
}

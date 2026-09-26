package com.lightningsword;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Warden Sword feature.
 * - Gives a fully enchanted netherite sword via /wardensword (OP only)
 * - Shift + right-click: fires a Warden-style sonic boom in the direction you're facing.
 *   A series of sonic boom particle rings travels along the beam toward whatever it hits
 *   (or out to max range on a miss), then deals damage on arrival, with the real Warden
 *   sonic boom sound. On a cooldown.
 * - Uses its own PersistentDataContainer tag so it never interferes with your other swords
 */
public class WardenSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;

    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    // --- Tunable numbers ---
    private static final double SONIC_BOOM_DAMAGE = 5.0;       // 5.0 = 2.5 hearts
    private static final double SONIC_BOOM_RANGE = 20.0;       // max distance the beam checks, in blocks
    private static final double SONIC_BOOM_CONE_DEGREES = 15;  // how narrow the "beam" is
    private static final long ABILITY_COOLDOWN_MS = 12_000;    // 12 second cooldown
    private static final double TRAVEL_STEP = 1.0;             // blocks the effect advances per tick
    private static final long TRAVEL_TICK_PERIOD = 1L;         // how often (in ticks) the effect advances

    public WardenSwordFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "warden_sword");
    }

    public ItemStack createWardenSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();

        meta.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Warden Sword");
        meta.setLore(List.of(
                ChatColor.DARK_AQUA + "Shift + right-click to unleash",
                ChatColor.DARK_AQUA + "a sonic boom attack."
        ));

        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isWardenSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("wardensword")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        player.getInventory().addItem(createWardenSword());
        player.sendMessage(ChatColor.DARK_AQUA + "You have been given the " + ChatColor.BOLD + "Warden Sword" + ChatColor.DARK_AQUA + "!");
        return true;
    }

    @EventHandler
    public void onAbilityTrigger(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        boolean rightClick = event.getAction().name().contains("RIGHT_CLICK");
        if (!rightClick) return;
        if (!player.isSneaking()) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isWardenSword(weapon)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = abilityCooldowns.getOrDefault(uuid, 0L);

        if (now - lastUse < ABILITY_COOLDOWN_MS) {
            long secondsLeft = (ABILITY_COOLDOWN_MS - (now - lastUse)) / 1000 + 1;
            player.sendMessage(ChatColor.RED + "Sonic boom on cooldown (" + secondsLeft + "s left).");
            return;
        }

        event.setCancelled(true);
        abilityCooldowns.put(uuid, now);

        fireSonicBoom(player);
    }

    private void fireSonicBoom(Player player) {
        Location eyeLoc = player.getEyeLocation();
        Vector facing = eyeLoc.getDirection().normalize();

        Collection<Entity> nearby = player.getWorld().getNearbyEntities(
                eyeLoc, SONIC_BOOM_RANGE, SONIC_BOOM_RANGE, SONIC_BOOM_RANGE);

        LivingEntity closestTarget = null;
        double closestDistance = Double.MAX_VALUE;

        for (Entity entity : nearby) {
            if (entity.getUniqueId().equals(player.getUniqueId())) continue;
            if (!(entity instanceof LivingEntity)) continue;

            Vector toEntity = entity.getLocation().toVector().subtract(eyeLoc.toVector());
            double distance = toEntity.length();
            if (distance > SONIC_BOOM_RANGE || distance == 0) continue;

            double angle = Math.toDegrees(facing.angle(toEntity.normalize()));
            if (angle <= SONIC_BOOM_CONE_DEGREES && distance < closestDistance) {
                closestDistance = distance;
                closestTarget = (LivingEntity) entity;
            }
        }

        double travelDistance = (closestTarget != null) ? closestDistance : SONIC_BOOM_RANGE;
        LivingEntity finalTarget = closestTarget;

        player.getWorld().playSound(eyeLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);

        // Animate a series of sonic boom rings traveling from the player out to the target/max range
        new BukkitRunnable() {
            double traveled = 0;

            @Override
            public void run() {
                if (traveled >= travelDistance) {
                    if (finalTarget != null) {
                        finalTarget.damage(SONIC_BOOM_DAMAGE, player);
                    }
                    cancel();
                    return;
                }

                Location point = eyeLoc.clone().add(facing.clone().multiply(traveled));
                player.getWorld().spawnParticle(Particle.SONIC_BOOM, point, 1);

                traveled += TRAVEL_STEP;
            }
        }.runTaskTimer(plugin, 0L, TRAVEL_TICK_PERIOD);
    }
}

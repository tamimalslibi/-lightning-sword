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
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Warden Sword feature.
 * - Gives a fully enchanted sword via /wardensword (OP only)
 * - Shift + right-click: fires a Warden-style sonic boom in the direction you're facing,
 *   hitting the closest living entity within a narrow cone and dealing damage, with the
 *   real Warden sonic boom sound + particle effect. On a cooldown.
 * - Uses its own PersistentDataContainer tag so it never interferes with your other swords
 */
public class WardenSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;

    // Tracks the last time (ms) each player used the sonic boom ability
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    // --- Tunable numbers ---
    private static final double SONIC_BOOM_DAMAGE = 10.0;     // damage dealt to whatever the beam hits
    private static final double SONIC_BOOM_RANGE = 20.0;      // max distance the beam checks, in blocks
    private static final double SONIC_BOOM_CONE_DEGREES = 15; // how narrow the "beam" is
    private static final long ABILITY_COOLDOWN_MS = 12_000;   // 12 second cooldown

    public WardenSwordFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "warden_sword");
    }

    // ---------- Item creation ----------

    public ItemStack createWardenSword() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();

        meta.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Warden Sword");
        meta.setLore(List.of(
                ChatColor.DARK_AQUA + "Shift + right-click to unleash",
                ChatColor.DARK_AQUA + "a sonic boom attack."
        ));

        // NOTE: on 1.20.5+ (new enchantment registry) swap to Enchantment.SHARPNESS,
        // Enchantment.LOOTING, Enchantment.UNBREAKING, Enchantment.MENDING instead.
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);      // Sharpness V
        meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true); // Looting III
        meta.addEnchant(Enchantment.DURABILITY, 3, true);      // Unbreaking III
        meta.addEnchant(Enchantment.MENDING, 1, true);         // Mending

        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isWardenSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    // ---------- Command: /wardensword (OP only) ----------

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

    // ---------- Shift + right-click ability: sonic boom ----------

    @EventHandler
    public void onAbilityTrigger(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        boolean rightClick = event.getAction().name().contains("RIGHT_CLICK");
        if (!rightClick) return;
        if (!player.isSneaking()) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isWardenSword(weapon)) return; // ignore for every other sword

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

        // Play the sonic boom effect along the beam either way, so it feels consistent even on a miss
        Location endPoint = closestTarget != null
                ? closestTarget.getLocation()
                : eyeLoc.clone().add(facing.multiply(SONIC_BOOM_RANGE));

        player.getWorld().playSound(eyeLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
        player.getWorld().spawnParticle(Particle.SONIC_BOOM, endPoint, 1);

        if (closestTarget != null) {
            closestTarget.damage(SONIC_BOOM_DAMAGE, player);
        }
    }
}

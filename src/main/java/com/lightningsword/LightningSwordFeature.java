package com.lightningsword;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Lightning Sword feature.
 * - Gives a fully enchanted netherite sword via /lightningsword (OP only), dealing normal sword damage
 * - Passive: every fully-charged hit has a 15% chance to strike 1 (visual-only) lightning bolt on the target.
 *   A hit only "counts" toward that roll if it lands at least MIN_HIT_INTERVAL_MS after the last counted hit,
 *   so spam-clicking can't force extra procs.
 * - Shift + right-click: AoE ability that strikes lightning around the player and deals decent
 *   custom damage to nearby entities (NOT the game's real lightning damage), on a cooldown
 * - Uses a PersistentDataContainer tag so it never interferes with other custom swords
 */
public class LightningSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;

    private final Map<UUID, Long> lastCountedHit = new HashMap<>();
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    private final Random random = new Random();

    private static final double PASSIVE_PROC_CHANCE = 0.15;
    private static final long MIN_HIT_INTERVAL_MS = 600;
    private static final double ABILITY_RADIUS = 6.0;
    private static final double ABILITY_DAMAGE = 16.0;
    private static final long ABILITY_COOLDOWN_MS = 15_000;

    public LightningSwordFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "lightning_sword");
    }

    public ItemStack createLightningSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();

        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Lightning Sword");
        meta.setLore(List.of(
                ChatColor.YELLOW + "Deals normal sword damage.",
                ChatColor.YELLOW + "Passive: 15% chance per solid hit to",
                ChatColor.YELLOW + "strike the target with lightning.",
                ChatColor.YELLOW + "Shift + right-click: call down a storm",
                ChatColor.YELLOW + "that deals decent damage nearby."
        ));

        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isLightningSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lightningsword")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        player.getInventory().addItem(createLightningSword());
        player.sendMessage(ChatColor.YELLOW + "You have been given the " + ChatColor.BOLD + "Lightning Sword" + ChatColor.YELLOW + "!");
        return true;
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        ItemStack weapon = player.getInventory().getItemInMainHand();

        if (!isLightningSword(weapon)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastCountedHit.getOrDefault(uuid, 0L);

        if (now - last < MIN_HIT_INTERVAL_MS) return;

        lastCountedHit.put(uuid, now);

        if (random.nextDouble() < PASSIVE_PROC_CHANCE) {
            LivingEntity target = (LivingEntity) event.getEntity();
            Location loc = target.getLocation();
            loc.getWorld().strikeLightningEffect(loc);
        }
    }

    @EventHandler
    public void onAbilityTrigger(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        boolean rightClick = event.getAction().name().contains("RIGHT_CLICK");
        if (!rightClick) return;
        if (!player.isSneaking()) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isLightningSword(weapon)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = abilityCooldowns.getOrDefault(uuid, 0L);

        if (now - lastUse < ABILITY_COOLDOWN_MS) {
            long secondsLeft = (ABILITY_COOLDOWN_MS - (now - lastUse)) / 1000 + 1;
            player.sendMessage(ChatColor.RED + "Lightning Sword ability on cooldown (" + secondsLeft + "s left).");
            return;
        }

        event.setCancelled(true);
        abilityCooldowns.put(uuid, now);

        Collection<Entity> nearby = player.getWorld().getNearbyEntities(
                player.getLocation(), ABILITY_RADIUS, ABILITY_RADIUS, ABILITY_RADIUS);

        int struck = 0;
        for (Entity entity : nearby) {
            if (entity.getUniqueId().equals(uuid)) continue;
            if (!(entity instanceof LivingEntity)) continue;

            LivingEntity target = (LivingEntity) entity;
            Location loc = target.getLocation();

            target.getWorld().strikeLightningEffect(loc);
            target.damage(ABILITY_DAMAGE, player);
            struck++;
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        player.sendMessage(ChatColor.YELLOW + "You call down the storm! " + ChatColor.GRAY
                + "(" + struck + " target" + (struck == 1 ? "" : "s") + " struck)");
    }
}

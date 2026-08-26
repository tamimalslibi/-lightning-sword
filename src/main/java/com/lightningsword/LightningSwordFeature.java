package com.lightningsword;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Sound;
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
 * - Gives a fully enchanted sword via /lightningsword
 * - Deals a bit of extra flat damage on top of the sword + enchant damage
 * - Every 2-3 hits with THIS specific sword, strikes exactly 1 (visual-only) lightning bolt on the target
 * - Shift + right-click: AoE ability that strikes lightning around the player and chips nearby
 *   entities for a small custom damage amount (NOT the game's real lightning damage), on a cooldown
 * - Uses a PersistentDataContainer tag so it never interferes with other custom swords (e.g. Dash Sword)
 */
public class LightningSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;

    // Tracks hits-since-last-strike per player
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    // Tracks the randomized threshold (2 or 3) per player, re-rolled after each strike
    private final Map<UUID, Integer> hitThresholds = new HashMap<>();
    // Tracks the last time (ms) each player used the shift-right-click ability
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    private final Random random = new Random();

    // --- Tunable numbers, adjust to taste ---
    private static final double EXTRA_MELEE_DAMAGE = 2.0;     // flat bonus on top of normal sword + enchant damage
    private static final double ABILITY_RADIUS = 6.0;         // blocks around the player affected by the ability
    private static final double ABILITY_DAMAGE = 3.0;         // 3.0 = 1.5 hearts of "a bit" of damage per bolt
    private static final long ABILITY_COOLDOWN_MS = 15_000;   // 15 second cooldown on the ability

    public LightningSwordFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.swordKey = new NamespacedKey(plugin, "lightning_sword");
    }

    // ---------- Item creation ----------

    public ItemStack createLightningSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();

        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Lightning Sword");
        meta.setLore(List.of(
                ChatColor.WHITE + "Strikes lightning upon hit.",
                ChatColor.WHITE + "Every few swings, the storm answers."
        ));

        // NOTE: enchantment field names shown are for older Spigot/Paper APIs.
        // On 1.20.5+ (new enchantment registry) use Enchantment.SHARPNESS,
        // Enchantment.LOOTING, Enchantment.UNBREAKING, Enchantment.SWEEPING_EDGE, Enchantment.MENDING instead.
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);      // Sharpness V
        meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true); // Looting III
        meta.addEnchant(Enchantment.DURABILITY, 3, true);      // Unbreaking III
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);   // Sweeping Edge III
        meta.addEnchant(Enchantment.MENDING, 1, true);         // Mending

        // Tag it so the hit listener (and nothing else) recognizes it
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        sword.setItemMeta(meta);
        return sword;
    }

    private boolean isLightningSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    // ---------- Command: /lightningsword ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lightningsword")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        player.getInventory().addItem(createLightningSword());
        player.sendMessage(ChatColor.YELLOW + "You have been given the " + ChatColor.BOLD + "Lightning Sword" + ChatColor.YELLOW + "!");
        return true;
    }

    // ---------- Hit tracking + lightning trigger ----------

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        ItemStack weapon = player.getInventory().getItemInMainHand();

        if (!isLightningSword(weapon)) return; // not this sword -> ignore, no interference with other swords

        // A bit of extra damage on top of the normal sword + enchant damage
        event.setDamage(event.getDamage() + EXTRA_MELEE_DAMAGE);

        UUID uuid = player.getUniqueId();
        int count = hitCounts.getOrDefault(uuid, 0) + 1;
        int threshold = hitThresholds.computeIfAbsent(uuid, k -> rollThreshold());

        if (count >= threshold) {
            LivingEntity target = (LivingEntity) event.getEntity();
            Location loc = target.getLocation();

            // strikeLightningEffect = visual + sound only, does NOT deal extra damage
            loc.getWorld().strikeLightningEffect(loc);

            // reset counter and roll a new random threshold (2 or 3) for next time
            hitCounts.put(uuid, 0);
            hitThresholds.put(uuid, rollThreshold());
        } else {
            hitCounts.put(uuid, count);
        }
    }

    private int rollThreshold() {
        return 2 + random.nextInt(2); // 2 or 3
    }

    // ---------- Shift + right-click ability: AoE lightning, chip damage only ----------

    @EventHandler
    public void onAbilityTrigger(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // Only fire once per click (not once per hand) and only on right-click
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        boolean rightClick = event.getAction().name().contains("RIGHT_CLICK");
        if (!rightClick) return;
        if (!player.isSneaking()) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isLightningSword(weapon)) return; // ignore for every other sword

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = abilityCooldowns.getOrDefault(uuid, 0L);

        if (now - lastUse < ABILITY_COOLDOWN_MS) {
            long secondsLeft = (ABILITY_COOLDOWN_MS - (now - lastUse)) / 1000 + 1;
            player.sendMessage(ChatColor.RED + "Lightning Sword ability on cooldown (" + secondsLeft + "s left).");
            return;
        }

        event.setCancelled(true); // stop this from also triggering block interaction/eating/etc.
        abilityCooldowns.put(uuid, now);

        Collection<Entity> nearby = player.getWorld().getNearbyEntities(
                player.getLocation(), ABILITY_RADIUS, ABILITY_RADIUS, ABILITY_RADIUS);

        int struck = 0;
        for (Entity entity : nearby) {
            if (entity.getUniqueId().equals(uuid)) continue;       // skip the caster
            if (!(entity instanceof LivingEntity)) continue;

            LivingEntity target = (LivingEntity) entity;
            Location loc = target.getLocation();

            // Visual/sound only — this does NOT deal Minecraft's normal lightning damage
            target.getWorld().strikeLightningEffect(loc);

            // Apply our own small "a bit of damage" amount instead, attributed to the player
            target.damage(ABILITY_DAMAGE, player);
            struck++;
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        player.sendMessage(ChatColor.YELLOW + "You call down the storm! " + ChatColor.GRAY
                + "(" + struck + " target" + (struck == 1 ? "" : "s") + " struck)");
    }
}

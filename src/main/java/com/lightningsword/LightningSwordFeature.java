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
 * - Gives a fully enchanted netherite sword via /lightningsword, dealing normal sword damage (no melee bonus)
 * - Passive: every fully-charged hit has a 15% chance to strike 1 (visual-only) lightning bolt on the target.
 *   A hit only "counts" toward that roll if it lands at least MIN_HIT_INTERVAL_MS after the last counted hit,
 *   so spam-clicking can't force extra procs.
 * - Shift + right-click: AoE ability that strikes lightning around the player and deals decent
 *   custom damage to nearby entities (NOT the game's real lightning damage), on a cooldown
 * - Uses a PersistentDataContainer tag so it never interferes with other custom swords (e.g. Dash Sword)
 */
public class LightningSwordFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey swordKey;

    // Tracks the last time (ms) each player's hit counted toward the passive's 15% roll
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();
    // Tracks the last time (ms) each player used the shift-right-click ability
    private final Map<UUID, Long> abilityCooldowns = new HashMap<>();

    private final Random random = new Random();

    // --- Tunable numbers, adjust to taste ---
    private static final double PASSIVE_PROC_CHANCE = 0.15;   // 15% chance per counted hit to strike lightning
    private static final long MIN_HIT_INTERVAL_MS = 600;      // hits faster than this (spam-click) don't count toward the roll
    private static final double ABILITY_RADIUS = 6.0;         // blocks around the player affected by the ability
    private static final double ABILITY_DAMAGE = 16.0;        // decent damage per target hit by the storm ability
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
                ChatColor.GRAY + "Deals normal sword damage.",
                ChatColor.GRAY + "Passive: 15% chance per solid hit to",
                ChatColor.GRAY + "strike the target with lightning.",
                ChatColor.GRAY + "Shift + right-click: call down a storm",
                ChatColor.GRAY + "that deals decent damage nearby."
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

    // ---------- Passive: 15% chance on a "fully loaded" hit ----------

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        ItemStack weapon = player.getInventory().getItemInMainHand();

        if (!isLightningSword(weapon)) return; // not this sword -> ignore, no interference with other swords

        // Base damage is left untouched here - sword hits for its normal (enchant-boosted) amount, nothing extra.

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastCountedHit.getOrDefault(uuid, 0L);

        // Hits faster than MIN_HIT_INTERVAL_MS (spam-clicking) don't count toward the roll at all
        if (now - last < MIN_HIT_INTERVAL_MS) return;

        lastCountedHit.put(uuid, now);

        if (random.nextDouble() < PASSIVE_PROC_CHANCE) {
            LivingEntity target = (LivingEntity) event.getEntity();
            Location loc = target.getLocation();

            // strikeLightningEffect = visual + sound only, does NOT deal extra damage on top of the normal hit
            loc.getWorld().strikeLightningEffect(loc);
        }
    }

    // ---------- Shift + right-click ability: AoE lightning, decent damage ----------

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

            // Apply our own decent custom damage amount instead, attributed to the player
            target.damage(ABILITY_DAMAGE, player);
            struck++;
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        player.sendMessage(ChatColor.YELLOW + "You call down the storm! " + ChatColor.GRAY
                + "(" + struck + " target" + (struck == 1 ? "" : "s") + " struck)");
    }
}

package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import me.usainsrht.basicdeathchest.util.LocationUtil;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts {@link PlayerDeathEvent} to:
 * <ol>
 *   <li>Validate world whitelist, keepInventory, and permission.</li>
 *   <li>Clear drops and schedule chest creation on the region thread.</li>
 *   <li>Save a {@link DeathEntry} to the database (async).</li>
 *   <li>Handle custom death messages.</li>
 * </ol>
 *
 * <p>In Folia, {@link PlayerDeathEvent} is fired on the region thread that
 * owns the player's location. Block operations in the handler are therefore
 * safe for the same region; the chest placement is still delegated to the
 * {@link me.usainsrht.basicdeathchest.chest.DeathChestManager} which may
 * schedule follow-up work via {@link FoliaUtil#runOnRegion}.
 */
public class PlayerDeathListener implements Listener {

    private final BasicDeathChest plugin;

    public PlayerDeathListener(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        org.bukkit.World world = player.getWorld();

        // ── Guard: keepInventory ──────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        GameRule<Boolean> keepInventoryRule = (GameRule<Boolean>) org.bukkit.Registry.GAME_RULE.get(org.bukkit.NamespacedKey.minecraft("keep_inventory"));
        if (keepInventoryRule != null) {
            Boolean keepInv = world.getGameRuleValue(keepInventoryRule);
            if (Boolean.TRUE.equals(keepInv)) return;
        }

        // ── Guard: world allowed check ────────────────────────────────────────
        boolean worldAllowed = plugin.getConfigManager().isWorldAllowed(world.getName());
        boolean bypassWorldFilter = plugin.getConfigManager().isDatabaseBypassWorldFilter();

        if (!worldAllowed && !bypassWorldFilter) {
            return;
        }

        // ── Guard: permission ─────────────────────────────────────────────────
        if (plugin.getConfigManager().isRequirePermission()
                && !player.hasPermission(plugin.getConfigManager().getRequiredPermission())) {
            player.sendMessage(plugin.getMessagesManager().chestPermissionRequired());
            return;
        }

        // ── Capture drops ─────────────────────────────────────────────────────
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        if (drops.isEmpty()) return;

        // ── Handle death message mode ─────────────────────────────────────────
        handleDeathMessage(event, player);

        // ── Persist death entry async ─────────────────────────────────────────
        Location deathLoc = player.getLocation().clone();
        String cause = extractCause(event);
        DeathEntry entry = new DeathEntry(
                player.getUniqueId(), player.getName(),
                System.currentTimeMillis(), cause, deathLoc);
        FoliaUtil.runAsync(plugin, () -> plugin.getDatabaseManager().saveEntry(entry));

        // ── Create death chest on the region thread if world is allowed ───────
        if (worldAllowed) {
            // Clear vanilla drops — chest will contain them
            event.getDrops().clear();

            final List<ItemStack> finalDrops = drops;
            FoliaUtil.runOnRegion(plugin, deathLoc, () -> {
                if (!player.isConnected()) {
                    // Player disconnected before chest could be placed — drop items
                    for (ItemStack item : finalDrops) {
                        if (item != null && !item.getType().isAir()) {
                            deathLoc.getWorld().dropItemNaturally(deathLoc, item);
                        }
                    }
                    return;
                }
                plugin.getDeathChestManager().createDeathChest(player, finalDrops);
            });
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void handleDeathMessage(PlayerDeathEvent event, Player player) {
        String mode = plugin.getConfigManager().getDeathMessageMode();
        switch (mode) {
            case "DISABLED" -> event.deathMessage(null);
            case "CUSTOM" -> {
                String format = plugin.getConfigManager().getDeathMessageCustomFormat();
                if (format.isBlank()) {
                    event.deathMessage(null);
                    return;
                }
                Location loc = player.getLocation();
                String cause = extractCause(event);
                String translatedCause = plugin.getMessagesManager().getTranslatedCause(cause);
                var msg = plugin.getMessagesManager().parse(format,
                        "player", player.getName(),
                        "cause", translatedCause,
                        "x", LocationUtil.x(loc),
                        "y", LocationUtil.y(loc),
                        "z", LocationUtil.z(loc),
                        "world", LocationUtil.worldName(loc));
                event.deathMessage(msg);
            }
            default -> { /* VANILLA — do nothing, let Paper handle it */ }
        }
    }

    private String extractCause(PlayerDeathEvent event) {
        if (event.getEntity().getLastDamageCause() == null) return "UNKNOWN";
        return event.getEntity().getLastDamageCause().getCause().name();
    }
}

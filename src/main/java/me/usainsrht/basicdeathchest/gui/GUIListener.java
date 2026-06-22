package me.usainsrht.basicdeathchest.gui;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Handles all inventory interactions for the {@link DeathLocationsGUI}.
 *
 * <p>Prevents item theft, routes clicks to the teleport system, and
 * propagates the player interaction back to a region-safe context.
 */
public class GUIListener implements Listener {

    private final BasicDeathChest plugin;

    public GUIListener(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DeathLocationsGUI gui)) return;
        // Always cancel any item movement
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.getUniqueId().equals(gui.getViewerUUID())) return;

        int slot = event.getRawSlot();
        // Only handle clicks in the top inventory (the GUI itself)
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        DeathEntry entry = gui.getEntryAt(slot);
        if (entry == null) return; // Clicked filler

        if (!plugin.getConfigManager().isTeleportEnabled()) {
            player.sendMessage(plugin.getMessagesManager().teleportDisabled());
            plugin.getTeleportManager().playFailureSound(player);
            return;
        }

        if (!player.hasPermission("basicdeathchest.teleport")) {
            player.sendMessage(plugin.getMessagesManager().noPermission());
            plugin.getTeleportManager().playFailureSound(player);
            return;
        }

        // Close the inventory first, then teleport (Folia: close is fine on the player's region thread)
        player.closeInventory();
        plugin.getTeleportManager().teleport(player, entry);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DeathLocationsGUI) {
            event.setCancelled(true);
        }
    }

    // ─── Static helper ─────────────────────────────────────────────────────────

    /**
     * Opens the Death Locations GUI for {@code player}.
     *
     * <p>Loads entries asynchronously, then opens the inventory on the player's
     * entity scheduler (Folia-safe).
     *
     * @param plugin the plugin instance
     * @param player the player to show the GUI to
     */
    public static void openFor(BasicDeathChest plugin, Player player) {
        int limit = plugin.getConfigManager().getGuiMaxEntries();

        FoliaUtil.runAsync(plugin, () ->
                plugin.getDatabaseManager().getEntries(player.getUniqueId(), limit, entries -> {
                    DeathLocationsGUI gui = new DeathLocationsGUI(plugin, player.getUniqueId(), entries);
                    gui.buildInventory(entries);
                    // Open on the player's entity (region) thread
                    FoliaUtil.runOnEntity(plugin, player, () ->
                            player.openInventory(gui.getInventory()), null);
                }));
    }
}

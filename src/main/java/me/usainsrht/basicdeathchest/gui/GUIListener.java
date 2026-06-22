package me.usainsrht.basicdeathchest.gui;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

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
        int size = event.getInventory().getSize();
        if (slot < 0 || slot >= size) return;

        // Check pagination controls if admin view
        if (gui.isAdminView() && size >= 18) {
            int bottomRowStart = size - 9;
            int prevSlot = bottomRowStart + 2;
            int nextSlot = bottomRowStart + 6;

            if (slot == prevSlot && gui.getPage() > 0) {
                gui.setPage(gui.getPage() - 1);
                gui.reopenPage(player);
                return;
            } else if (slot == nextSlot && gui.getPage() < gui.getTotalPages() - 1) {
                gui.setPage(gui.getPage() + 1);
                gui.reopenPage(player);
                return;
            }
        }

        DeathEntry entry = gui.getEntryAt(slot);
        if (entry == null) return; // Clicked filler or page indicator/empty button

        if (gui.isAdminView()) {
            // Admin GUI bypasses all costs/checks
            player.closeInventory();
            plugin.getTeleportManager().teleport(player, entry, true);
            return;
        }

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
        plugin.getTeleportManager().teleport(player, entry, false);
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

    /**
     * Opens the paginated admin GUI for {@code admin} to view {@code targetName}'s deaths.
     */
    public static void openAdminGuiFor(BasicDeathChest plugin, Player admin, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            openAdminGuiForUUID(plugin, admin, target.getUniqueId(), target.getName());
            return;
        }

        FoliaUtil.runAsync(plugin, () -> {
            plugin.getDatabaseManager().getPlayerUUIDByName(targetName, uuid -> {
                if (uuid != null) {
                    openAdminGuiForUUID(plugin, admin, uuid, targetName);
                } else {
                    org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
                    if (offline != null && offline.getUniqueId() != null && offline.getName() != null) {
                        openAdminGuiForUUID(plugin, admin, offline.getUniqueId(), offline.getName());
                    } else {
                        FoliaUtil.runOnEntity(plugin, admin, () ->
                                admin.sendMessage(MiniMessageUtil.parse("<red>Player '" + targetName + "' was not found.")), null);
                    }
                }
            });
        });
    }

    private static void openAdminGuiForUUID(BasicDeathChest plugin, Player admin, UUID targetUUID, String targetName) {
        FoliaUtil.runAsync(plugin, () -> {
            plugin.getDatabaseManager().getAllEntries(targetUUID, entries -> {
                FoliaUtil.runOnEntity(plugin, admin, () -> {
                    DeathLocationsGUI gui = new DeathLocationsGUI(plugin, admin.getUniqueId(), targetUUID, targetName, entries, true);
                    gui.buildInventory(entries);
                    admin.openInventory(gui.getInventory());
                }, null);
            });
        });
    }
}

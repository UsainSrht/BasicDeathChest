package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.api.events.DeathChestOpenEvent;
import me.usainsrht.basicdeathchest.chest.DeathChest;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

/**
 * Protects death chest blocks from:
 * <ul>
 *   <li>Being broken by non-owners (or enforces configured rules).</li>
 *   <li>Being destroyed by explosions.</li>
 *   <li>Being opened by other players (fires {@link DeathChestOpenEvent}).</li>
 * </ul>
 *
 * <p>Also handles auto-removal when a chest is emptied by its owner.
 */
public class ChestProtectionListener implements Listener {

    private final BasicDeathChest plugin;

    public ChestProtectionListener(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    // ─── Block break ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String ownerUUID = getDeathChestOwner(block);
        if (ownerUUID == null) return; // Not a death chest

        Player player = event.getPlayer();
        boolean isOwner = player.getUniqueId().toString().equals(ownerUUID);
        boolean isAdmin = player.hasPermission("basicdeathchest.admin");

        if (!isOwner && !isAdmin) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessagesManager().chestNotOwner());
            return;
        }

        // Owner/admin breaking their chest — always cancel the vanilla break
        event.setCancelled(true);
        event.setDropItems(false);

        DeathChest chest = plugin.getDeathChestManager().getDeathChestAt(block.getLocation());
        if (chest != null) {
            // Remove the entire chest (drops items naturally)
            plugin.getDeathChestManager().expireChest(chest, false);
        } else {
            // Chest is in the registry but no model — just clear and remove the block
            if (block.getState() instanceof Container container) {
                container.getInventory().getContents();
                for (ItemStack item : container.getInventory().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        block.getWorld().dropItemNaturally(
                                block.getLocation().add(0.5, 0.5, 0.5), item);
                    }
                }
                container.getInventory().clear();
            }
            block.setType(Material.AIR);
        }

        // Optionally drop the chest block itself
        if (plugin.getConfigManager().isDropOnBreak() && (isOwner || isAdmin)) {
            block.getWorld().dropItemNaturally(
                    block.getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(block.getType()));
        }
    }

    // ─── Interaction (open) ───────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        String ownerUUID = getDeathChestOwner(block);
        if (ownerUUID == null) return; // Not a death chest

        Player player = event.getPlayer();
        boolean isOwner = player.getUniqueId().toString().equals(ownerUUID);
        boolean canOpenOthers = player.hasPermission("basicdeathchest.open-others")
                || plugin.getConfigManager().isOpenByEveryone();

        DeathChest chest = plugin.getDeathChestManager().getDeathChestAt(block.getLocation());

        // Fire the open event
        if (chest != null) {
            DeathChestOpenEvent openEvent = new DeathChestOpenEvent(chest, player);
            org.bukkit.Bukkit.getPluginManager().callEvent(openEvent);
            if (openEvent.isCancelled()) {
                event.setCancelled(true);
                return;
            }
        }

        if (!isOwner && !canOpenOthers) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessagesManager().chestNotOwner());
            return;
        }

        // Bypass vanilla obstruction check (e.g. solid block above chest)
        if (block.getState() instanceof Container container) {
            event.setCancelled(true);

            Inventory inventory = container.getInventory();
            if (container instanceof org.bukkit.block.Chest chestBlock) {
                if (inventory.getHolder() instanceof org.bukkit.block.DoubleChest doubleChest) {
                    inventory = doubleChest.getInventory();
                }
            }

            Inventory finalInventory = inventory;
            me.usainsrht.basicdeathchest.util.FoliaUtil.runOnEntity(plugin, player, () -> {
                player.openInventory(finalInventory);
            }, null);
        }
    }

    // ─── Auto-remove when empty ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        Block block = null;
        if (inv.getHolder() instanceof org.bukkit.block.Chest chestHolder) {
            block = chestHolder.getBlock();
        } else if (inv.getHolder() instanceof org.bukkit.block.DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof org.bukkit.block.Chest leftChest) {
                block = leftChest.getBlock();
            }
        }
        if (block == null) return;

        String ownerUUID = getDeathChestOwner(block);
        if (ownerUUID == null) return;

        // Check if inventory is empty
        boolean empty = true;
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) {
                empty = false;
                break;
            }
        }

        if (empty) {
            DeathChest chest = plugin.getDeathChestManager().getDeathChestAt(block.getLocation());
            if (chest != null) {
                plugin.getDeathChestManager().expireChest(chest, false);
                if (event.getPlayer() instanceof Player player) {
                    player.sendMessage(plugin.getMessagesManager().chestEmptied());
                }
            }
        }
    }

    // ─── Explosion protection ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> getDeathChestOwner(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> getDeathChestOwner(block) != null);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * Returns the owner UUID string stored in the block's PDC,
     * or {@code null} if the block is not a tagged death chest.
     */
    private String getDeathChestOwner(Block block) {
        if (block == null) return null;
        if (!(block.getState() instanceof PersistentDataHolder holder)) return null;
        return holder.getPersistentDataContainer()
                .get(plugin.getDeathChestKey(), PersistentDataType.STRING);
    }
}

package me.usainsrht.basicdeathchest.chest;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the physical placement strategy for death chest blocks.
 *
 * <h3>Placement algorithm</h3>
 * <ol>
 *   <li>Place the primary container at the death location.</li>
 *   <li>If items overflow, try to form a double-chest (for CHEST/TRAPPED_CHEST)
 *       by checking NORTH → SOUTH → WEST → EAST for an air block.</li>
 *   <li>If no horizontal air is found, check UP.</li>
 *   <li>If all adjacent positions are occupied, check {@link #canBreak(Block, Player)}.
 *       If true, break the obstructing block and place there.</li>
 *   <li>Any items that still don't fit are dropped naturally.</li>
 * </ol>
 *
 * <p>All methods must be called on the region thread that owns the target location.
 */
public class ChestPlacementHelper {

    /** Faces searched (in order) for a secondary chest placement. */
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    private final BasicDeathChest plugin;

    public ChestPlacementHelper(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Places one or more container blocks starting at {@code origin} and fills them
     * with {@code items}. Returns the resulting {@link DeathChest} model.
     *
     * <p>Items that cannot fit in any container are dropped naturally.
     *
     * @param player the player who died (used for permission checks)
     * @param origin the block at which to place the first container
     * @param items  the items to store
     * @return the populated {@link DeathChest} model
     */
    public DeathChest place(Player player, Block origin, List<ItemStack> items) {
        Material type = plugin.getConfigManager().getContainerType();
        String title = resolveTitle(player);
        int timerDuration = plugin.getConfigManager().getTimerDuration();

        DeathChest chest = new DeathChest(
                player.getUniqueId(), player.getName(),
                origin.getLocation(), timerDuration);

        // Place the primary block and tag it
        placeContainer(origin, type, title, player.getUniqueId().toString());

        // Fill primary container
        Inventory primaryInv = getInventory(origin);
        List<ItemStack> overflow = fillInventory(primaryInv, items);

        if (!overflow.isEmpty()) {
            // Try to place a second container for overflow
            Block secondary = findSecondaryBlock(origin, player, type);
            if (secondary != null) {
                placeContainer(secondary, type, title, player.getUniqueId().toString());
                chest.addLocation(secondary.getLocation());

                Inventory secondaryInv = getInventory(secondary);
                List<ItemStack> stillOverflow = fillInventory(secondaryInv, overflow);

                if (!stillOverflow.isEmpty()) {
                    dropItems(origin, stillOverflow);
                    notifyNoSpace(player);
                }
            } else {
                // No space for a second container — drop overflow
                dropItems(origin, overflow);
                notifyNoSpace(player);
            }
        }

        return chest;
    }

    // ─── Hook (intentionally empty — for external API integration) ────────────

    /**
     * Determines whether the plugin is allowed to break {@code block} on behalf
     * of {@code player} in order to make room for a death chest container.
     *
     * <p><strong>Implementation note:</strong> This method is intentionally left
     * as a stub returning {@code false} (safe default — never break blocks).
     * External plugins can override this behaviour by listening to
     * {@link me.usainsrht.basicdeathchest.api.events.DeathChestCreateEvent} or by
     * providing a custom {@link ChestPlacementHelper} subclass registered via
     * the API.
     *
     * @param block  the block that is obstructing a potential container placement
     * @param player the player on whose behalf the break would occur
     * @return {@code true} if the block may be broken; {@code false} otherwise
     */
    public boolean canBreak(Block block, Player player) {
        // Stub — always deny by default
        return false;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void placeContainer(Block block, Material type, String resolvedTitle, String ownerUUID) {
        block.setType(type, false); // Don't apply physics immediately

        // Access the tile entity state once and mutate it before update()
        org.bukkit.block.BlockState state = block.getState();

        // Tag with PDC so the protection listener can identify it
        if (state instanceof org.bukkit.persistence.PersistentDataHolder holder) {
            holder.getPersistentDataContainer().set(
                    plugin.getDeathChestKey(),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    ownerUUID);
        }

        // Set custom name/title on the container
        if (state instanceof org.bukkit.block.Container container) {
            container.customName(plugin.getMessagesManager().parse(resolvedTitle));
        }

        // Commit both the PDC and custom name in a single update
        state.update(true, false);
    }

    private Inventory getInventory(Block block) {
        if (block.getState() instanceof org.bukkit.block.Container container) {
            return container.getInventory();
        }
        throw new IllegalStateException("Block at " + block.getLocation() + " is not a container!");
    }

    /**
     * Fills {@code inventory} with as many items from {@code items} as possible.
     *
     * @return the items that did NOT fit (may be empty)
     */
    private List<ItemStack> fillInventory(Inventory inventory, List<ItemStack> items) {
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            var result = inventory.addItem(item);
            overflow.addAll(result.values());
        }
        return overflow;
    }

    /**
     * Finds a suitable block for a secondary container.
     * Priority: horizontal faces, then UP.
     */
    private Block findSecondaryBlock(Block origin, Player player, Material type) {
        // Check horizontal adjacents (for double-chest logic)
        if (isChestLike(type)) {
            for (BlockFace face : HORIZONTAL_FACES) {
                Block candidate = origin.getRelative(face);
                if (candidate.getType().isAir()) return candidate;
                if (canBreak(candidate, player)) {
                    candidate.setType(Material.AIR);
                    return candidate;
                }
            }
        }

        // Check UP
        Block above = origin.getRelative(BlockFace.UP);
        if (above.getType().isAir()) return above;
        if (canBreak(above, player)) {
            above.setType(Material.AIR);
            return above;
        }

        return null;
    }

    private boolean isChestLike(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private void dropItems(Block at, List<ItemStack> items) {
        org.bukkit.Location center = at.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                at.getWorld().dropItemNaturally(center, item);
            }
        }
    }

    private void notifyNoSpace(Player player) {
        player.sendMessage(plugin.getMessagesManager().chestSpawnedNoSpace());
    }

    private String resolveTitle(Player player) {
        String template = plugin.getConfigManager().getContainerTitle();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return template
                .replace("%player%", player.getName())
                .replace("%date%", now.toLocalDate().toString())
                .replace("%time%", String.format("%02d:%02d:%02d",
                        now.getHour(), now.getMinute(), now.getSecond()));
    }
}

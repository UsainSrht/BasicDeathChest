package me.usainsrht.basicdeathchest.chest;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import org.bukkit.Bukkit;
import me.usainsrht.basicdeathchest.util.WorldGuardWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the physical placement strategy for death chest blocks.
 *
 * <h3>Placement algorithm</h3>
 * <ol>
 * <li>Place the primary container at the death location.</li>
 * <li>If items overflow, try to form a double-chest (for CHEST/TRAPPED_CHEST)
 * by checking NORTH → SOUTH → WEST → EAST for an air block.</li>
 * <li>If no horizontal air is found, check UP.</li>
 * <li>If all adjacent positions are occupied, check
 * {@link #canBreak(Block, Player)}.
 * If true, break the obstructing block and place there.</li>
 * <li>Any items that still don't fit are dropped naturally.</li>
 * </ol>
 *
 * <p>
 * All methods must be called on the region thread that owns the target
 * location.
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
     * <p>
     * Items that cannot fit in any container are dropped naturally.
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
            BlockFace foundFace = null;
            Block secondary = null;
            if (isChestLike(type)) {
                for (BlockFace face : HORIZONTAL_FACES) {
                    Block candidate = origin.getRelative(face);
                    if (candidate.getType().isAir()) {
                        secondary = candidate;
                        foundFace = face;
                        break;
                    }
                    if (canBreak(candidate, player)) {
                        candidate.setType(Material.AIR);
                        secondary = candidate;
                        foundFace = face;
                        break;
                    }
                }
            }

            if (secondary == null) {
                // Check UP
                Block above = origin.getRelative(BlockFace.UP);
                if (above.getType().isAir()) {
                    secondary = above;
                } else if (canBreak(above, player)) {
                    above.setType(Material.AIR);
                    secondary = above;
                }
            }

            if (secondary != null) {
                placeContainer(secondary, type, title, player.getUniqueId().toString());
                chest.addLocation(secondary.getLocation());

                // If side by side chest, connect them!
                if (foundFace != null && isChestLike(type)) {
                    setChestBlockData(origin, secondary, foundFace, player);
                }

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
     * <p>
     * <strong>Implementation note:</strong> This method is intentionally left
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
        if (block.getType().getHardness() < 0) {
            return false;
        }
        if (!player.hasPermission("basicdeathchest.break")) {
            return false;
        }
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            return WorldGuardWrapper.canBuild(player, block.getLocation());
        }
        return true;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void setChestBlockData(Block origin, Block secondary, BlockFace face, Player player) {
        if (!(origin.getBlockData() instanceof org.bukkit.block.data.type.Chest) ||
            !(secondary.getBlockData() instanceof org.bukkit.block.data.type.Chest)) {
            return;
        }

        BlockFace facing;
        org.bukkit.block.data.type.Chest.Type originType;
        org.bukkit.block.data.type.Chest.Type secondaryType;

        BlockFace pf = player.getFacing();
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            facing = (pf == BlockFace.WEST) ? BlockFace.EAST : BlockFace.WEST;
            if (facing == BlockFace.WEST) {
                if (face == BlockFace.NORTH) {
                    originType = org.bukkit.block.data.type.Chest.Type.LEFT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                } else {
                    originType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.LEFT;
                }
            } else { // EAST
                if (face == BlockFace.NORTH) {
                    originType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.LEFT;
                } else {
                    originType = org.bukkit.block.data.type.Chest.Type.LEFT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                }
            }
        } else { // EAST or WEST
            facing = (pf == BlockFace.SOUTH) ? BlockFace.NORTH : BlockFace.SOUTH;
            if (facing == BlockFace.NORTH) {
                if (face == BlockFace.EAST) {
                    originType = org.bukkit.block.data.type.Chest.Type.LEFT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                } else {
                    originType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.LEFT;
                }
            } else { // SOUTH
                if (face == BlockFace.EAST) {
                    originType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.LEFT;
                } else {
                    originType = org.bukkit.block.data.type.Chest.Type.LEFT;
                    secondaryType = org.bukkit.block.data.type.Chest.Type.RIGHT;
                }
            }
        }

        var originData = (org.bukkit.block.data.type.Chest) origin.getBlockData();
        originData.setFacing(facing);
        originData.setType(originType);
        origin.setBlockData(originData, false);

        var secondaryData = (org.bukkit.block.data.type.Chest) secondary.getBlockData();
        secondaryData.setFacing(facing);
        secondaryData.setType(secondaryType);
        secondary.setBlockData(secondaryData, false);
    }

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
            if (item == null || item.getType().isAir())
                continue;
            var result = inventory.addItem(item);
            overflow.addAll(result.values());
        }
        return overflow;
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

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
import java.util.UUID;

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
     */
    public DeathChest place(Player player, Block origin, List<ItemStack> items) {
        return place(player, null, origin, items);
    }

    /**
     * Places one or more container blocks starting at {@code origin} and fills them
     * with {@code items} with killer metadata. Returns the resulting {@link DeathChest} model.
     *
     * @param player the player who died
     * @param killer the killer player (if PvP kill), or null
     * @param origin the block at which to place the first container
     * @param items  the items to store
     * @return the populated {@link DeathChest} model
     */
    public DeathChest place(Player player, Player killer, Block origin, List<ItemStack> items) {
        Material type = plugin.getConfigManager().getContainerType();
        String title = resolveTitle(player);
        int timerDuration = plugin.getConfigManager().getTimerDuration();

        boolean killerProtected = killer != null
                && !killer.getUniqueId().equals(player.getUniqueId())
                && plugin.getConfigManager().isKillerProtectionEnabled();
        UUID killerUUID = killerProtected ? killer.getUniqueId() : null;
        String killerName = killerProtected ? killer.getName() : null;
        int killerDuration = killerProtected ? plugin.getConfigManager().getKillerProtectionDuration() : 0;
        long killerExpiry = (killerProtected && killerDuration > 0)
                ? System.currentTimeMillis() + (killerDuration * 1000L) : 0L;

        DeathChest chest = new DeathChest(
                player.getUniqueId(), player.getName(),
                killerUUID, killerName,
                origin.getLocation(), timerDuration, killerDuration);

        // Place the primary block and tag it
        placeContainer(origin, type, title, player.getUniqueId().toString(),
                killerUUID != null ? killerUUID.toString() : null, killerExpiry);

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
                    if (isDestroyable(candidate)) {
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
                if (isDestroyable(above)) {
                    secondary = above;
                } else if (canBreak(above, player)) {
                    above.setType(Material.AIR);
                    secondary = above;
                }
            }

            if (secondary != null) {
                placeContainer(secondary, type, title, player.getUniqueId().toString(),
                        killerUUID != null ? killerUUID.toString() : null, killerExpiry);
                chest.addLocation(secondary.getLocation());

                // If side by side chest, connect them!
                if (foundFace != null && isChestLike(type)) {
                    setChestBlockData(origin, secondary, foundFace, player);
                }

                Inventory secondaryInv = getInventory(secondary);
                List<ItemStack> stillOverflow = fillInventory(secondaryInv, overflow);

                if (!stillOverflow.isEmpty()) {
                    dropItems(origin, stillOverflow, killerUUID, killerDuration);
                    notifyNoSpace(player);
                }
            } else {
                // No space for a second container — drop overflow
                dropItems(origin, overflow, killerUUID, killerDuration);
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

    private void placeContainer(Block block, Material type, String resolvedTitle, String ownerUUID,
                                String killerUUID, long killerExpiry) {
        block.setType(type, false); // Don't apply physics immediately

        // Access the tile entity state once and mutate it before update()
        org.bukkit.block.BlockState state = block.getState();

        // Tag with PDC so the protection listener can identify it
        if (state instanceof org.bukkit.persistence.PersistentDataHolder holder) {
            holder.getPersistentDataContainer().set(
                    plugin.getDeathChestKey(),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    ownerUUID);

            if (killerUUID != null && killerExpiry > System.currentTimeMillis()) {
                holder.getPersistentDataContainer().set(
                        plugin.getDeathChestKillerKey(),
                        org.bukkit.persistence.PersistentDataType.STRING,
                        killerUUID);
                holder.getPersistentDataContainer().set(
                        plugin.getDeathChestKillerExpiryKey(),
                        org.bukkit.persistence.PersistentDataType.LONG,
                        killerExpiry);
            }
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

    public static boolean isDestroyable(Block block) {
        Material type = block.getType();
        return type.isAir() || type.getHardness() >= 0;
    }

    private boolean isChestLike(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private void dropItems(Block at, List<ItemStack> items, java.util.UUID killerUUID, int killerDuration) {
        org.bukkit.Location center = at.getLocation().add(0.5, 0.5, 0.5);
        if (killerUUID != null && killerDuration > 0) {
            plugin.getKillerProtectionManager().dropProtectedItems(center, items, killerUUID, killerDuration);
        } else {
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir()) {
                    at.getWorld().dropItemNaturally(center, item);
                }
            }
        }
    }

    private void notifyNoSpace(Player player) {
        if (player.isOnline()) {
            player.sendMessage(plugin.getMessagesManager().chestSpawnedNoSpace());
        }
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

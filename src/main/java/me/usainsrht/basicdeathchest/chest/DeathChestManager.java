package me.usainsrht.basicdeathchest.chest;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.api.events.DeathChestCreateEvent;
import me.usainsrht.basicdeathchest.api.events.DeathChestExpireEvent;
import me.usainsrht.basicdeathchest.api.interfaces.IDeathChest;
import me.usainsrht.basicdeathchest.api.interfaces.IDeathChestManager;
import me.usainsrht.basicdeathchest.hologram.DeathChestHologram;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import me.usainsrht.basicdeathchest.util.LocationUtil;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all active {@link DeathChest} instances.
 *
 * <h3>Thread-safety</h3>
 * <ul>
 *   <li>{@link #activeChests} is a {@link ConcurrentHashMap} keyed by serialized location
 *       string — safe for cross-region reads.</li>
 *   <li>All block mutations (chest creation, expiry) are executed on the region thread
 *       that owns the chest's location via {@link FoliaUtil}.</li>
 * </ul>
 *
 * <p>Implements {@link IDeathChestManager} so external plugins can interact via the API.
 */
public class DeathChestManager implements IDeathChestManager {

    private final BasicDeathChest plugin;
    private final ChestPlacementHelper placementHelper;

    /**
     * Key: serialized block location string → active chest.
     * A chest occupying multiple blocks will have multiple keys pointing to the same instance.
     */
    private final ConcurrentHashMap<String, DeathChest> activeChests = new ConcurrentHashMap<>();

    public DeathChestManager(BasicDeathChest plugin) {
        this.plugin = plugin;
        this.placementHelper = new ChestPlacementHelper(plugin);
    }

    // ─── Creation ─────────────────────────────────────────────────────────────

    /**
     * Creates a death chest for {@code player} at their current location,
     * containing {@code drops}.
     *
     * <p>Must be called on the region thread for the player's location.
     *
     * @param player the player who died
     * @param drops  the items to store (list is NOT cleared — caller is responsible)
     */
    public void createDeathChest(Player player, List<ItemStack> drops) {
        if (drops.isEmpty()) return;

        // Fire pre-create event (cancellable)
        DeathChestCreateEvent event = new DeathChestCreateEvent(player, new ArrayList<>(drops));
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            // Drop items naturally at death location
            Location loc = player.getLocation();
            for (ItemStack item : drops) {
                if (item != null && !item.getType().isAir()) {
                    loc.getWorld().dropItemNaturally(loc, item);
                }
            }
            return;
        }

        List<ItemStack> chestItems = event.getDrops();
        Block origin = player.getLocation().getBlock();

        // Find a non-solid block to place the chest (search downward if needed)
        origin = findSuitableBlock(origin, player);

        DeathChest chest = placementHelper.place(player, origin, chestItems);
        registerChest(chest);

        // Spawn hologram
        DeathChestHologram hologram = plugin.getHologramManager().createFor(
                chest, chest.getPrimaryLocation());
        chest.setHologram(hologram);

        // Start expire timer
        startExpiryTimer(chest);

        // Send coordinates message
        Location deathLoc = chest.getPrimaryLocation();
        if (plugin.getConfigManager().isCoordinatesEnabled()) {
            player.sendMessage(plugin.getMessagesManager().coordinatesMessage(
                    LocationUtil.x(deathLoc),
                    LocationUtil.y(deathLoc),
                    LocationUtil.z(deathLoc),
                    LocationUtil.worldName(deathLoc)));
        }

        // Notify player
        player.sendMessage(plugin.getMessagesManager().chestSpawned(
                LocationUtil.x(deathLoc),
                LocationUtil.y(deathLoc),
                LocationUtil.z(deathLoc)));
    }

    // ─── IDeathChestManager ───────────────────────────────────────────────────

    @Override
    public Optional<IDeathChest> getChestAt(Location location) {
        return Optional.ofNullable(activeChests.get(LocationUtil.serialize(location)));
    }

    @Override
    public List<IDeathChest> getChestsOwnedBy(UUID playerUUID) {
        return activeChests.values().stream()
                .filter(c -> c.getOwnerUUID().equals(playerUUID))
                .distinct()
                .map(c -> (IDeathChest) c)
                .toList();
    }

    @Override
    public List<IDeathChest> getAllActiveChests() {
        return activeChests.values().stream().distinct()
                .map(c -> (IDeathChest) c).toList();
    }

    @Override
    public void removeChest(IDeathChest chest) {
        if (chest instanceof DeathChest dc) {
            expireChest(dc, false);
        }
    }

    // ─── Direct lookup (internal) ─────────────────────────────────────────────

    /**
     * Looks up a {@link DeathChest} by exact block location.
     *
     * @return the chest or {@code null} if not found
     */
    public DeathChest getDeathChestAt(Location location) {
        return activeChests.get(LocationUtil.serialize(location));
    }

    // ─── Expiry ───────────────────────────────────────────────────────────────

    /**
     * Starts the per-second expiry countdown for {@code chest} on its region thread.
     */
    private void startExpiryTimer(DeathChest chest) {
        if (plugin.getConfigManager().isInfiniteTimer()) return; // No timer

        Location loc = chest.getPrimaryLocation();
        var task = FoliaUtil.runRepeatingOnRegion(plugin, loc, scheduledTask -> {
            if (chest.isExpired()) {
                scheduledTask.cancel();
                return;
            }
            int remaining = chest.decrementTimer();
            if (remaining <= 0) {
                scheduledTask.cancel();
                expireChest(chest, true);
            }
        }, 20L, 20L); // every second (20 ticks)

        chest.setTimerTask(task);
    }

    /**
     * Expires a chest: fires the expire event, drops items, plays effects, removes block.
     *
     * @param chest   the chest to expire
     * @param natural {@code true} = normal expiry; {@code false} = forced removal (no event)
     */
    public void expireChest(DeathChest chest, boolean natural) {
        if (chest.isExpired()) return;
        chest.markExpired();

        if (natural) {
            DeathChestExpireEvent expireEvent = new DeathChestExpireEvent(chest);
            Bukkit.getPluginManager().callEvent(expireEvent);
            if (expireEvent.isCancelled()) {
                chest.markExpired(); // reset — need a better model but sufficient for now
                return;
            }
        }

        // Unregister all location keys
        for (Location loc : chest.getAllLocations()) {
            activeChests.remove(LocationUtil.serialize(loc));
        }

        // Remove hologram
        plugin.getHologramManager().remove(chest.getHologram());

        // Drop items and remove blocks
        for (Location loc : chest.getAllLocations()) {
            Block block = loc.getBlock();
            if (block.getState() instanceof Container container) {
                Inventory inv = container.getInventory();
                Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);
                for (ItemStack item : inv.getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        block.getWorld().dropItemNaturally(dropLoc, item);
                    }
                }
                inv.clear();
            }
            block.setType(Material.AIR);

            // Play expiry effects at each chest block
            if (natural) playExpiryEffects(loc);
        }

        // Notify owner if online
        if (natural) {
            Player owner = Bukkit.getPlayer(chest.getOwnerUUID());
            if (owner != null && owner.isOnline()) {
                Location primary = chest.getPrimaryLocation();
                FoliaUtil.runOnEntity(plugin, owner, () ->
                        owner.sendMessage(plugin.getMessagesManager().chestExpired(
                                LocationUtil.x(primary),
                                LocationUtil.y(primary),
                                LocationUtil.z(primary))), null);
            }
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Forcibly removes all active chests without dropping items.
     * Called on plugin disable.
     */
    public void disableAll() {
        for (DeathChest chest : new ArrayList<>(activeChests.values().stream().distinct().toList())) {
            chest.markExpired();
            if (chest.getTimerTask() != null) chest.getTimerTask().cancel();
            plugin.getHologramManager().remove(chest.getHologram());
        }
        activeChests.clear();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void registerChest(DeathChest chest) {
        for (Location loc : chest.getAllLocations()) {
            activeChests.put(LocationUtil.serialize(loc), chest);
        }
    }

    private Block findSuitableBlock(Block block, Player player) {
        // If the death location is inside a solid block but the block above is destroyable/replaceable,
        // place the chest on top of it. This handles cases like slabs, stalagmites, path blocks, etc.
        if (block.getType().isSolid() && !ChestPlacementHelper.isDestroyable(block)) {
            Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
            if (ChestPlacementHelper.isDestroyable(above)) {
                return above;
            }
        }

        // If the block itself is solid and not destroyable, check if we can break it or search downward
        int attempts = 0;
        while (block.getType().isSolid() && !ChestPlacementHelper.isDestroyable(block) && attempts < 5) {
            if (placementHelper.canBreak(block, player)) {
                return block;
            }
            block = block.getRelative(org.bukkit.block.BlockFace.DOWN);
            attempts++;
        }
        return block;
    }

    private void playExpiryEffects(Location loc) {
        Location center = loc.clone().add(0.5, 0.5, 0.5);
        // Visual particle
        Particle particle = plugin.getConfigManager().getExpiryParticle();
        loc.getWorld().spawnParticle(particle, center,
                plugin.getConfigManager().getExpiryParticleCount(), 0.5, 0.5, 0.5, 0.1);
        // Sound
        Sound sound = plugin.getConfigManager().getExpirySound();
        loc.getWorld().playSound(sound, center.getX(), center.getY(), center.getZ());
    }
}

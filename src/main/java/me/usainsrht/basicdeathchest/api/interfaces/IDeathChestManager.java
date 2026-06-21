package me.usainsrht.basicdeathchest.api.interfaces;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public manager interface for interacting with the active death chest registry.
 *
 * <p>Obtain the singleton via {@link me.usainsrht.basicdeathchest.api.DeathChestAPI#getManager()}.
 */
public interface IDeathChestManager {

    /**
     * Returns the active death chest whose block occupies the given location,
     * or {@link Optional#empty()} if no death chest is at that position.
     *
     * <p>Must be called on the region thread for {@code location}.
     */
    Optional<IDeathChest> getChestAt(Location location);

    /**
     * Returns all currently active death chests owned by the specified player.
     */
    List<IDeathChest> getChestsOwnedBy(UUID playerUUID);

    /**
     * Returns an unmodifiable snapshot of all currently active death chests.
     */
    List<IDeathChest> getAllActiveChests();

    /**
     * Removes a death chest immediately, dropping all contained items naturally
     * and cleaning up holograms and timers.
     *
     * <p>Must be called on the region thread for the chest's primary location.
     *
     * @param chest the chest to remove
     */
    void removeChest(IDeathChest chest);
}

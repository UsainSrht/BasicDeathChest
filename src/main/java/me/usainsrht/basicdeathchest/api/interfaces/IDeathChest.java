package me.usainsrht.basicdeathchest.api.interfaces;

import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * Represents an active death chest in the world.
 *
 * <p>Instances are obtained from {@link IDeathChestManager}.
 * External plugins should only interact with this interface, not the
 * internal {@code DeathChest} implementation.
 */
public interface IDeathChest {

    /**
     * Returns the UUID of the player who owns this chest.
     */
    UUID getOwnerUUID();

    /**
     * Returns the display name of the owner at the time of death.
     */
    String getOwnerName();

    /**
     * Returns the primary location of the death chest (the block that was
     * placed first, corresponding to the player's death position).
     */
    Location getPrimaryLocation();

    /**
     * Returns all block locations occupied by this death chest (may be more
     * than one if overflow containers were placed).
     */
    List<Location> getAllLocations();

    /**
     * Returns the Unix timestamp (milliseconds) at which this chest expires,
     * or {@code -1} if the chest has an infinite lifetime.
     */
    long getExpiresAt();

    /**
     * Returns the number of seconds remaining until expiry,
     * or {@code -1} if the chest never expires.
     */
    int getRemainingSeconds();

    /**
     * Returns {@code true} if the chest has already expired and is pending removal.
     */
    boolean isExpired();

    /**
     * Forces this chest to expire immediately, dropping all contained items.
     * Safe to call from any Folia region thread that owns the chest's location.
     */
    void forceExpire();
}

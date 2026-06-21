package me.usainsrht.basicdeathchest.hologram;

import me.usainsrht.basicdeathchest.chest.DeathChest;
import org.bukkit.Location;

/**
 * Strategy interface for hologram backend implementations.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Not persist holograms across server restarts (non-persistent entities / API handles).</li>
 *   <li>Be safe to call {@link #createHologram} from the region thread that owns the location.</li>
 *   <li>Clean up all handles in {@link #removeAll()} when the plugin disables.</li>
 * </ul>
 */
public interface HologramProvider {

    /**
     * Returns a unique identifier for this backend (e.g. {@code "VANILLA"}).
     */
    String getName();

    /**
     * Returns {@code true} if the required plugin/API is available on this server.
     * The factory will fall back to the Vanilla implementation if this returns {@code false}.
     */
    boolean isAvailable();

    /**
     * Creates and spawns a hologram for the given death chest at {@code location}.
     *
     * @param chest    the owning death chest model
     * @param location the world position where the hologram should appear
     * @return the newly created (and already spawned) hologram handle
     */
    DeathChestHologram createHologram(DeathChest chest, Location location);

    /**
     * Removes all holograms managed by this provider.
     * Called on plugin disable — must not throw.
     */
    void removeAll();
}

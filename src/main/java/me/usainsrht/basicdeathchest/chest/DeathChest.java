package me.usainsrht.basicdeathchest.chest;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.usainsrht.basicdeathchest.api.interfaces.IDeathChest;
import me.usainsrht.basicdeathchest.hologram.DeathChestHologram;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal model representing an active death chest in the world.
 *
 * <p>Thread-safety notes:
 * <ul>
 *   <li>{@link #remainingSeconds} is an {@link AtomicInteger} for safe cross-thread reads.</li>
 *   <li>The {@link #timerTask} and {@link #hologram} fields are written once after creation
 *       and read from the owning region thread — no additional synchronisation needed.</li>
 *   <li>{@link #expired} is volatile for visibility across Folia region threads.</li>
 * </ul>
 */
public class DeathChest implements IDeathChest {

    private final UUID ownerUUID;
    private final String ownerName;
    private final Location primaryLocation;
    private final List<Location> allLocations;
    private final long expiresAt;           // Unix millis, or -1 for infinite
    private final AtomicInteger remainingSeconds;

    private volatile boolean expired = false;
    private ScheduledTask timerTask;
    private DeathChestHologram hologram;

    /**
     * @param ownerUUID       the UUID of the player who died
     * @param ownerName       display name at time of death
     * @param primaryLocation block location of the first chest placed
     * @param timerDuration   countdown in seconds; ≤ 0 means infinite
     */
    public DeathChest(UUID ownerUUID, String ownerName,
                      Location primaryLocation, int timerDuration) {
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.primaryLocation = primaryLocation.clone();
        this.allLocations = new ArrayList<>();
        this.allLocations.add(primaryLocation.clone());

        if (timerDuration <= 0) {
            this.expiresAt = -1L;
            this.remainingSeconds = new AtomicInteger(-1);
        } else {
            this.expiresAt = System.currentTimeMillis() + (timerDuration * 1000L);
            this.remainingSeconds = new AtomicInteger(timerDuration);
        }
    }

    // ─── IDeathChest implementation ──────────────────────────────────────────

    @Override public UUID getOwnerUUID()           { return ownerUUID; }
    @Override public String getOwnerName()         { return ownerName; }
    @Override public Location getPrimaryLocation() { return primaryLocation.clone(); }

    @Override
    public List<Location> getAllLocations() {
        return Collections.unmodifiableList(allLocations);
    }

    @Override public long getExpiresAt()           { return expiresAt; }

    @Override
    public int getRemainingSeconds() {
        return remainingSeconds.get();
    }

    @Override
    public double getRemainingSecondsDouble() {
        if (expiresAt < 0) return -1.0;
        long diff = expiresAt - System.currentTimeMillis();
        return Math.max(0.0, diff / 1000.0);
    }

    @Override
    public boolean isExpired()                    { return expired; }

    @Override
    public void forceExpire() {
        // Delegates to the manager; see DeathChestManager#expireChest
        expired = true;
        if (timerTask != null) timerTask.cancel();
    }

    // ─── Internal accessors (package-private) ────────────────────────────────

    /**
     * Decrements the countdown by one second.
     *
     * @return remaining seconds after decrement; returns -1 if infinite
     */
    int decrementTimer() {
        if (remainingSeconds.get() < 0) return -1;
        return remainingSeconds.decrementAndGet();
    }

    void addLocation(Location loc) {
        allLocations.add(loc.clone());
    }

    void setTimerTask(ScheduledTask task) {
        this.timerTask = task;
    }

    ScheduledTask getTimerTask() {
        return timerTask;
    }

    void setHologram(DeathChestHologram hologram) {
        this.hologram = hologram;
    }

    DeathChestHologram getHologram() {
        return hologram;
    }

    void markExpired() {
        this.expired = true;
    }

    /**
     * Formats the remaining time as a human-readable string,
     * e.g. "4m 30s" or "∞" for infinite chests.
     */
    public String formatRemainingTime() {
        int secs = remainingSeconds.get();
        if (secs < 0) return "∞ Never";
        if (secs == 0) return "Expired";
        int minutes = secs / 60;
        int seconds = secs % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}

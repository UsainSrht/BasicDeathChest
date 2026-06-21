package me.usainsrht.basicdeathchest.database.model;

import org.bukkit.Location;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Immutable data record representing a single player death entry.
 * All fields are serializable to SQLite / JSON.
 */
public final class DeathEntry {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final UUID playerUUID;
    private final String playerName;
    private final long timestamp;     // Unix millis
    private final String deathCause;  // EntityDamageEvent.DamageCause name
    private final int x;
    private final int y;
    private final int z;
    private final String world;

    /**
     * Constructs a {@code DeathEntry} from a live {@link Location}.
     */
    public DeathEntry(UUID playerUUID, String playerName, long timestamp,
                      String deathCause, Location location) {
        this(playerUUID, playerName, timestamp, deathCause,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                location.getWorld() != null ? location.getWorld().getName() : "unknown");
    }

    /**
     * Constructs a {@code DeathEntry} from raw field values (used when loading from storage).
     */
    public DeathEntry(UUID playerUUID, String playerName, long timestamp,
                      String deathCause, int x, int y, int z, String world) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.timestamp = timestamp;
        this.deathCause = deathCause;
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
    }

    public UUID getPlayerUUID()  { return playerUUID; }
    public String getPlayerName() { return playerName; }
    public long getTimestamp()   { return timestamp; }
    public String getDeathCause() { return deathCause; }
    public int getX()            { return x; }
    public int getY()            { return y; }
    public int getZ()            { return z; }
    public String getWorld()     { return world; }

    /**
     * Returns the timestamp formatted for display (e.g. "2025-06-20 14:30:00").
     */
    public String getFormattedTime() {
        return DISPLAY_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Returns a formatted death-cause string with the first letter capitalised
     * and underscores replaced with spaces.
     */
    public String getFormattedCause() {
        if (deathCause == null || deathCause.isEmpty()) return "Unknown";
        String lowered = deathCause.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lowered.charAt(0)) + lowered.substring(1);
    }

    @Override
    public String toString() {
        return "DeathEntry{player=" + playerName + ", world=" + world
                + ", x=" + x + ", y=" + y + ", z=" + z
                + ", cause=" + deathCause + ", time=" + getFormattedTime() + "}";
    }
}

package me.usainsrht.basicdeathchest.database.model;

import org.bukkit.Location;
import org.bukkit.event.entity.EntityDamageEvent;

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
    private final String killer;      // Player name / mob type, or null
    private final int x;
    private final int y;
    private final int z;
    private final String world;
    private final ChestStatus chestStatus;

    /**
     * Constructs a {@code DeathEntry} from a live {@link Location}.
     */
    public DeathEntry(UUID playerUUID, String playerName, long timestamp,
                      String deathCause, String killer, Location location,
                      ChestStatus chestStatus) {
        this(playerUUID, playerName, timestamp, deathCause, killer,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                location.getWorld() != null ? location.getWorld().getName() : "unknown",
                chestStatus);
    }

    /**
     * Constructs a {@code DeathEntry} from raw field values (used when loading from storage).
     */
    public DeathEntry(UUID playerUUID, String playerName, long timestamp,
                      String deathCause, String killer, int x, int y, int z, String world,
                      ChestStatus chestStatus) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.timestamp = timestamp;
        String[] normalized = normalizeLegacy(deathCause, killer);
        this.deathCause = normalized[0];
        this.killer = normalized[1];
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
        this.chestStatus = chestStatus != null ? chestStatus : ChestStatus.UNKNOWN;
    }

    /**
     * Older builds stored either a DamageCause enum or a killer display name in
     * {@code deathCause}. Normalize those into cause + killer.
     */
    private static String[] normalizeLegacy(String deathCause, String killer) {
        String cause = deathCause == null || deathCause.isEmpty() ? "UNKNOWN" : deathCause;
        String killerName = (killer == null || killer.isBlank()) ? null : killer;

        if (killerName == null && !isDamageCause(cause)) {
            // Previous version stored killer/mob name in death_cause
            return new String[]{"ENTITY_ATTACK", cause};
        }
        return new String[]{cause, killerName};
    }

    private static boolean isDamageCause(String value) {
        try {
            EntityDamageEvent.DamageCause.valueOf(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public UUID getPlayerUUID()  { return playerUUID; }
    public String getPlayerName() { return playerName; }
    public long getTimestamp()   { return timestamp; }
    public String getDeathCause() { return deathCause; }
    public String getKiller()    { return killer; }
    public int getX()            { return x; }
    public int getY()            { return y; }
    public int getZ()            { return z; }
    public String getWorld()     { return world; }
    public ChestStatus getChestStatus() { return chestStatus; }

    /**
     * Returns the timestamp formatted for display (e.g. "2025-06-20 14:30:00").
     */
    public String getFormattedTime() {
        return DISPLAY_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    @Override
    public String toString() {
        return "DeathEntry{player=" + playerName + ", world=" + world
                + ", x=" + x + ", y=" + y + ", z=" + z
                + ", cause=" + deathCause + ", killer=" + killer
                + ", chestStatus=" + chestStatus
                + ", time=" + getFormattedTime() + "}";
    }
}

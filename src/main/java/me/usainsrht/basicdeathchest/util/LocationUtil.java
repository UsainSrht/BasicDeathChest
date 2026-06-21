package me.usainsrht.basicdeathchest.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Utility methods for {@link Location} serialization and display formatting.
 */
public final class LocationUtil {

    private LocationUtil() {}

    /**
     * Serializes a {@link Location} to a compact string.
     * Format: {@code "worldName,x,y,z"}
     */
    public static String serialize(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName() + ","
                + loc.getBlockX() + ","
                + loc.getBlockY() + ","
                + loc.getBlockZ();
    }

    /**
     * Deserializes a location string produced by {@link #serialize(Location)}.
     *
     * @return the {@link Location}, or {@code null} if the world is not loaded or the string is malformed
     */
    public static Location deserialize(String str) {
        if (str == null || str.isBlank()) return null;
        String[] parts = str.split(",", 4);
        if (parts.length < 4) return null;
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Formats block coordinates as a short human-readable string: {@code "x, y, z"}.
     */
    public static String formatShort(Location loc) {
        if (loc == null) return "N/A";
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    /**
     * Returns the block X coordinate as a string.
     */
    public static String x(Location loc) {
        return String.valueOf(loc.getBlockX());
    }

    /**
     * Returns the block Y coordinate as a string.
     */
    public static String y(Location loc) {
        return String.valueOf(loc.getBlockY());
    }

    /**
     * Returns the block Z coordinate as a string.
     */
    public static String z(Location loc) {
        return String.valueOf(loc.getBlockZ());
    }

    /**
     * Returns the world name, or {@code "unknown"} if the world is null.
     */
    public static String worldName(Location loc) {
        return (loc != null && loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
    }
}

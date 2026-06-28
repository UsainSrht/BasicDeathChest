package me.usainsrht.basicdeathchest.util;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Utility wrapper for interacting with WorldGuard API.
 * This class is decoupled into a separate file to prevent ClassNotFoundExceptions
 * on servers where WorldGuard is not installed, as classes referencing WorldGuard types
 * will only load when this wrapper class is loaded/invoked.
 */
public class WorldGuardWrapper {

    /**
     * Checks if a player has permission to build/break at the specified location
     * according to WorldGuard region rules and bypass permission checks.
     *
     * @param player   the player to check
     * @param location the location to query
     * @return true if the player can build/break at the location; false otherwise
     */
    public static boolean canBuild(Player player, Location location) {
        try {
            var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            var wgLocation = BukkitAdapter.adapt(location);
            var wgWorld = BukkitAdapter.adapt(location.getWorld());
            
            // Check for admin/bypass permissions first
            if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, wgWorld)) {
                return true;
            }
            
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            return query.testState(wgLocation, localPlayer, Flags.BUILD);
        } catch (Throwable t) {
            // Safe fallback if there's any API mismatch or issue
            return false;
        }
    }
}

package me.usainsrht.basicdeathchest.api;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.api.interfaces.IDeathChestManager;
import me.usainsrht.basicdeathchest.database.DatabaseManager;

/**
 * Static façade for the BasicDeathChest public API.
 *
 * <p>Third-party plugins should use this class as their entry point:
 * <pre>{@code
 *   if (Bukkit.getPluginManager().isPluginEnabled("BasicDeathChest")) {
 *       IDeathChestManager manager = DeathChestAPI.getManager();
 *       DatabaseManager db = DeathChestAPI.getDatabase();
 *   }
 * }</pre>
 *
 * <p>All methods throw {@link IllegalStateException} if called before
 * BasicDeathChest has fully enabled.
 */
public final class DeathChestAPI {

    private static BasicDeathChest instance;

    private DeathChestAPI() {}

    /**
     * Called internally by {@link BasicDeathChest#onEnable()} to register the instance.
     * Do NOT call this from external plugins.
     */
    public static void setInstance(BasicDeathChest plugin) {
        instance = plugin;
    }

    /**
     * Returns the {@link IDeathChestManager} for querying and manipulating active death chests.
     *
     * @throws IllegalStateException if the plugin is not yet enabled
     */
    public static IDeathChestManager getManager() {
        ensureEnabled();
        return instance.getDeathChestManager();
    }

    /**
     * Returns the active {@link DatabaseManager} for reading/writing death entries.
     *
     * @throws IllegalStateException if the plugin is not yet enabled
     */
    public static DatabaseManager getDatabase() {
        ensureEnabled();
        return instance.getDatabaseManager();
    }

    /**
     * Returns the plugin instance (for version checking, scheduler access, etc.).
     *
     * @throws IllegalStateException if the plugin is not yet enabled
     */
    public static BasicDeathChest getPlugin() {
        ensureEnabled();
        return instance;
    }

    /**
     * Returns {@code true} if the API is currently available (plugin is enabled).
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    private static void ensureEnabled() {
        if (instance == null) {
            throw new IllegalStateException(
                    "BasicDeathChest API is not available — is the plugin enabled?");
        }
    }
}

package me.usainsrht.basicdeathchest.database;

import me.usainsrht.basicdeathchest.database.model.DeathEntry;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Service interface for persisting and retrieving death entries.
 *
 * <p>All mutating methods are expected to be called from an async context
 * (via {@link me.usainsrht.basicdeathchest.util.FoliaUtil#runAsync}).
 * Callbacks are delivered on the same async thread; callers are responsible
 * for dispatching back to the appropriate region thread if Bukkit API is needed.
 */
public interface DatabaseManager {

    /**
     * Initialises the storage backend (creates tables, opens connections, etc.).
     * Called once on plugin enable.
     *
     * @throws Exception if initialisation fails — the plugin should disable itself
     */
    void initialize() throws Exception;

    /**
     * Persists a new death entry. May silently drop the oldest entries if the
     * per-player limit configured in {@code config.yml} is exceeded.
     */
    void saveEntry(DeathEntry entry);

    /**
     * Asynchronously fetches the most recent {@code limit} death entries for a player,
     * then delivers the result to {@code callback}.
     */
    void getEntries(UUID playerUUID, int limit, Consumer<List<DeathEntry>> callback);

    /**
     * Asynchronously fetches <em>all</em> stored death entries for a player.
     */
    void getAllEntries(UUID playerUUID, Consumer<List<DeathEntry>> callback);

    /**
     * Removes a specific death entry identified by player UUID and timestamp.
     */
    void removeEntry(UUID playerUUID, long timestamp);

    /**
     * Asynchronously fetches the number of free uses consumed by a player.
     */
    void getFreeUsesConsumed(UUID playerUUID, Consumer<Integer> callback);

    /**
     * Persists the number of free uses consumed by a player.
     */
    void saveFreeUsesConsumed(UUID playerUUID, int count);

    /**
     * Closes connections and flushes any pending writes.
     * Called on plugin disable.
     */
    void close();
}

package me.usainsrht.basicdeathchest.database;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.ChestStatus;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.ItemStackSerializer;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * SQLite-backed implementation of {@link DatabaseManager}.
 *
 * <p>A single {@link Connection} is maintained and synchronised via
 * {@code synchronized} blocks (SQLite is single-writer anyway).
 * All public methods are intended to be called from an async thread.
 */
public class SQLiteDatabase implements DatabaseManager {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS death_entries (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid  TEXT    NOT NULL,
                player_name  TEXT    NOT NULL,
                timestamp    INTEGER NOT NULL,
                death_cause  TEXT    NOT NULL,
                killer       TEXT,
                world        TEXT    NOT NULL,
                x            INTEGER NOT NULL,
                y            INTEGER NOT NULL,
                z            INTEGER NOT NULL,
                chest_status TEXT
            );
            """;

    private static final String CREATE_FREE_USES_TABLE = """
            CREATE TABLE IF NOT EXISTS player_free_uses (
                player_uuid TEXT PRIMARY KEY,
                free_uses   INTEGER NOT NULL
            );
            """;

    private static final String CREATE_DEATH_ITEMS_TABLE = """
            CREATE TABLE IF NOT EXISTS death_items (
                player_uuid TEXT    NOT NULL,
                timestamp   INTEGER NOT NULL,
                data        BLOB    NOT NULL,
                PRIMARY KEY (player_uuid, timestamp)
            );
            """;

    private static final String INSERT = """
            INSERT INTO death_entries (player_uuid, player_name, timestamp, death_cause, killer, world, x, y, z, chest_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String SELECT_FREE_USES = """
            SELECT free_uses FROM player_free_uses WHERE player_uuid = ?;
            """;

    private static final String REPLACE_FREE_USES = """
            INSERT OR REPLACE INTO player_free_uses (player_uuid, free_uses) VALUES (?, ?);
            """;

    private static final String SELECT_COLUMNS =
            "player_uuid, player_name, timestamp, death_cause, killer, world, x, y, z, chest_status";

    private static final String SELECT_LIMIT = """
            SELECT player_uuid, player_name, timestamp, death_cause, killer, world, x, y, z, chest_status
              FROM death_entries
             WHERE player_uuid = ?
             ORDER BY timestamp DESC
             LIMIT ?;
            """;

    private static final String SELECT_ALL = """
            SELECT player_uuid, player_name, timestamp, death_cause, killer, world, x, y, z, chest_status
              FROM death_entries
             WHERE player_uuid = ?
             ORDER BY timestamp DESC;
            """;

    private static final String DELETE_ENTRY = """
            DELETE FROM death_entries WHERE player_uuid = ? AND timestamp = ?;
            """;

    private static final String PRUNE_OLD = """
            DELETE FROM death_entries
             WHERE player_uuid = ?
               AND id NOT IN (
                     SELECT id FROM death_entries
                      WHERE player_uuid = ?
                      ORDER BY timestamp DESC
                      LIMIT ?
                   );
            """;

    private static final String REPLACE_DEATH_ITEMS = """
            INSERT OR REPLACE INTO death_items (player_uuid, timestamp, data) VALUES (?, ?, ?);
            """;

    private static final String SELECT_DEATH_ITEMS = """
            SELECT data FROM death_items WHERE player_uuid = ? AND timestamp = ?;
            """;

    private static final String DELETE_DEATH_ITEMS = """
            DELETE FROM death_items WHERE player_uuid = ? AND timestamp = ?;
            """;

    private static final String PURGE_OLD_DEATH_ITEMS = """
            DELETE FROM death_items WHERE timestamp < ?;
            """;

    private static final String PRUNE_ORPHAN_DEATH_ITEMS = """
            DELETE FROM death_items
             WHERE player_uuid = ?
               AND timestamp NOT IN (
                     SELECT timestamp FROM death_entries WHERE player_uuid = ?
                   );
            """;

    // ─────────────────────────────────────────────────────────────────────────

    private final BasicDeathChest plugin;
    private Connection connection;

    public SQLiteDatabase(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public synchronized void initialize() throws Exception {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File dbFile = new File(dataFolder, "data.db");
        // Force-load the SQLite driver
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Enable WAL mode for better concurrency
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_FREE_USES_TABLE);
            stmt.execute(CREATE_DEATH_ITEMS_TABLE);
            migrateSchema(stmt);
        }
        purgeAgedDeathItems();
        plugin.getLogger().info("SQLite database initialised at " + dbFile.getPath());
    }

    private void migrateSchema(Statement stmt) throws SQLException {
        boolean hasKiller = false;
        boolean hasChestStatus = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(death_entries);")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if ("killer".equalsIgnoreCase(name)) {
                    hasKiller = true;
                } else if ("chest_status".equalsIgnoreCase(name)) {
                    hasChestStatus = true;
                }
            }
        }
        if (!hasKiller) {
            stmt.execute("ALTER TABLE death_entries ADD COLUMN killer TEXT;");
            plugin.getLogger().info("Migrated death_entries: added killer column.");
        }
        if (!hasChestStatus) {
            stmt.execute("ALTER TABLE death_entries ADD COLUMN chest_status TEXT;");
            plugin.getLogger().info("Migrated death_entries: added chest_status column.");
        }
    }

    @Override
    public synchronized void saveEntry(DeathEntry entry) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            ps.setString(1, entry.getPlayerUUID().toString());
            ps.setString(2, entry.getPlayerName());
            ps.setLong(3, entry.getTimestamp());
            ps.setString(4, entry.getDeathCause());
            ps.setString(5, entry.getKiller());
            ps.setString(6, entry.getWorld());
            ps.setInt(7, entry.getX());
            ps.setInt(8, entry.getY());
            ps.setInt(9, entry.getZ());
            ps.setString(10, entry.getChestStatus().name());
            ps.executeUpdate();

            // Prune entries exceeding per-player limit if maxEntries is positive
            int maxEntries = plugin.getConfigManager().getMaxEntriesPerPlayer();
            if (maxEntries > 0) {
                String uuid = entry.getPlayerUUID().toString();
                try (PreparedStatement prune = connection.prepareStatement(PRUNE_OLD)) {
                    prune.setString(1, uuid);
                    prune.setString(2, uuid);
                    prune.setInt(3, maxEntries);
                    prune.executeUpdate();
                }
                // Drop item snapshots for pruned entries
                try (PreparedStatement orphan = connection.prepareStatement(PRUNE_ORPHAN_DEATH_ITEMS)) {
                    orphan.setString(1, uuid);
                    orphan.setString(2, uuid);
                    orphan.executeUpdate();
                }
            }
            purgeAgedDeathItems();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save death entry", e);
        }
    }

    @Override
    public void getEntries(UUID playerUUID, int limit, Consumer<List<DeathEntry>> callback) {
        List<DeathEntry> entries = new ArrayList<>();
        int maxAgeHours = plugin.getConfigManager().getGuiMaxRecordAgeHours();
        long cutoff = maxAgeHours > 0 ? (System.currentTimeMillis() - maxAgeHours * 3600000L) : 0L;

        synchronized (this) {
            String query = cutoff > 0 ?
                    "SELECT " + SELECT_COLUMNS + " FROM death_entries WHERE player_uuid = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?;" :
                    SELECT_LIMIT;

            try (PreparedStatement ps = connection.prepareStatement(query)) {
                ps.setString(1, playerUUID.toString());
                if (cutoff > 0) {
                    ps.setLong(2, cutoff);
                    ps.setInt(3, limit);
                } else {
                    ps.setInt(2, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(fromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve death entries", e);
            }
        }
        callback.accept(entries);
    }

    @Override
    public void getAllEntries(UUID playerUUID, Consumer<List<DeathEntry>> callback) {
        List<DeathEntry> entries = new ArrayList<>();
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL)) {
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(fromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve all death entries", e);
            }
        }
        callback.accept(entries);
    }

    @Override
    public synchronized void removeEntry(UUID playerUUID, long timestamp) {
        try (PreparedStatement ps = connection.prepareStatement(DELETE_ENTRY)) {
            ps.setString(1, playerUUID.toString());
            ps.setLong(2, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove death entry", e);
        }
        deleteDeathItems(playerUUID, timestamp);
    }

    @Override
    public synchronized void saveDeathItems(UUID playerUUID, long timestamp, ItemStack[] contents) {
        try {
            if (ItemStackSerializer.isEmpty(contents)) return;
            byte[] data = ItemStackSerializer.encode(contents, plugin.getLogger());
            if (data == null) return;
            try (PreparedStatement ps = connection.prepareStatement(REPLACE_DEATH_ITEMS)) {
                ps.setString(1, playerUUID.toString());
                ps.setLong(2, timestamp);
                ps.setBytes(3, data);
                ps.executeUpdate();
            }
            purgeAgedDeathItems();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save death items for " + playerUUID, e);
        }
    }

    @Override
    public void getDeathItems(UUID playerUUID, long timestamp, Consumer<ItemStack[]> callback) {
        ItemStack[] contents = new ItemStack[ItemStackSerializer.PLAYER_INVENTORY_SIZE];
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_DEATH_ITEMS)) {
                ps.setString(1, playerUUID.toString());
                ps.setLong(2, timestamp);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        contents = ItemStackSerializer.decode(rs.getBytes("data"), plugin.getLogger());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load death items for " + playerUUID, e);
                contents = new ItemStack[ItemStackSerializer.PLAYER_INVENTORY_SIZE];
            }
        }
        callback.accept(contents);
    }

    @Override
    public synchronized void deleteDeathItems(UUID playerUUID, long timestamp) {
        try (PreparedStatement ps = connection.prepareStatement(DELETE_DEATH_ITEMS)) {
            ps.setString(1, playerUUID.toString());
            ps.setLong(2, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete death items for " + playerUUID, e);
        }
    }

    @Override
    public synchronized void purgeOldDeathItems(long cutoffMillis) {
        if (cutoffMillis <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement(PURGE_OLD_DEATH_ITEMS)) {
            ps.setLong(1, cutoffMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to purge old death items", e);
        }
    }

    @Override
    public void getFreeUsesConsumed(UUID playerUUID, Consumer<Integer> callback) {
        int count = 0;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_FREE_USES)) {
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getInt("free_uses");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve free uses", e);
            }
        }
        callback.accept(count);
    }

    @Override
    public synchronized void saveFreeUsesConsumed(UUID playerUUID, int count) {
        try (PreparedStatement ps = connection.prepareStatement(REPLACE_FREE_USES)) {
            ps.setString(1, playerUUID.toString());
            ps.setInt(2, count);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save free uses", e);
        }
    }

    @Override
    public void getPlayerUUIDByName(String name, Consumer<UUID> callback) {
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT player_uuid FROM death_entries WHERE LOWER(player_name) = LOWER(?) LIMIT 1;")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        callback.accept(UUID.fromString(rs.getString("player_uuid")));
                        return;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to resolve UUID by name", e);
            }
        }
        callback.accept(null);
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("SQLite connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing SQLite connection", e);
            }
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private DeathEntry fromResultSet(ResultSet rs) throws SQLException {
        return new DeathEntry(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getLong("timestamp"),
                rs.getString("death_cause"),
                rs.getString("killer"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("world"),
                ChestStatus.fromStorage(rs.getString("chest_status"))
        );
    }

    private void purgeAgedDeathItems() {
        int maxAgeHours = plugin.getConfigManager().getGuiMaxRecordAgeHours();
        if (maxAgeHours <= 0) return;
        long cutoff = System.currentTimeMillis() - maxAgeHours * 3600000L;
        purgeOldDeathItems(cutoff);
    }
}

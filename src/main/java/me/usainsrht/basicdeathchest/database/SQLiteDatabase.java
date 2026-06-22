package me.usainsrht.basicdeathchest.database;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;

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
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT    NOT NULL,
                player_name TEXT    NOT NULL,
                timestamp   INTEGER NOT NULL,
                death_cause TEXT    NOT NULL,
                world       TEXT    NOT NULL,
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL
            );
            """;

    private static final String INSERT = """
            INSERT INTO death_entries (player_uuid, player_name, timestamp, death_cause, world, x, y, z)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String SELECT_LIMIT = """
            SELECT player_uuid, player_name, timestamp, death_cause, world, x, y, z
              FROM death_entries
             WHERE player_uuid = ?
             ORDER BY timestamp DESC
             LIMIT ?;
            """;

    private static final String SELECT_ALL = """
            SELECT player_uuid, player_name, timestamp, death_cause, world, x, y, z
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
        }
        plugin.getLogger().info("SQLite database initialised at " + dbFile.getPath());
    }

    @Override
    public synchronized void saveEntry(DeathEntry entry) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            ps.setString(1, entry.getPlayerUUID().toString());
            ps.setString(2, entry.getPlayerName());
            ps.setLong(3, entry.getTimestamp());
            ps.setString(4, entry.getDeathCause());
            ps.setString(5, entry.getWorld());
            ps.setInt(6, entry.getX());
            ps.setInt(7, entry.getY());
            ps.setInt(8, entry.getZ());
            ps.executeUpdate();

            // Prune entries exceeding per-player limit
            int maxEntries = plugin.getConfigManager().getMaxEntriesPerPlayer();
            try (PreparedStatement prune = connection.prepareStatement(PRUNE_OLD)) {
                prune.setString(1, entry.getPlayerUUID().toString());
                prune.setString(2, entry.getPlayerUUID().toString());
                prune.setInt(3, maxEntries);
                prune.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save death entry", e);
        }
    }

    @Override
    public void getEntries(UUID playerUUID, int limit, Consumer<List<DeathEntry>> callback) {
        List<DeathEntry> entries = new ArrayList<>();
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_LIMIT)) {
                ps.setString(1, playerUUID.toString());
                ps.setInt(2, limit);
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
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("world")
        );
    }
}

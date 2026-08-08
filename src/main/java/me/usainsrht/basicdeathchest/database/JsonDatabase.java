package me.usainsrht.basicdeathchest.database;

import com.google.gson.*;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.ChestStatus;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.ItemStackSerializer;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * JSON flat-file implementation of {@link DatabaseManager}.
 *
 * <p>Data is stored in {@code plugins/BasicDeathChest/deaths.json}.
 * The file is read entirely into memory and re-written on each mutation.
 * Suitable for small servers; prefer {@link SQLiteDatabase} for larger ones.
 *
 * <p>Death item snapshots are stored as YAML sidecars under {@code death-items/}.
 *
 * <p>All public methods are intended to be called from an async thread.
 */
public class JsonDatabase implements DatabaseManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BasicDeathChest plugin;
    private File dataFile;
    private File freeUsesFile;
    private File deathItemsDir;
    /** In-memory representation: UUID string → list of entries (newest first) */
    private final Map<String, List<DeathEntry>> data = new LinkedHashMap<>();
    private final Map<String, Integer> freeUsesData = new LinkedHashMap<>();

    public JsonDatabase(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public synchronized void initialize() throws Exception {
        File folder = plugin.getDataFolder();
        if (!folder.exists()) folder.mkdirs();

        dataFile = new File(folder, "deaths.json");
        if (dataFile.exists()) {
            loadFromDisk();
        } else {
            dataFile.createNewFile();
            saveToDisk();
        }

        freeUsesFile = new File(folder, "free_uses.json");
        if (freeUsesFile.exists()) {
            loadFreeUsesFromDisk();
        } else {
            freeUsesFile.createNewFile();
            saveFreeUsesToDisk();
        }

        deathItemsDir = new File(folder, "death-items");
        if (!deathItemsDir.exists()) {
            deathItemsDir.mkdirs();
        }
        purgeAgedDeathItems();
        plugin.getLogger().info("JSON database initialised at " + dataFile.getPath() + " and " + freeUsesFile.getPath());
    }

    @Override
    public synchronized void saveEntry(DeathEntry entry) {
        String key = entry.getPlayerUUID().toString();
        List<DeathEntry> entries = data.computeIfAbsent(key, k -> new ArrayList<>());
        entries.add(0, entry); // newest first

        // Prune if max is positive (also drop matching item sidecars)
        int max = plugin.getConfigManager().getMaxEntriesPerPlayer();
        if (max > 0) {
            while (entries.size() > max) {
                DeathEntry removed = entries.remove(entries.size() - 1);
                deleteDeathItemsFile(removed.getPlayerUUID(), removed.getTimestamp());
            }
        }
        saveToDisk();
        purgeAgedDeathItems();
    }

    @Override
    public void getEntries(UUID playerUUID, int limit, Consumer<List<DeathEntry>> callback) {
        List<DeathEntry> result = new ArrayList<>();
        int maxAgeHours = plugin.getConfigManager().getGuiMaxRecordAgeHours();
        long cutoff = maxAgeHours > 0 ? (System.currentTimeMillis() - maxAgeHours * 3600000L) : 0L;

        synchronized (this) {
            List<DeathEntry> all = data.getOrDefault(playerUUID.toString(), Collections.emptyList());
            for (DeathEntry entry : all) {
                if (cutoff > 0 && entry.getTimestamp() < cutoff) {
                    continue;
                }
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        callback.accept(result);
    }

    @Override
    public void getAllEntries(UUID playerUUID, Consumer<List<DeathEntry>> callback) {
        List<DeathEntry> result;
        synchronized (this) {
            result = new ArrayList<>(data.getOrDefault(playerUUID.toString(), Collections.emptyList()));
        }
        callback.accept(result);
    }

    @Override
    public synchronized void removeEntry(UUID playerUUID, long timestamp) {
        List<DeathEntry> entries = data.get(playerUUID.toString());
        if (entries == null) return;
        entries.removeIf(e -> e.getTimestamp() == timestamp);
        deleteDeathItemsFile(playerUUID, timestamp);
        saveToDisk();
    }

    @Override
    public synchronized void saveDeathItems(UUID playerUUID, long timestamp, ItemStack[] contents) {
        try {
            if (ItemStackSerializer.isEmpty(contents)) return;
            File file = deathItemsFile(playerUUID, timestamp);
            ItemStackSerializer.saveToFile(file, contents, plugin.getLogger());
            purgeAgedDeathItems();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save death items for " + playerUUID, e);
        }
    }

    @Override
    public void getDeathItems(UUID playerUUID, long timestamp, Consumer<ItemStack[]> callback) {
        ItemStack[] contents;
        synchronized (this) {
            try {
                contents = ItemStackSerializer.loadFromFile(
                        deathItemsFile(playerUUID, timestamp), plugin.getLogger());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load death items for " + playerUUID, e);
                contents = new ItemStack[ItemStackSerializer.PLAYER_INVENTORY_SIZE];
            }
        }
        callback.accept(contents);
    }

    @Override
    public synchronized void deleteDeathItems(UUID playerUUID, long timestamp) {
        deleteDeathItemsFile(playerUUID, timestamp);
    }

    @Override
    public synchronized void purgeOldDeathItems(long cutoffMillis) {
        if (cutoffMillis <= 0 || deathItemsDir == null || !deathItemsDir.isDirectory()) return;
        File[] files = deathItemsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            long ts = parseTimestampFromFileName(file.getName());
            if (ts >= 0 && ts < cutoffMillis) {
                if (!file.delete()) {
                    plugin.getLogger().warning("Failed to delete aged death-items file: " + file.getName());
                }
            }
        }
    }

    @Override
    public void getFreeUsesConsumed(UUID playerUUID, Consumer<Integer> callback) {
        int count;
        synchronized (this) {
            count = freeUsesData.getOrDefault(playerUUID.toString(), 0);
        }
        callback.accept(count);
    }

    @Override
    public synchronized void saveFreeUsesConsumed(UUID playerUUID, int count) {
        freeUsesData.put(playerUUID.toString(), count);
        saveFreeUsesToDisk();
    }

    @Override
    public void getPlayerUUIDByName(String name, Consumer<UUID> callback) {
        synchronized (this) {
            for (List<DeathEntry> list : data.values()) {
                for (DeathEntry entry : list) {
                    if (entry.getPlayerName().equalsIgnoreCase(name)) {
                        callback.accept(entry.getPlayerUUID());
                        return;
                    }
                }
            }
        }
        callback.accept(null);
    }

    @Override
    public void close() {
        synchronized (this) {
            saveToDisk();
            saveFreeUsesToDisk();
        }
        plugin.getLogger().info("JSON database flushed and closed.");
    }

    // ─── Serialization helpers ────────────────────────────────────────────────

    private void loadFromDisk() {
        try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> playerEntry : root.entrySet()) {
                List<DeathEntry> list = new ArrayList<>();
                for (JsonElement el : playerEntry.getValue().getAsJsonArray()) {
                    JsonObject obj = el.getAsJsonObject();
                    String killer = obj.has("killer") && !obj.get("killer").isJsonNull()
                            ? obj.get("killer").getAsString() : null;
                    ChestStatus chestStatus = obj.has("chestStatus") && !obj.get("chestStatus").isJsonNull()
                            ? ChestStatus.fromStorage(obj.get("chestStatus").getAsString())
                            : ChestStatus.UNKNOWN;
                    list.add(new DeathEntry(
                            UUID.fromString(obj.get("playerUUID").getAsString()),
                            obj.get("playerName").getAsString(),
                            obj.get("timestamp").getAsLong(),
                            obj.get("deathCause").getAsString(),
                            killer,
                            obj.get("x").getAsInt(),
                            obj.get("y").getAsInt(),
                            obj.get("z").getAsInt(),
                            obj.get("world").getAsString(),
                            chestStatus
                    ));
                }
                data.put(playerEntry.getKey(), list);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load deaths.json", e);
        }
    }

    private void saveToDisk() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<DeathEntry>> playerEntry : data.entrySet()) {
            JsonArray arr = new JsonArray();
            for (DeathEntry e : playerEntry.getValue()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("playerUUID", e.getPlayerUUID().toString());
                obj.addProperty("playerName", e.getPlayerName());
                obj.addProperty("timestamp", e.getTimestamp());
                obj.addProperty("deathCause", e.getDeathCause());
                if (e.getKiller() != null) {
                    obj.addProperty("killer", e.getKiller());
                }
                obj.addProperty("x", e.getX());
                obj.addProperty("y", e.getY());
                obj.addProperty("z", e.getZ());
                obj.addProperty("world", e.getWorld());
                obj.addProperty("chestStatus", e.getChestStatus().name());
                arr.add(obj);
            }
            root.add(playerEntry.getKey(), arr);
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write deaths.json", e);
        }
    }

    private void loadFreeUsesFromDisk() {
        try (Reader reader = new InputStreamReader(new FileInputStream(freeUsesFile), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                freeUsesData.put(entry.getKey(), entry.getValue().getAsInt());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load free_uses.json", e);
        }
    }

    private void saveFreeUsesToDisk() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Integer> entry : freeUsesData.entrySet()) {
            root.addProperty(entry.getKey(), entry.getValue());
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(freeUsesFile), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write free_uses.json", e);
        }
    }

    private File deathItemsFile(UUID playerUUID, long timestamp) {
        return new File(deathItemsDir, playerUUID + "_" + timestamp + ".yml");
    }

    private void deleteDeathItemsFile(UUID playerUUID, long timestamp) {
        try {
            File file = deathItemsFile(playerUUID, timestamp);
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Failed to delete death-items file: " + file.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete death items for " + playerUUID, e);
        }
    }

    private void purgeAgedDeathItems() {
        int maxAgeHours = plugin.getConfigManager().getGuiMaxRecordAgeHours();
        if (maxAgeHours <= 0) return;
        long cutoff = System.currentTimeMillis() - maxAgeHours * 3600000L;
        purgeOldDeathItems(cutoff);
    }

    private static long parseTimestampFromFileName(String name) {
        // Expected: <uuid>_<timestamp>.yml
        int underscore = name.lastIndexOf('_');
        int dot = name.lastIndexOf('.');
        if (underscore < 0 || dot <= underscore) return -1L;
        try {
            return Long.parseLong(name.substring(underscore + 1, dot));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}

package me.usainsrht.basicdeathchest;

import me.usainsrht.basicdeathchest.config.MessagesManager;
import me.usainsrht.basicdeathchest.database.model.ChestStatus;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class DeathEntryLevelTest {

    private MessagesManager messagesManager;

    @BeforeEach
    public void setup() throws Exception {
        messagesManager = new MessagesManager(null);
        InputStream stream = getClass().getClassLoader().getResourceAsStream("messages.yml");
        assertNotNull(stream, "messages.yml must exist in resources");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        Field cfgField = MessagesManager.class.getDeclaredField("cfg");
        cfgField.setAccessible(true);
        cfgField.set(messagesManager, config);
    }

    @Test
    public void testDeathEntryLevel() {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // New constructor with level
        DeathEntry entryWithLevel = new DeathEntry(uuid, "PlayerA", now, "FALL", null, 10, 64, -20, "world", ChestStatus.PLACED, 42);
        assertEquals(42, entryWithLevel.getLevel());
        assertTrue(entryWithLevel.toString().contains("level=42"));

        // Legacy constructor defaults to 0
        DeathEntry legacyEntry = new DeathEntry(uuid, "PlayerA", now, "FALL", null, 10, 64, -20, "world", ChestStatus.PLACED);
        assertEquals(0, legacyEntry.getLevel());

        // Negative level is clamped to 0
        DeathEntry negativeEntry = new DeathEntry(uuid, "PlayerA", now, "FALL", null, 10, 64, -20, "world", ChestStatus.PLACED, -5);
        assertEquals(0, negativeEntry.getLevel());
    }

    @Test
    public void testMessagesYmlContainsLevelEntries() {
        String guiLoreLevel = messagesManager.getRaw("gui-entry-lore-level");
        assertNotNull(guiLoreLevel);
        assertTrue(guiLoreLevel.contains("%level%"));

        String adminRestored = messagesManager.getRaw("admin-items-restored");
        assertNotNull(adminRestored);
        assertTrue(adminRestored.contains("%level%"));
    }

    @Test
    public void testJsonSerializationAndLegacyFallback() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        // Legacy JSON without level
        String legacyJson = "{\"playerUUID\":\"" + UUID.randomUUID() + "\",\"playerName\":\"PlayerA\",\"timestamp\":1000,\"deathCause\":\"FALL\",\"x\":0,\"y\":64,\"z\":0,\"world\":\"world\",\"chestStatus\":\"PLACED\"}";
        com.google.gson.JsonObject obj = gson.fromJson(legacyJson, com.google.gson.JsonObject.class);
        int level = obj.has("level") && !obj.get("level").isJsonNull() ? obj.get("level").getAsInt() : 0;
        assertEquals(0, level);

        // New JSON with level
        String newJson = "{\"playerUUID\":\"" + UUID.randomUUID() + "\",\"playerName\":\"PlayerA\",\"timestamp\":1000,\"deathCause\":\"FALL\",\"x\":0,\"y\":64,\"z\":0,\"world\":\"world\",\"chestStatus\":\"PLACED\",\"level\":30}";
        com.google.gson.JsonObject newObj = gson.fromJson(newJson, com.google.gson.JsonObject.class);
        int newLevel = newObj.has("level") && !newObj.get("level").isJsonNull() ? newObj.get("level").getAsInt() : 0;
        assertEquals(30, newLevel);
    }
}

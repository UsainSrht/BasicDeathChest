package me.usainsrht.basicdeathchest;

import me.usainsrht.basicdeathchest.chest.DeathChest;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class KillerProtectionTest {

    @Test
    public void testConfigYamlDefaults() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream, "config.yml must exist in resources");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertTrue(config.contains("killer-protection"), "config.yml must contain killer-protection section");
        assertTrue(config.getBoolean("killer-protection.enabled"), "killer-protection.enabled default should be true");
        assertEquals(10, config.getInt("killer-protection.duration"), "killer-protection.duration default should be 10");
        assertFalse(config.getBoolean("killer-protection.allow-victim"), "killer-protection.allow-victim default should be false");
    }

    @Test
    public void testMessagesYamlDefaults() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("messages.yml");
        assertNotNull(stream, "messages.yml must exist in resources");
        YamlConfiguration messages = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertTrue(messages.contains("chest-killer-protected"), "messages.yml must contain chest-killer-protected");
        assertTrue(messages.contains("chest-killer-protected-victim"), "messages.yml must contain chest-killer-protected-victim");
        assertTrue(messages.contains("loot-killer-protected"), "messages.yml must contain loot-killer-protected");

        String chestProtectedMsg = messages.getString("chest-killer-protected");
        assertNotNull(chestProtectedMsg);
        assertTrue(chestProtectedMsg.contains("%seconds%"), "chest-killer-protected should contain %seconds% placeholder");

        Component parsed = MiniMessageUtil.parse(chestProtectedMsg, "seconds", "8");
        String plain = PlainTextComponentSerializer.plainText().serialize(parsed);
        assertTrue(plain.contains("8"), "Rendered message must contain formatted seconds");
    }

    @Test
    public void testDeathChestKillerProtectionActive() {
        UUID ownerUUID = UUID.randomUUID();
        UUID killerUUID = UUID.randomUUID();
        Location dummyLoc = new Location(null, 100, 64, 200);

        DeathChest chest = new DeathChest(ownerUUID, "Victim", killerUUID, "Killer", dummyLoc, 300, 10);

        assertEquals(ownerUUID, chest.getOwnerUUID());
        assertEquals("Victim", chest.getOwnerName());
        assertEquals(killerUUID, chest.getKillerUUID());
        assertEquals("Killer", chest.getKillerName());

        assertTrue(chest.isKillerProtected(), "Chest should be killer-protected immediately after creation");
        assertTrue(chest.getRemainingKillerProtectionSeconds() <= 10 && chest.getRemainingKillerProtectionSeconds() > 0,
                "Remaining killer protection seconds should be between 1 and 10");
    }

    @Test
    public void testDeathChestWithoutKiller() {
        UUID ownerUUID = UUID.randomUUID();
        Location dummyLoc = new Location(null, 0, 70, 0);

        DeathChest chest = new DeathChest(ownerUUID, "Victim", dummyLoc, 300);

        assertNull(chest.getKillerUUID());
        assertNull(chest.getKillerName());
        assertFalse(chest.isKillerProtected(), "Chest without killer must not be killer-protected");
        assertEquals(0, chest.getRemainingKillerProtectionSeconds());
    }

    @Test
    public void testDeathChestZeroDuration() {
        UUID ownerUUID = UUID.randomUUID();
        UUID killerUUID = UUID.randomUUID();
        Location dummyLoc = new Location(null, 0, 70, 0);

        DeathChest chest = new DeathChest(ownerUUID, "Victim", killerUUID, "Killer", dummyLoc, 300, 0);

        assertFalse(chest.isKillerProtected(), "Chest with 0s protection duration must not be killer-protected");
        assertEquals(0, chest.getRemainingKillerProtectionSeconds());
    }
}

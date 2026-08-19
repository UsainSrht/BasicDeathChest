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

public class DeathCauseFormattingTest {

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
    public void testSoloEnvironmentalDeath() {
        // Fall damage without entity
        assertEquals("Fall Damage", messagesManager.formatDeathCause("FALL", null));
        assertEquals("Fall Damage", messagesManager.formatDeathCause("FALL", ""));
        assertEquals("Fall Damage", messagesManager.formatDeathCause("FALL", "   "));

        // Lava without entity
        assertEquals("Lava", messagesManager.formatDeathCause("LAVA", null));

        // Drowning without entity
        assertEquals("Drowning", messagesManager.formatDeathCause("DROWNING", null));

        // Void without entity
        assertEquals("Void", messagesManager.formatDeathCause("VOID", null));
    }

    @Test
    public void testDirectEntityKill() {
        // Direct melee attack by player
        assertEquals("PlayerB", messagesManager.formatDeathCause("ENTITY_ATTACK", "PlayerB"));

        // Direct melee attack by mob
        assertEquals("Zombie", messagesManager.formatDeathCause("ENTITY_ATTACK", "Zombie"));

        // Direct projectile attack by mob
        assertEquals("Skeleton", messagesManager.formatDeathCause("PROJECTILE", "Skeleton"));

        // Direct sweep attack
        assertEquals("PlayerB", messagesManager.formatDeathCause("ENTITY_SWEEP_ATTACK", "PlayerB"));

        // Direct Sonic Boom from Warden
        assertEquals("Warden", messagesManager.formatDeathCause("SONIC_BOOM", "Warden"));

        // Direct Creeper explosion
        assertEquals("Creeper", messagesManager.formatDeathCause("ENTITY_EXPLOSION", "Creeper"));
    }

    @Test
    public void testIndirectEntityKill() {
        // Fall damage while running from/fighting PlayerB
        assertEquals("Fall Damage / PlayerB", messagesManager.formatDeathCause("FALL", "PlayerB"));

        // Lava while fighting PlayerB
        assertEquals("Lava / PlayerB", messagesManager.formatDeathCause("LAVA", "PlayerB"));

        // Drowning while fighting Drowned
        assertEquals("Drowning / Drowned", messagesManager.formatDeathCause("DROWNING", "Drowned"));

        // Void while fighting PlayerB
        assertEquals("Void / PlayerB", messagesManager.formatDeathCause("VOID", "PlayerB"));

        // Fire tick while fighting Blaze
        assertEquals("Burning / Blaze", messagesManager.formatDeathCause("FIRE_TICK", "Blaze"));

        // Suffocation while fighting PlayerB
        assertEquals("Suffocation / PlayerB", messagesManager.formatDeathCause("SUFFOCATION", "PlayerB"));
    }

    @Test
    public void testDeathEntryLegacyNormalization() {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // Standard new entry
        DeathEntry entry1 = new DeathEntry(uuid, "PlayerA", now, "FALL", "PlayerB", 0, 64, 0, "world", ChestStatus.PLACED);
        assertEquals("FALL", entry1.getDeathCause());
        assertEquals("PlayerB", entry1.getKiller());

        // Legacy entry where deathCause was "FALL" and killer was null
        DeathEntry entry2 = new DeathEntry(uuid, "PlayerA", now, "FALL", null, 0, 64, 0, "world", ChestStatus.PLACED);
        assertEquals("FALL", entry2.getDeathCause());
        assertNull(entry2.getKiller());

        // Legacy entry where deathCause was mob name "Zombie" and killer was null
        DeathEntry entry3 = new DeathEntry(uuid, "PlayerA", now, "Zombie", null, 0, 64, 0, "world", ChestStatus.PLACED);
        assertEquals("ENTITY_ATTACK", entry3.getDeathCause());
        assertEquals("Zombie", entry3.getKiller());
    }
}

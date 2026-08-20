package me.usainsrht.basicdeathchest;

import me.usainsrht.basicdeathchest.config.ConfigManager.WorldFilter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WorldFilterTest {

    @Test
    public void testWhitelistLogic() {
        WorldFilter filter = new WorldFilter(true, List.of("world", "world_nether", "world_the_end"));
        assertTrue(filter.isWhitelist());
        assertTrue(filter.isAllowed("world"));
        assertTrue(filter.isAllowed("world_nether"));
        assertTrue(filter.isAllowed("world_the_end"));
        assertFalse(filter.isAllowed("spawn"));
        assertFalse(filter.isAllowed("mining_world"));
        assertFalse(filter.isAllowed(null));
    }

    @Test
    public void testBlacklistLogic() {
        WorldFilter filter = new WorldFilter(false, List.of("spawn", "lobby", "minigames"));
        assertFalse(filter.isWhitelist());
        assertFalse(filter.isAllowed("spawn"));
        assertFalse(filter.isAllowed("lobby"));
        assertFalse(filter.isAllowed("minigames"));
        assertTrue(filter.isAllowed("world"));
        assertTrue(filter.isAllowed("world_nether"));
        assertFalse(filter.isAllowed(null));
    }

    @Test
    public void testConfigYamlDefaults() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream, "config.yml must exist in resources");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertTrue(config.contains("chest-worlds"));
        assertTrue(config.getBoolean("chest-worlds.whitelist"));
        assertEquals(List.of("world", "world_nether", "world_the_end"), config.getStringList("chest-worlds.list"));

        assertTrue(config.contains("entry-worlds"));
        assertTrue(config.getBoolean("entry-worlds.whitelist"));
        assertEquals(List.of("world", "world_nether", "world_the_end"), config.getStringList("entry-worlds.list"));

        assertTrue(config.contains("teleport-worlds"));
        assertTrue(config.getBoolean("teleport-worlds.whitelist"));
        assertEquals(List.of("world", "world_nether", "world_the_end"), config.getStringList("teleport-worlds.list"));
    }
}

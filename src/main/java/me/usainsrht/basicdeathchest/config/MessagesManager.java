package me.usainsrht.basicdeathchest.config;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Loads and caches all player-facing message strings from {@code messages.yml}.
 *
 * <p>Messages are stored as raw MiniMessage strings; use {@link #parse(String, String...)}
 * (or the convenience getters that return {@link Component}) to get the rendered form.
 */
public class MessagesManager {

    private final BasicDeathChest plugin;
    private FileConfiguration cfg;
    private String prefix;

    public MessagesManager(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves the default {@code messages.yml} (if absent) and reloads all values from disk.
     */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);

        // Merge any new keys from the bundled default
        InputStream defStream = plugin.getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            cfg.setDefaults(defaults);
        }

        prefix = cfg.getString("prefix", "<dark_gray>[<gold>DeathChest</gold>]</dark_gray> ");
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the raw MiniMessage string for the given key, or a fallback.
     */
    public String getRaw(String key) {
        String value = cfg.getString(key);
        if (value == null) {
            plugin.getLogger().log(Level.WARNING, "Missing message key: " + key);
            return "<red>[Missing message: " + key + "]";
        }
        return value;
    }

    /**
     * Parses the message for {@code key} with optional placeholder pairs
     * and prefixes it with the configured plugin prefix.
     */
    public Component get(String key, String... pairs) {
        String raw = prefix + getRaw(key);
        return MiniMessageUtil.parse(raw, pairs);
    }

    /**
     * Parses the message for {@code key} WITHOUT the prefix.
     */
    public Component getRawComponent(String key, String... pairs) {
        return MiniMessageUtil.parse(getRaw(key), pairs);
    }

    /**
     * Parses an arbitrary MiniMessage string (not a key) with optional pairs.
     * Useful for config-defined message templates (e.g. hologram lines).
     */
    public Component parse(String miniMessage, String... pairs) {
        return MiniMessageUtil.parse(miniMessage, pairs);
    }

    // ─── Convenience getters ──────────────────────────────────────────────────

    public Component noPermission()                   { return get("no-permission"); }
    public Component playerOnly()                     { return get("player-only"); }
    public Component unknownCommand()                 { return get("unknown-command"); }

    public Component chestSpawned(String x, String y, String z) {
        return get("chest-spawned", "x", x, "y", y, "z", z);
    }
    public Component chestSpawnedNoSpace()            { return get("chest-spawned-no-space"); }
    public Component chestPermissionRequired()        { return get("chest-permission-required"); }
    public Component chestNotOwner()                  { return get("chest-not-owner"); }
    public Component chestEmptied()                   { return get("chest-emptied"); }
    public Component chestExpired(String x, String y, String z) {
        return get("chest-expired", "x", x, "y", y, "z", z);
    }

    public Component guiNoEntries()                   { return get("gui-no-entries"); }
    public Component guiEntryName(String index)       { return getRawComponent("gui-entry-name", "index", index); }

    public Component teleportDisabled()               { return get("teleport-disabled"); }
    public Component teleportSuccess()                { return get("teleport-success"); }
    public Component teleportCost(String cost)        { return get("teleport-cost", "cost", cost); }
    public Component teleportInsufficientFunds(String cost, String balance) {
        return get("teleport-insufficient-funds", "cost", cost, "balance", balance);
    }
    public Component teleportFreeRemaining(String remaining) {
        return get("teleport-free-remaining", "remaining", remaining);
    }
    public Component teleportWorldNotLoaded()         { return get("teleport-world-not-loaded"); }

    public Component bodyguardsSpawned()              { return get("bodyguards-spawned"); }
    public Component bodyguardsDespawned()            { return get("bodyguards-despawned"); }

    public Component reloadSuccess()                  { return get("reload-success"); }
    public Component reloadFail()                     { return get("reload-fail"); }

    public Component coordinatesMessage(String x, String y, String z, String world) {
        String msg = plugin.getConfigManager().getCoordinatesMessage();
        return MiniMessageUtil.parse(msg, "x", x, "y", y, "z", z, "world", world);
    }

    /**
     * Formats the timer using the translation strings defined in messages.yml.
     */
    public String formatTimer(int secs) {
        if (secs < 0) {
            return getRaw("timer-format-infinite");
        }
        int minutes = secs / 60;
        int seconds = secs % 60;
        if (minutes > 0) {
            String format = getRaw("timer-format-minutes");
            return format.replace("%m%", String.valueOf(minutes))
                         .replace("%s%", String.valueOf(seconds));
        } else {
            String format = getRaw("timer-format-seconds");
            return format.replace("%s%", String.valueOf(seconds));
        }
    }

    /**
     * Formats the timer using the translation strings defined in messages.yml,
     * supporting decimal formatting if update interval is not a multiple of 20 ticks.
     */
    public String formatTimer(double secs, int updateIntervalTicks) {
        if (secs < 0) {
            return getRaw("timer-format-infinite");
        }
        int minutes = (int) (secs / 60);
        double seconds = secs % 60;

        String secondsStr;
        if (minutes > 0 || updateIntervalTicks % 20 == 0) {
            secondsStr = String.valueOf((int) Math.ceil(seconds));
        } else {
            secondsStr = String.format(java.util.Locale.ROOT, "%.1f", seconds);
        }

        if (minutes > 0) {
            String format = getRaw("timer-format-minutes");
            return format.replace("%m%", String.valueOf(minutes))
                         .replace("%s%", secondsStr);
        } else {
            String format = getRaw("timer-format-seconds");
            return format.replace("%s%", secondsStr);
        }
    }
}

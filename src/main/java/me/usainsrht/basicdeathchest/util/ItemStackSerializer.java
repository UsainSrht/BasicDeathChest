package me.usainsrht.basicdeathchest.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fail-safe encode/decode for player inventory snapshots (slot → ItemStack).
 *
 * <p>Corrupt or unknown slots are skipped; whole-payload failures return an empty array.
 */
public final class ItemStackSerializer {

    /** Standard player inventory size: storage + armor + offhand. */
    public static final int PLAYER_INVENTORY_SIZE = 41;

    private ItemStackSerializer() {}

    /**
     * Deep-clones non-empty slots from {@code source} into a fixed-size array.
     */
    public static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] result = new ItemStack[PLAYER_INVENTORY_SIZE];
        if (source == null) return result;
        int len = Math.min(source.length, PLAYER_INVENTORY_SIZE);
        for (int i = 0; i < len; i++) {
            ItemStack item = source[i];
            if (item != null && !item.getType().isAir()) {
                result[i] = item.clone();
            }
        }
        return result;
    }

    public static boolean isEmpty(ItemStack[] contents) {
        if (contents == null) return true;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Encodes inventory contents to YAML bytes. Returns {@code null} on failure.
     */
    public static byte[] encode(ItemStack[] contents, Logger logger) {
        try {
            YamlConfiguration yaml = toYaml(contents);
            return yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to encode death items", e);
            }
            return null;
        }
    }

    /**
     * Decodes YAML bytes to a fixed-size inventory array. Never throws; returns empty on failure.
     */
    public static ItemStack[] decode(byte[] data, Logger logger) {
        if (data == null || data.length == 0) {
            return new ItemStack[PLAYER_INVENTORY_SIZE];
        }
        try {
            String text = new String(data, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return fromYaml(yaml, logger);
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to decode death items", e);
            }
            return new ItemStack[PLAYER_INVENTORY_SIZE];
        }
    }

    public static boolean saveToFile(File file, ItemStack[] contents, Logger logger) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                if (logger != null) {
                    logger.warning("Failed to create death-items directory: " + parent.getPath());
                }
                return false;
            }
            toYaml(contents).save(file);
            return true;
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to save death items to " + file.getPath(), e);
            }
            return false;
        }
    }

    public static ItemStack[] loadFromFile(File file, Logger logger) {
        if (file == null || !file.exists()) {
            return new ItemStack[PLAYER_INVENTORY_SIZE];
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            return fromYaml(yaml, logger);
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to load death items from " + file.getPath(), e);
            }
            return new ItemStack[PLAYER_INVENTORY_SIZE];
        }
    }

    private static YamlConfiguration toYaml(ItemStack[] contents) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (contents == null) return yaml;
        int len = Math.min(contents.length, PLAYER_INVENTORY_SIZE);
        for (int i = 0; i < len; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir()) {
                yaml.set("slots." + i, item);
            }
        }
        return yaml;
    }

    private static ItemStack[] fromYaml(YamlConfiguration yaml, Logger logger) {
        ItemStack[] result = new ItemStack[PLAYER_INVENTORY_SIZE];
        ConfigurationSection section = yaml.getConfigurationSection("slots");
        if (section == null) return result;

        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                if (slot < 0 || slot >= PLAYER_INVENTORY_SIZE) continue;
                ItemStack item = section.getItemStack(key);
                if (item != null && !item.getType().isAir()) {
                    result[slot] = item;
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Level.WARNING, "Skipping corrupt death-item slot '" + key + "'", e);
                }
            }
        }
        return result;
    }
}

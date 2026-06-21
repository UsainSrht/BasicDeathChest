package me.usainsrht.basicdeathchest.hologram;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.chest.DeathChest;
import me.usainsrht.basicdeathchest.hologram.impl.DecentHologramsImpl;
import me.usainsrht.basicdeathchest.hologram.impl.FancyHologramsImpl;
import me.usainsrht.basicdeathchest.hologram.impl.VanillaHologramImpl;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.Location;

import java.util.List;

/**
 * Factory and lifecycle manager for hologram backends.
 *
 * <p>Selects the backend configured in {@code hologram.backend} and falls
 * back to {@link VanillaHologramImpl} if the requested plugin is unavailable.
 *
 * <p>All creation/removal calls are dispatched on the appropriate region thread
 * by the callers; this class itself does not schedule anything.
 */
public class HologramManager {

    private final BasicDeathChest plugin;
    private HologramProvider provider;

    public HologramManager(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    /** Initialises the backend. Must be called after {@link me.usainsrht.basicdeathchest.config.ConfigManager#reload()}. */
    public void initialize() {
        String backend = plugin.getConfigManager().getHologramBackend();
        provider = selectProvider(backend);
        plugin.getLogger().info("Hologram backend: " + provider.getName());
    }

    /**
     * Creates a hologram above the given location and starts its update timer.
     *
     * <p>Must be called on the region thread for {@code location}.
     *
     * @param chest    the owning death chest
     * @param location the base location; Y offset from config is applied here
     * @return the live {@link DeathChestHologram} handle
     */
    public DeathChestHologram createFor(DeathChest chest, Location location) {
        double yOff = plugin.getConfigManager().getHologramYOffset();
        Location holoLoc = location.clone().add(0.5, yOff + 1.0, 0.5);

        DeathChestHologram hologram = provider.createHologram(chest, holoLoc);

        // Start the update task
        int intervalTicks = plugin.getConfigManager().getHologramUpdateIntervalTicks();
        var updateTask = FoliaUtil.runRepeatingOnRegion(plugin, holoLoc, task -> {
            if (chest.isExpired()) {
                task.cancel();
                return;
            }
            List<String> resolvedLines = resolveLines(chest);
            hologram.update(resolvedLines);
        }, intervalTicks, intervalTicks);

        hologram.setUpdateTask(updateTask);
        return hologram;
    }

    /**
     * Removes the hologram and cancels its update task.
     */
    public void remove(DeathChestHologram hologram) {
        if (hologram == null) return;
        hologram.cancelUpdateTask();
        hologram.remove();
    }

    /** Removes all active holograms (called on plugin disable). */
    public void removeAll() {
        if (provider != null) provider.removeAll();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private HologramProvider selectProvider(String name) {
        String upperName = name != null ? name.toUpperCase() : "FANCY_HOLOGRAMS";
        if (upperName.equals("DECENT_HOLOGRAMS")) {
            DecentHologramsImpl dh = new DecentHologramsImpl(plugin);
            if (dh.isAvailable()) return dh;
            plugin.getLogger().warning("DecentHolograms not found — falling back to VANILLA.");
            return new VanillaHologramImpl(plugin);
        }

        // Default is FANCY_HOLOGRAMS
        FancyHologramsImpl fh = new FancyHologramsImpl(plugin);
        if (fh.isAvailable()) return fh;

        if (!upperName.equals("VANILLA")) {
            plugin.getLogger().warning("FancyHolograms not found — falling back to VANILLA.");
        }
        return new VanillaHologramImpl(plugin);
    }

    /**
     * Resolves %player% and %timer% placeholders in each hologram line template.
     */
    private List<String> resolveLines(DeathChest chest) {
        List<String> templates = plugin.getConfigManager().getHologramLines();
        String playerName = chest.getOwnerName();
        String timer = plugin.getMessagesManager().formatTimer(chest.getRemainingSeconds());
        return templates.stream()
                .map(line -> line
                        .replace("%player%", playerName)
                        .replace("%timer%", timer))
                .toList();
    }
}

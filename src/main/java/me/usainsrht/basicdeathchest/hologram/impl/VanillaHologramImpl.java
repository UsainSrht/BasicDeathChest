package me.usainsrht.basicdeathchest.hologram.impl;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.chest.DeathChest;
import me.usainsrht.basicdeathchest.hologram.DeathChestHologram;
import me.usainsrht.basicdeathchest.hologram.HologramProvider;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vanilla TextDisplay entity-based hologram backend.
 *
 * <p>Each hologram line is a separate {@link TextDisplay} entity stacked
 * vertically. All entities are non-persistent (they won't survive a restart).
 *
 * <p>Spawn and removal must occur on the region thread that owns the location.
 */
public class VanillaHologramImpl implements HologramProvider {

    /** Line spacing between stacked TextDisplay entities (blocks). */
    private static final double LINE_SPACING = 0.28;

    private final BasicDeathChest plugin;
    /** Tracks all live holograms for bulk cleanup on disable. */
    private final CopyOnWriteArrayList<VanillaHologram> activeHolograms = new CopyOnWriteArrayList<>();

    public VanillaHologramImpl(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override public String getName()        { return "VANILLA"; }
    @Override public boolean isAvailable()   { return true; }

    @Override
    public DeathChestHologram createHologram(DeathChest chest, Location location) {
        VanillaHologram hologram = new VanillaHologram(chest, location);
        hologram.spawn();
        activeHolograms.add(hologram);
        return hologram;
    }

    @Override
    public void removeAll() {
        for (VanillaHologram h : activeHolograms) {
            h.remove();
        }
        activeHolograms.clear();
    }

    // ─── Inner hologram implementation ───────────────────────────────────────

    private class VanillaHologram extends DeathChestHologram {

        private final Location baseLocation;
        private final List<TextDisplay> displays = new ArrayList<>();

        VanillaHologram(DeathChest chest, Location baseLocation) {
            super(chest);
            this.baseLocation = baseLocation.clone();
        }

        @Override
        public void spawn() {
            List<String> lines = plugin.getConfigManager().getHologramLines();
            spawnDisplays(lines);
        }

        @Override
        public void update(List<String> lines) {
            // Remove extra entities
            while (displays.size() > lines.size()) {
                TextDisplay last = displays.remove(displays.size() - 1);
                last.remove();
            }
            // Update or add
            for (int i = 0; i < lines.size(); i++) {
                Component text = MiniMessageUtil.parse(lines.get(i));
                if (i < displays.size()) {
                    displays.get(i).text(text);
                } else {
                    Location lineLoc = baseLocation.clone().add(0, (lines.size() - 1 - i) * LINE_SPACING, 0);
                    TextDisplay td = spawnEntity(lineLoc, text);
                    displays.add(td);
                }
            }
        }

        @Override
        public void remove() {
            cancelUpdateTask();
            for (TextDisplay td : displays) {
                if (td.isValid()) td.remove();
            }
            displays.clear();
            activeHolograms.remove(this);
        }

        // ─── Helpers ─────────────────────────────────────────────────────────

        private void spawnDisplays(List<String> lines) {
            // Render top line first, subsequent lines lower
            for (int i = 0; i < lines.size(); i++) {
                double yOffset = (lines.size() - 1 - i) * LINE_SPACING;
                Location lineLoc = baseLocation.clone().add(0, yOffset, 0);
                Component text = MiniMessageUtil.parse(lines.get(i));
                displays.add(spawnEntity(lineLoc, text));
            }
        }

        private TextDisplay spawnEntity(Location location, Component text) {
            return location.getWorld().spawn(location, TextDisplay.class, td -> {
                td.text(text);
                td.setPersistent(false);          // Non-persistent — won't survive restart
                td.setVisibleByDefault(true);

                // Billboard mode
                String billboard = plugin.getConfigManager().getHologramBillboard();
                td.setBillboard(parseBillboard(billboard));

                // See-through
                td.setSeeThrough(plugin.getConfigManager().isHologramSeeThrough());

                // Shadow
                td.setShadowed(plugin.getConfigManager().isHologramShadow());

                // Background color
                String bgColorStr = plugin.getConfigManager().getHologramBgColor();
                if (!bgColorStr.equalsIgnoreCase("none") && !bgColorStr.isEmpty()) {
                    try {
                        long argb = Long.decode(bgColorStr);
                        int a = (int) ((argb >> 24) & 0xFF);
                        int r = (int) ((argb >> 16) & 0xFF);
                        int g = (int) ((argb >> 8) & 0xFF);
                        int b = (int) (argb & 0xFF);
                        td.setBackgroundColor(Color.fromARGB(a, r, g, b));
                    } catch (NumberFormatException ignored) {
                        // Leave as default
                    }
                }

                // Scale
                float sx = plugin.getConfigManager().getHologramScaleX();
                float sy = plugin.getConfigManager().getHologramScaleY();
                float sz = plugin.getConfigManager().getHologramScaleZ();
                td.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(sx, sy, sz),
                        new AxisAngle4f(0, 0, 0, 1)
                ));

                td.setAlignment(TextDisplay.TextAlignment.CENTER);
            });
        }

        private Display.Billboard parseBillboard(String name) {
            try {
                return Display.Billboard.valueOf(name);
            } catch (IllegalArgumentException e) {
                return Display.Billboard.CENTER;
            }
        }
    }
}

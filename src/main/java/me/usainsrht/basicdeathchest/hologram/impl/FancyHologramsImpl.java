package me.usainsrht.basicdeathchest.hologram.impl;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.chest.DeathChest;
import me.usainsrht.basicdeathchest.hologram.DeathChestHologram;
import me.usainsrht.basicdeathchest.hologram.HologramProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class FancyHologramsImpl implements HologramProvider {

    private final BasicDeathChest plugin;
    private final CopyOnWriteArrayList<FancyHologram> activeHolograms = new CopyOnWriteArrayList<>();

    public FancyHologramsImpl(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "FANCY_HOLOGRAMS";
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("FancyHolograms");
    }

    @Override
    public DeathChestHologram createHologram(DeathChest chest, Location location) {
        FancyHologram hologram = new FancyHologram(chest, location);
        hologram.spawn();
        activeHolograms.add(hologram);
        return hologram;
    }

    @Override
    public void removeAll() {
        for (FancyHologram h : activeHolograms) {
            h.remove();
        }
        activeHolograms.clear();
    }

    private class FancyHologram extends DeathChestHologram {
        private final Location location;
        private Hologram fancyHolo;

        FancyHologram(DeathChest chest, Location location) {
            super(chest);
            this.location = location.clone();
        }

        @Override
        public void spawn() {
            String holoName = "bdc_" + UUID.randomUUID().toString().substring(0, 8);
            TextHologramData data = new TextHologramData(holoName, location);
            
            // Set text lines
            List<String> resolvedLines = resolveInitialLines();
            data.setText(resolvedLines);

            // Background color
            String bgColorStr = plugin.getConfigManager().getHologramBgColor();
            if (!bgColorStr.equalsIgnoreCase("none") && !bgColorStr.isEmpty()) {
                try {
                    long argb = Long.decode(bgColorStr);
                    int rgb = (int) (argb & 0xFFFFFF);
                    data.setBackground(org.bukkit.Color.fromRGB(rgb));
                } catch (NumberFormatException ignored) {}
            }

            // Billboard mode
            try {
                data.setBillboard(Display.Billboard.valueOf(plugin.getConfigManager().getHologramBillboard()));
            } catch (Exception ignored) {}

            // Scale
            float sx = plugin.getConfigManager().getHologramScaleX();
            float sy = plugin.getConfigManager().getHologramScaleY();
            float sz = plugin.getConfigManager().getHologramScaleZ();
            data.setScale(new Vector3f(sx, sy, sz));

            // Shadow
            data.setTextShadow(plugin.getConfigManager().isHologramShadow());

            // See-through
            data.setSeeThrough(plugin.getConfigManager().isHologramSeeThrough());

            data.setPersistent(false);

            HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
            fancyHolo = manager.create(data);
            manager.addHologram(fancyHolo);
        }

        @Override
        public void update(List<String> lines) {
            if (fancyHolo == null) return;
            TextHologramData data = (TextHologramData) fancyHolo.getData();
            data.setText(lines);
            
            // Force/queue update to players
            fancyHolo.queueUpdate();
        }

        @Override
        public void remove() {
            cancelUpdateTask();
            if (fancyHolo != null) {
                HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
                manager.removeHologram(fancyHolo);
                fancyHolo = null;
            }
            activeHolograms.remove(this);
        }

        private List<String> resolveInitialLines() {
            List<String> templates = plugin.getConfigManager().getHologramLines();
            String playerName = getOwningChest().getOwnerName();
            String timer = plugin.getMessagesManager().formatTimer(getOwningChest().getRemainingSeconds());
            return templates.stream()
                    .map(line -> line
                            .replace("%player%", playerName)
                            .replace("%timer%", timer))
                    .toList();
        }
    }
}

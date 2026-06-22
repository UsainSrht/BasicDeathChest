package me.usainsrht.basicdeathchest.hologram.impl;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.chest.DeathChest;
import me.usainsrht.basicdeathchest.hologram.DeathChestHologram;
import me.usainsrht.basicdeathchest.hologram.HologramProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class DecentHologramsImpl implements HologramProvider {

    private final BasicDeathChest plugin;
    private final CopyOnWriteArrayList<DecentHologram> activeHolograms = new CopyOnWriteArrayList<>();

    public DecentHologramsImpl(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "DECENT_HOLOGRAMS";
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("DecentHolograms");
    }

    @Override
    public DeathChestHologram createHologram(DeathChest chest, Location location) {
        DecentHologram hologram = new DecentHologram(chest, location);
        hologram.spawn();
        activeHolograms.add(hologram);
        return hologram;
    }

    @Override
    public void removeAll() {
        for (DecentHologram h : activeHolograms) {
            h.remove();
        }
        activeHolograms.clear();
    }

    private class DecentHologram extends DeathChestHologram {
        private final Location location;
        private Hologram hologram;

        DecentHologram(DeathChest chest, Location location) {
            super(chest);
            this.location = location.clone();
        }

        @Override
        public void spawn() {
            String holoId = "bdc_" + UUID.randomUUID().toString().substring(0, 8);
            List<String> resolvedLines = resolveInitialLines();
            hologram = DHAPI.createHologram(holoId, location, false, resolvedLines);
        }

        @Override
        public void update(List<String> lines) {
            if (hologram == null) return;
            DHAPI.setHologramLines(hologram, lines);
        }

        @Override
        public void remove() {
            cancelUpdateTask();
            if (hologram != null) {
                hologram.delete();
                hologram = null;
            }
            activeHolograms.remove(this);
        }

        private List<String> resolveInitialLines() {
            List<String> templates = plugin.getConfigManager().getHologramLines();
            String playerName = getOwningChest().getOwnerName();
            int intervalTicks = plugin.getConfigManager().getHologramUpdateIntervalTicks();
            String timer = plugin.getMessagesManager().formatTimer(getOwningChest().getRemainingSecondsDouble(), intervalTicks);
            return templates.stream()
                    .map(line -> line
                            .replace("%player%", playerName)
                            .replace("%timer%", timer))
                    .toList();
        }
    }
}

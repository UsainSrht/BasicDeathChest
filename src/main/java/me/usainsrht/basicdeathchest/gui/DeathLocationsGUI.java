package me.usainsrht.basicdeathchest.gui;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure Bukkit {@link InventoryHolder} GUI displaying a player's death locations.
 *
 * <p>No third-party GUI library is used. The inventory is built by calling
 * {@link #buildInventory(List)} and then opened on the player's region thread.
 *
 * <p>Click handling is performed in {@link GUIListener}.
 */
public class DeathLocationsGUI implements InventoryHolder {

    private final BasicDeathChest plugin;
    private final UUID viewerUUID;
    private final List<DeathEntry> entries;
    private Inventory inventory;

    /** Slot → death entry index mapping (built in {@link #buildInventory}). */
    private final List<DeathEntry> slotMap = new ArrayList<>();

    public DeathLocationsGUI(BasicDeathChest plugin, UUID viewerUUID, List<DeathEntry> entries) {
        this.plugin = plugin;
        this.viewerUUID = viewerUUID;
        this.entries = entries;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public UUID getViewerUUID() {
        return viewerUUID;
    }

    /** Returns the death entry associated with {@code slot}, or {@code null}. */
    public DeathEntry getEntryAt(int slot) {
        if (slot < 0 || slot >= slotMap.size()) return null;
        return slotMap.get(slot);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs and returns the inventory ready to be opened for a player.
     *
     * @param entriesToShow the death entries to display (already limited by config)
     * @return this instance (for chaining)
     */
    public DeathLocationsGUI buildInventory(List<DeathEntry> entriesToShow) {
        int guiRows = plugin.getConfigManager().getGuiRows();
        int size = guiRows * 9;

        Component title = MiniMessageUtil.parse(plugin.getConfigManager().getGuiTitle());
        inventory = Bukkit.createInventory(this, size, title);

        Material entryMaterial = plugin.getConfigManager().getGuiEntryMaterial();
        Material fillerMaterial = plugin.getConfigManager().getGuiFillerMaterial();

        // Filler for all slots
        ItemStack filler = createFiller(fillerMaterial);
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, filler);
        }

        // Slot mapping: slot -> death entry
        slotMap.clear();
        for (int i = 0; i < size; i++) slotMap.add(null); // pre-fill

        List<Integer> slots = plugin.getConfigManager().getGuiSlots();

        if (entriesToShow.isEmpty()) {
            int noRecordSlot = slots.isEmpty() ? (size / 2) : slots.get(slots.size() / 2);
            inventory.setItem(noRecordSlot, buildNoRecordItem());
        } else {
            int displayCount = Math.min(slots.size(), entriesToShow.size());
            for (int i = 0; i < displayCount; i++) {
                int slot = slots.get(i);
                DeathEntry entry = entriesToShow.get(i);
                slotMap.set(slot, entry);
                inventory.setItem(slot, buildEntryItem(entry, i + 1, entryMaterial));
            }
        }

        return this;
    }

    // ─── Item builders ────────────────────────────────────────────────────────

    private ItemStack buildEntryItem(DeathEntry entry, int index, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Display name
        meta.displayName(formatItemText(plugin.getMessagesManager().guiEntryName(String.valueOf(index))));

        // Lore
        List<Component> lore = new ArrayList<>();
        lore.add(formatItemText(MiniMessageUtil.parse(
                plugin.getMessagesManager().getRaw("gui-entry-lore-world"),
                "world", entry.getWorld())));
        lore.add(formatItemText(MiniMessageUtil.parse(
                plugin.getMessagesManager().getRaw("gui-entry-lore-cause"),
                "cause", entry.getFormattedCause())));
        lore.add(formatItemText(MiniMessageUtil.parse(
                plugin.getMessagesManager().getRaw("gui-entry-lore-coords"),
                "x", String.valueOf(entry.getX()),
                "y", String.valueOf(entry.getY()),
                "z", String.valueOf(entry.getZ()))));
        lore.add(formatItemText(MiniMessageUtil.parse(
                plugin.getMessagesManager().getRaw("gui-entry-lore-time"),
                "time", entry.getFormattedTime())));
        lore.add(Component.empty());
        lore.add(formatItemText(MiniMessageUtil.parse(plugin.getMessagesManager().getRaw("gui-entry-lore-click"))));

        meta.lore(lore);
        // Suppress attribute modifiers tooltip
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildNoRecordItem() {
        Material material = plugin.getConfigManager().getGuiNoRecordMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(formatItemText(plugin.getMessagesManager().guiNoEntries()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFiller(Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private Component formatItemText(Component component) {
        return component.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC, net.kyori.adventure.text.format.TextDecoration.State.FALSE);
    }
}

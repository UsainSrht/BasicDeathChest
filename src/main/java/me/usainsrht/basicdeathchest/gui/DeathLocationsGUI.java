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
    private final UUID targetUUID;
    private final String targetName;
    private final List<DeathEntry> entries;
    private final boolean isAdminView;
    private int page = 0;
    private Inventory inventory;

    /** Slot → death entry index mapping (built in {@link #buildInventory}). */
    private final List<DeathEntry> slotMap = new ArrayList<>();

    public DeathLocationsGUI(BasicDeathChest plugin, UUID viewerUUID, UUID targetUUID, String targetName, List<DeathEntry> entries, boolean isAdminView) {
        this.plugin = plugin;
        this.viewerUUID = viewerUUID;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.entries = entries;
        this.isAdminView = isAdminView;
    }

    public DeathLocationsGUI(BasicDeathChest plugin, UUID viewerUUID, List<DeathEntry> entries) {
        this(plugin, viewerUUID, viewerUUID, 
             Bukkit.getPlayer(viewerUUID) != null ? Bukkit.getPlayer(viewerUUID).getName() : Bukkit.getOfflinePlayer(viewerUUID).getName(),
             entries, false);
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
     * @param entriesToShow the death entries to display (ignored if admin view pagination is active)
     * @return this instance (for chaining)
     */
    public DeathLocationsGUI buildInventory(List<DeathEntry> entriesToShow) {
        int guiRows = plugin.getConfigManager().getGuiRows();
        int size = guiRows * 9;

        Component title;
        if (isAdminView) {
            String adminTitleFormat = plugin.getConfigManager().getAdminGuiTitle();
            if (adminTitleFormat == null) {
                adminTitleFormat = "<dark_gray>☠ <gold>%player%'s Deaths (Page %page%/%total_pages%)</gold> ☠";
            }
            title = MiniMessageUtil.parse(adminTitleFormat,
                    "player", targetName != null ? targetName : "Unknown",
                    "page", String.valueOf(page + 1),
                    "total_pages", String.valueOf(getTotalPages()));
        } else {
            title = MiniMessageUtil.parse(plugin.getConfigManager().getGuiTitle());
        }
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
        int pageSize = slots.size();
        if (pageSize <= 0) pageSize = 27; // fallback

        int startIndex = page * pageSize;
        int endIndex = Math.min(entries.size(), startIndex + pageSize);

        if (entries.isEmpty()) {
            int noRecordSlot = plugin.getConfigManager().getGuiNoRecordSlot();
            if (noRecordSlot < 0 || noRecordSlot >= size) {
                noRecordSlot = slots.isEmpty() ? (size / 2) : slots.get(slots.size() / 2);
            }
            inventory.setItem(noRecordSlot, buildNoRecordItem());
        } else {
            int entryIndex = startIndex;
            for (int i = 0; i < pageSize && entryIndex < endIndex; i++) {
                int slot = slots.get(i);
                if (slot >= 0 && slot < size) {
                    DeathEntry entry = entries.get(entryIndex);
                    slotMap.set(slot, entry);
                    inventory.setItem(slot, buildEntryItem(entry, entryIndex + 1, entryMaterial));
                }
                entryIndex++;
            }
        }

        if (isAdminView && size >= 18) {
            int bottomRowStart = size - 9;
            int totalPages = getTotalPages();

            // Previous Page button
            int prevSlot = bottomRowStart + plugin.getConfigManager().getGuiPrevSlotOffset();
            if (prevSlot >= 0 && prevSlot < size) {
                if (page > 0) {
                    inventory.setItem(prevSlot, buildPageButton(plugin.getConfigManager().getGuiPrevMaterial(), plugin.getMessagesManager().guiPreviousPage()));
                } else {
                    inventory.setItem(prevSlot, createFiller(fillerMaterial));
                }
            }

            // Page Info button
            int indicatorSlot = bottomRowStart + plugin.getConfigManager().getGuiIndicatorSlotOffset();
            if (indicatorSlot >= 0 && indicatorSlot < size) {
                inventory.setItem(indicatorSlot, buildPageIndicator(page + 1, totalPages, entries.size(), startIndex + 1, endIndex));
            }

            // Next Page button
            int nextSlot = bottomRowStart + plugin.getConfigManager().getGuiNextSlotOffset();
            if (nextSlot >= 0 && nextSlot < size) {
                if (page < totalPages - 1) {
                    inventory.setItem(nextSlot, buildPageButton(plugin.getConfigManager().getGuiNextMaterial(), plugin.getMessagesManager().guiNextPage()));
                } else {
                    inventory.setItem(nextSlot, createFiller(fillerMaterial));
                }
            }
        }

        if (plugin.getConfigManager().isGuiInfoEnabled()) {
            int infoSlot = plugin.getConfigManager().getGuiInfoSlot();
            if (infoSlot >= 0 && infoSlot < size) {
                if (!isAdminView || (size < 18 || infoSlot < size - 9)) {
                    inventory.setItem(infoSlot, buildInfoItem());
                }
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
                "cause", plugin.getMessagesManager().getTranslatedCause(entry.getDeathCause()))));
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
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.getMessagesManager().guiNoEntriesLore()) {
                lore.add(formatItemText(MiniMessageUtil.parse(line)));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildInfoItem() {
        int freeUses = plugin.getTeleportManager().getRemainingFreeUses(targetUUID);
        double cost = plugin.getConfigManager().getTeleportCost();
        String formattedCost = plugin.getVaultEconomy().isEnabled() ? plugin.getVaultEconomy().format(cost) : String.valueOf(cost);

        Material material = plugin.getConfigManager().getGuiInfoMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = plugin.getConfigManager().getGuiInfoName();
        meta.displayName(formatItemText(MiniMessageUtil.parse(name, "cost", formattedCost, "free_uses", String.valueOf(freeUses))));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfigManager().getGuiInfoLore()) {
            lore.add(formatItemText(MiniMessageUtil.parse(line, "cost", formattedCost, "free_uses", String.valueOf(freeUses))));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
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

    private ItemStack buildPageButton(Material material, Component displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(formatItemText(displayName));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildPageIndicator(int current, int total, int totalRecords, int start, int end) {
        Material material = plugin.getConfigManager().getGuiIndicatorMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(formatItemText(plugin.getMessagesManager().guiPageIndicatorName(String.valueOf(current), String.valueOf(total))));
            List<Component> lore = new ArrayList<>();
            if (totalRecords > 0) {
                for (Component comp : plugin.getMessagesManager().guiPageIndicatorLore(String.valueOf(totalRecords), String.valueOf(start), String.valueOf(end))) {
                    lore.add(formatItemText(comp));
                }
            } else {
                for (Component comp : plugin.getMessagesManager().guiPageIndicatorLoreEmpty()) {
                    lore.add(formatItemText(comp));
                }
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isAdminView() { return isAdminView; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public List<DeathEntry> getEntries() { return entries; }

    public int getTotalPages() {
        int pageSize = plugin.getConfigManager().getGuiSlots().size();
        if (pageSize <= 0) pageSize = 27; // fallback
        return Math.max(1, (int) Math.ceil((double) entries.size() / pageSize));
    }

    public void reopenPage(org.bukkit.entity.Player player) {
        buildInventory(entries);
        player.openInventory(inventory);
    }
}

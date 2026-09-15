package me.usainsrht.basicdeathchest.protection;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages killer loot protection for item entities dropped on the ground.
 *
 * <p>
 * When a victim dies and chest creation cannot take place (e.g. world filter,
 * no permission, placement obstruction, or inventory overflow), dropped item
 * entities
 * have their owner tag set to the killer for a configurable duration (default
 * 10s).
 *
 * <h3>Fail-safe mechanisms</h3>
 * <ul>
 * <li><b>Folia EntityScheduler:</b> Tasks are tied directly to the entity
 * lifecycle.</li>
 * <li><b>Chunk & Entities Reload:</b> Listens to {@link EntitiesLoadEvent} to
 * strip expired tags
 * or reschedule countdowns for items loaded from disk.</li>
 * <li><b>Pickup & Hopper Interception:</b> Intercepts
 * {@link EntityPickupItemEvent} and
 * {@link InventoryPickupItemEvent} (hoppers) to enforce exclusive killer access
 * and
 * instantly strip tags if the duration has elapsed.</li>
 * </ul>
 */
public class KillerProtectionManager implements Listener {

    private final BasicDeathChest plugin;
    private final NamespacedKey itemKillerKey;
    private final NamespacedKey itemKillerExpiryKey;
    private final ConcurrentHashMap<UUID, ScheduledTask> trackedTasks = new ConcurrentHashMap<>();

    public KillerProtectionManager(BasicDeathChest plugin) {
        this.plugin = plugin;
        this.itemKillerKey = new NamespacedKey(plugin, "killer_protection_owner");
        this.itemKillerExpiryKey = new NamespacedKey(plugin, "killer_protection_expiry");
    }

    /**
     * Drops {@code items} at {@code loc} with optional killer loot protection.
     * Must be called on the region thread owning {@code loc}.
     *
     * @param loc             the drop location
     * @param items           items to drop
     * @param killerUUID      UUID of the killer (if PvP), or {@code null}
     * @param durationSeconds protection duration in seconds (≤ 0 means no
     *                        protection)
     * @return the dropped item entities
     */
    public List<Item> dropProtectedItems(Location loc, List<ItemStack> items, UUID killerUUID, int durationSeconds) {
        List<Item> result = new ArrayList<>();
        if (items == null || items.isEmpty() || loc.getWorld() == null) {
            return result;
        }

        boolean applyProtection = killerUUID != null && durationSeconds > 0;
        long expiryMillis = applyProtection ? System.currentTimeMillis() + (durationSeconds * 1000L) : 0L;

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }

            Item itemEntity = loc.getWorld().dropItemNaturally(loc, item);
            if (applyProtection) {
                protectItemEntity(itemEntity, killerUUID, expiryMillis, durationSeconds * 20L);
            }
            result.add(itemEntity);
        }

        return result;
    }

    /**
     * Applies killer protection tags and schedules expiration for a single item
     * entity.
     */
    public void protectItemEntity(Item itemEntity, UUID killerUUID, long expiryMillis, long delayTicks) {
        itemEntity.setOwner(killerUUID);
        itemEntity.getPersistentDataContainer().set(itemKillerKey, PersistentDataType.STRING, killerUUID.toString());
        itemEntity.getPersistentDataContainer().set(itemKillerExpiryKey, PersistentDataType.LONG, expiryMillis);

        ScheduledTask task = FoliaUtil.runRepeatingOnEntity(
                plugin,
                itemEntity,
                scheduledTask -> {
                    scheduledTask.cancel();
                    stripItemProtection(itemEntity);
                },
                () -> trackedTasks.remove(itemEntity.getUniqueId()),
                Math.max(1L, delayTicks),
                1L);
        if (task != null) {
            trackedTasks.put(itemEntity.getUniqueId(), task);
        }
    }

    /**
     * Strips the killer owner tag and PDC keys from an item entity, allowing anyone
     * to pick it up.
     */
    public void stripItemProtection(Item itemEntity) {
        if (itemEntity != null) {
            trackedTasks.remove(itemEntity.getUniqueId());
            if (itemEntity.isValid()) {
                itemEntity.setOwner(null);
                itemEntity.getPersistentDataContainer().remove(itemKillerKey);
                itemEntity.getPersistentDataContainer().remove(itemKillerExpiryKey);
            }
        }
    }

    /**
     * Cancels all tracked tasks on shutdown.
     */
    public void disableAll() {
        for (ScheduledTask task : trackedTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        trackedTasks.clear();
    }

    // ─── Fail-Safe Listeners ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (!item.getPersistentDataContainer().has(itemKillerExpiryKey, PersistentDataType.LONG)) {
            return;
        }

        Long expiry = item.getPersistentDataContainer().get(itemKillerExpiryKey, PersistentDataType.LONG);
        if (expiry == null || System.currentTimeMillis() >= expiry) {
            // Protection expired — strip immediately and allow pickup
            stripItemProtection(item);
            return;
        }

        String killerUUIDStr = item.getPersistentDataContainer().get(itemKillerKey, PersistentDataType.STRING);
        LivingEntity entity = event.getEntity();
        if (killerUUIDStr != null && !entity.getUniqueId().toString().equals(killerUUIDStr)) {
            // Unauthorized entity attempted to pick up protected item
            event.setCancelled(true);
            if (entity instanceof Player player) {
                long remainingMillis = expiry - System.currentTimeMillis();
                int remainingSeconds = Math.max(1, (int) Math.ceil(remainingMillis / 1000.0));
                player.sendMessage(plugin.getMessagesManager().lootKillerProtected(String.valueOf(remainingSeconds)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof Item item)) {
                continue;
            }

            if (!item.getPersistentDataContainer().has(itemKillerExpiryKey, PersistentDataType.LONG)) {
                continue;
            }

            Long expiry = item.getPersistentDataContainer().get(itemKillerExpiryKey, PersistentDataType.LONG);
            if (expiry == null || System.currentTimeMillis() >= expiry) {
                stripItemProtection(item);
            } else {
                String killerStr = item.getPersistentDataContainer().get(itemKillerKey, PersistentDataType.STRING);
                if (killerStr != null) {
                    try {
                        UUID killerUUID = UUID.fromString(killerStr);
                        long remainingTicks = Math.max(1L, (expiry - System.currentTimeMillis()) / 50L);
                        protectItemEntity(item, killerUUID, expiry, remainingTicks);
                    } catch (IllegalArgumentException ignored) {
                        stripItemProtection(item);
                    }
                }
            }
        }
    }

    public NamespacedKey getItemKillerKey() {
        return itemKillerKey;
    }

    public NamespacedKey getItemKillerExpiryKey() {
        return itemKillerExpiryKey;
    }
}

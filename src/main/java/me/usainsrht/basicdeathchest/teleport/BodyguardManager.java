package me.usainsrht.basicdeathchest.teleport;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns and manages transient bodyguards for teleporting players.
 *
 * <h3>Bodyguard rules</h3>
 * <ul>
 *   <li>Must NOT target or damage the owning player.</li>
 *   <li>Must aggressively attack nearby hostile mobs.</li>
 *   <li>Drop ZERO loot or experience.</li>
 *   <li>Auto-despawn after a configurable duration.</li>
 *   <li>Are non-persistent (won't survive a restart).</li>
 * </ul>
 *
 * <p>This class implements {@link Listener} for entity events that enforce the
 * bodyguard rules. Register it as a listener on plugin enable.
 */
public class BodyguardManager implements Listener {

    /** PDC key stored on each bodyguard to track its owner UUID. */
    private static final String PDC_KEY = "bodyguard_owner";

    private final BasicDeathChest plugin;
    private final NamespacedKey bodyguardKey;
    /** Set of UUIDs belonging to active bodyguard entities. */
    private final Set<UUID> activeBodyguards = ConcurrentHashMap.newKeySet();
    /** Map of player UUID -> set of bodyguard entity UUIDs currently active for them. */
    private final Map<UUID, Set<UUID>> playerBodyguards = new ConcurrentHashMap<>();

    public BodyguardManager(BasicDeathChest plugin) {
        this.plugin = plugin;
        this.bodyguardKey = new NamespacedKey(plugin, PDC_KEY);
    }

    /**
     * Spawns the configured number of bodyguards at {@code location}
     * and schedules their auto-despawn.
     *
     * <p>Must be called on the region thread for {@code location}.
     *
     * @param location the arrival location
     * @param owner    the player being protected
     */
    public void spawnBodyguards(Location location, Player owner) {
        int count = plugin.getConfigManager().getBodyguardCount();
        int durationSeconds = plugin.getConfigManager().getBodyguardDuration();
        EntityType mobType = plugin.getConfigManager().getBodyguardMobType();
        Class<? extends org.bukkit.entity.Entity> entityClass = mobType.getEntityClass();

        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            plugin.getLogger().warning("Bodyguard mob type " + mobType + " is not a LivingEntity!");
            return;
        }

        Class<? extends LivingEntity> livingClass = entityClass.asSubclass(LivingEntity.class);
        Set<UUID> bodyguardsForPlayer = playerBodyguards.computeIfAbsent(owner.getUniqueId(), k -> ConcurrentHashMap.newKeySet());

        int updateInterval = plugin.getConfigManager().getBodyguardUpdateIntervalTicks();
        String nameTemplate = plugin.getConfigManager().getBodyguardNameTemplate();

        for (int i = 0; i < count; i++) {
            // Slight spread so they don't stack exactly
            Location spawnLoc = location.clone().add(
                    (i % 2 == 0 ? 1.5 : -1.5), 0, 0);

            LivingEntity bodyguard = location.getWorld().spawn(spawnLoc, livingClass, entity -> {
                entity.setPersistent(false);
                entity.setRemoveWhenFarAway(false);
                entity.setSilent(false);
                if (entity instanceof IronGolem golem) {
                    golem.setPlayerCreated(true); // Vanilla AI behavior for player-created golems
                }

                // Store owner UUID in PDC
                entity.getPersistentDataContainer().set(bodyguardKey,
                        PersistentDataType.STRING, owner.getUniqueId().toString());

                // Zero loot
                if (entity instanceof org.bukkit.loot.Lootable lootable) {
                    lootable.setLootTable(null);
                }

                // Max health for a proper tank
                if (entity.getAttribute(Attribute.MAX_HEALTH) != null) {
                    double health = plugin.getConfigManager().getBodyguardHealth();
                    entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
                    entity.setHealth(health);
                }
            });

            activeBodyguards.add(bodyguard.getUniqueId());
            bodyguardsForPlayer.add(bodyguard.getUniqueId());

            // Schedule auto-despawn and custom name update tasks on the entity scheduler
            long totalTicks = (long) durationSeconds * 20L;
            java.util.concurrent.atomic.AtomicLong ticksRemaining = new java.util.concurrent.atomic.AtomicLong(totalTicks);

            FoliaUtil.runRepeatingOnEntity(plugin, bodyguard, task -> {
                if (!bodyguard.isValid()) {
                    task.cancel();
                    removeBodyguard(bodyguard.getUniqueId(), owner.getUniqueId());
                    return;
                }
                long remTicks = ticksRemaining.get();
                if (remTicks <= 0) {
                    task.cancel();
                    removeBodyguard(bodyguard.getUniqueId(), owner.getUniqueId());
                    bodyguard.remove();
                    return;
                }

                // Update custom name with %timer% placeholder
                double secondsLeft = remTicks / 20.0;
                String timerStr;
                if (updateInterval % 20 == 0) {
                    timerStr = String.format("%.0f", Math.ceil(secondsLeft));
                } else {
                    timerStr = String.format(java.util.Locale.ROOT, "%.1f", secondsLeft);
                }
                net.kyori.adventure.text.Component nameComp = plugin.getMessagesManager().parse(nameTemplate, "timer", timerStr)
                        .decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC, net.kyori.adventure.text.format.TextDecoration.State.FALSE);
                bodyguard.customName(nameComp);
                bodyguard.setCustomNameVisible(true);

                ticksRemaining.addAndGet(-updateInterval);
            }, () -> {
                // Retired callback
                removeBodyguard(bodyguard.getUniqueId(), owner.getUniqueId());
            }, 1L, updateInterval);
        }

        owner.sendMessage(plugin.getMessagesManager().bodyguardsSpawned());
    }

    private void removeBodyguard(UUID entityUUID, UUID ownerUUID) {
        activeBodyguards.remove(entityUUID);
        Set<UUID> set = playerBodyguards.get(ownerUUID);
        if (set != null) {
            set.remove(entityUUID);
            if (set.isEmpty()) {
                playerBodyguards.remove(ownerUUID);
                // Notify owner if still online
                Player ownerPlayer = plugin.getServer().getPlayer(ownerUUID);
                if (ownerPlayer != null && ownerPlayer.isOnline()) {
                    FoliaUtil.runOnEntity(plugin, ownerPlayer, () ->
                            ownerPlayer.sendMessage(plugin.getMessagesManager().bodyguardsDespawned()),
                            null);
                }
            }
        }
    }

    /** Removes all active bodyguards (called on plugin disable). */
    public void removeAll() {
        for (UUID uuid : activeBodyguards) {
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) entity.remove();
        }
        activeBodyguards.clear();
        playerBodyguards.clear();
    }

    // ─── Event listeners ──────────────────────────────────────────────────────

    /**
     * Prevents bodyguards from targeting their owning player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyguardTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity bodyguard)) return;
        if (!isBodyguard(bodyguard)) return;
        if (!(event.getTarget() instanceof Player targetPlayer)) return;

        String ownerUUID = bodyguard.getPersistentDataContainer()
                .get(bodyguardKey, PersistentDataType.STRING);
        if (ownerUUID != null && targetPlayer.getUniqueId().toString().equals(ownerUUID)) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents bodyguards from damaging their owning player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyguardDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity bodyguard)) return;
        if (!isBodyguard(bodyguard)) return;
        if (!(event.getEntity() instanceof Player targetPlayer)) return;

        String ownerUUID = bodyguard.getPersistentDataContainer()
                .get(bodyguardKey, PersistentDataType.STRING);
        if (ownerUUID != null && targetPlayer.getUniqueId().toString().equals(ownerUUID)) {
            event.setCancelled(true);
        }
    }

    /**
     * Ensures bodyguards drop zero loot and zero XP on death.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyguardDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity bodyguard)) return;
        if (!isBodyguard(bodyguard)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        String ownerStr = bodyguard.getPersistentDataContainer().get(bodyguardKey, PersistentDataType.STRING);
        if (ownerStr != null) {
            try {
                UUID ownerUUID = UUID.fromString(ownerStr);
                removeBodyguard(bodyguard.getUniqueId(), ownerUUID);
            } catch (IllegalArgumentException ignored) {}
        } else {
            activeBodyguards.remove(bodyguard.getUniqueId());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean isBodyguard(LivingEntity entity) {
        return activeBodyguards.contains(entity.getUniqueId())
                || entity.getPersistentDataContainer().has(bodyguardKey, PersistentDataType.STRING);
    }
}

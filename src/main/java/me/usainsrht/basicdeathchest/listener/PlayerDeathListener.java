package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.ChestStatus;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import me.usainsrht.basicdeathchest.util.ItemStackSerializer;
import me.usainsrht.basicdeathchest.util.LocationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Intercepts {@link PlayerDeathEvent} to:
 * <ol>
 *   <li>Validate world whitelist, keepInventory, and permission.</li>
 *   <li>Clear drops and schedule chest creation on the region thread.</li>
 *   <li>Save a {@link DeathEntry} (with chest placement status) to the database.</li>
 *   <li>Handle custom death messages.</li>
 * </ol>
 *
 * <p>In Folia, {@link PlayerDeathEvent} is fired on the region thread that
 * owns the player's location. Block operations in the handler are therefore
 * safe for the same region; the chest placement is still delegated to the
 * {@link me.usainsrht.basicdeathchest.chest.DeathChestManager} which may
 * schedule follow-up work via {@link FoliaUtil#runOnRegion}.
 */
public class PlayerDeathListener implements Listener {

    private final BasicDeathChest plugin;

    public PlayerDeathListener(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        org.bukkit.World world = player.getWorld();

        // ── Guard: keepInventory ──────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        GameRule<Boolean> keepInventoryRule = (GameRule<Boolean>) org.bukkit.Registry.GAME_RULE.get(org.bukkit.NamespacedKey.minecraft("keep_inventory"));
        if (keepInventoryRule != null) {
            Boolean keepInv = world.getGameRuleValue(keepInventoryRule);
            if (Boolean.TRUE.equals(keepInv)) return;
        }

        // ── Guard: world allowed check ────────────────────────────────────────
        boolean worldAllowed = plugin.getConfigManager().isWorldAllowed(world.getName());
        boolean bypassWorldFilter = plugin.getConfigManager().isDatabaseBypassWorldFilter();

        if (!worldAllowed && !bypassWorldFilter) {
            return;
        }

        // ── Capture drops / death metadata ────────────────────────────────────
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        handleDeathMessage(event, player);

        Location deathLoc = player.getLocation().clone();
        CauseInfo causeInfo = extractCause(event);
        long timestamp = System.currentTimeMillis();
        UUIDInfo identity = new UUIDInfo(player.getUniqueId(), player.getName());

        // Admin inventory snapshot — independent of chest/drops; never abort death flow
        saveDeathItemsAsync(identity.uuid(), timestamp, snapshotInventory(player));

        // ── Guard: permission (still log the death) ───────────────────────────
        if (plugin.getConfigManager().isRequirePermission()
                && !player.hasPermission(plugin.getConfigManager().getRequiredPermission())) {
            player.sendMessage(plugin.getMessagesManager().chestPermissionRequired());
            saveEntry(identity, timestamp, causeInfo, deathLoc, ChestStatus.NO_PERMISSION);
            return;
        }

        // ── Create death chest on the region thread if world is allowed and drops are not empty ───
        if (worldAllowed && !drops.isEmpty()) {
            // Clear vanilla drops — chest will contain them
            event.getDrops().clear();

            final List<ItemStack> finalDrops = drops;
            FoliaUtil.runOnRegion(plugin, deathLoc, () -> {
                ChestStatus status;
                if (!player.isConnected()) {
                    // Player disconnected before chest could be placed — drop items
                    for (ItemStack item : finalDrops) {
                        if (item != null && !item.getType().isAir()) {
                            deathLoc.getWorld().dropItemNaturally(deathLoc, item);
                        }
                    }
                    status = ChestStatus.BLOCK_OBSTRUCTION;
                } else {
                    status = plugin.getDeathChestManager().createDeathChest(player, finalDrops);
                }
                saveEntry(identity, timestamp, causeInfo, deathLoc, status);
            });
        } else {
            ChestStatus status = !worldAllowed ? ChestStatus.WORLD_FILTERED : ChestStatus.NO_ITEMS;
            saveEntry(identity, timestamp, causeInfo, deathLoc, status);
        }
    }

    private void saveEntry(UUIDInfo identity, long timestamp, CauseInfo causeInfo,
                           Location deathLoc, ChestStatus chestStatus) {
        DeathEntry entry = new DeathEntry(
                identity.uuid(), identity.name(),
                timestamp, causeInfo.cause(), causeInfo.killer(), deathLoc, chestStatus);
        FoliaUtil.runAsync(plugin, () -> plugin.getDatabaseManager().saveEntry(entry));
    }

    private ItemStack[] snapshotInventory(Player player) {
        try {
            return ItemStackSerializer.cloneContents(player.getInventory().getContents());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to snapshot death inventory for " + player.getName(), e);
            return null;
        }
    }

    private void saveDeathItemsAsync(UUID playerUUID, long timestamp, ItemStack[] contents) {
        if (contents == null || ItemStackSerializer.isEmpty(contents)) return;
        FoliaUtil.runAsync(plugin, () -> {
            try {
                plugin.getDatabaseManager().saveDeathItems(playerUUID, timestamp, contents);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist death items for " + playerUUID, e);
            }
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void handleDeathMessage(PlayerDeathEvent event, Player player) {
        String mode = plugin.getConfigManager().getDeathMessageMode();
        switch (mode) {
            case "DISABLED" -> event.deathMessage(null);
            case "CUSTOM" -> {
                String format = plugin.getConfigManager().getDeathMessageCustomFormat();
                if (format.isBlank()) {
                    event.deathMessage(null);
                    return;
                }
                Location loc = player.getLocation();
                CauseInfo causeInfo = extractCause(event);
                String formattedCause = plugin.getMessagesManager()
                        .formatDeathCause(causeInfo.cause(), causeInfo.killer());
                var msg = plugin.getMessagesManager().parse(format,
                        "player", player.getName(),
                        "cause", formattedCause,
                        "x", LocationUtil.x(loc),
                        "y", LocationUtil.y(loc),
                        "z", LocationUtil.z(loc),
                        "world", LocationUtil.worldName(loc));
                event.deathMessage(msg);
            }
            default -> { /* VANILLA — do nothing, let Paper handle it */ }
        }
    }

    /**
     * Resolves damage cause enum name and optional killer display name.
     */
    private CauseInfo extractCause(PlayerDeathEvent event) {
        EntityDamageEvent last = event.getEntity().getLastDamageCause();
        if (last == null) return new CauseInfo("UNKNOWN", null);

        String cause = last.getCause().name();
        String killer = null;

        if (last instanceof EntityDamageByEntityEvent byEntity) {
            killer = resolveKillerName(byEntity.getDamager());
        }

        if (killer == null || killer.isBlank()) {
            Player pvpKiller = event.getEntity().getKiller();
            if (pvpKiller != null) {
                killer = pvpKiller.getName();
            }
        }

        if (killer != null && killer.isBlank()) {
            killer = null;
        }

        return new CauseInfo(cause, killer);
    }

    private String resolveKillerName(Entity damager) {
        if (damager == null) return null;

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                return resolveKillerName(shooterEntity);
            }
            return formatEntityType(damager.getType());
        }

        if (damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source != null) {
                return resolveKillerName(source);
            }
            return formatEntityType(EntityType.TNT);
        }

        if (damager instanceof AreaEffectCloud cloud) {
            ProjectileSource source = cloud.getSource();
            if (source instanceof Entity sourceEntity) {
                return resolveKillerName(sourceEntity);
            }
        }

        if (damager instanceof Player player) {
            return player.getName();
        }

        if (damager instanceof LivingEntity living && living.customName() != null) {
            String custom = PlainTextComponentSerializer.plainText().serialize(living.customName());
            if (!custom.isBlank()) return custom;
        }

        return formatEntityType(damager.getType());
    }

    private static String formatEntityType(EntityType type) {
        if (type == null) return "Unknown";
        String translated = PlainTextComponentSerializer.plainText()
                .serialize(Component.translatable(type.translationKey()));
        if (translated != null && !translated.isBlank() && !translated.equals(type.translationKey())) {
            return translated;
        }
        // Fallback when translation key is not resolved (e.g. offline tests)
        String raw = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private record CauseInfo(String cause, String killer) {}
    private record UUIDInfo(java.util.UUID uuid, String name) {}
}

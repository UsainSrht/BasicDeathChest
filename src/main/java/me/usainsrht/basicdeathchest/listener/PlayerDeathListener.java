package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.config.ConfigManager;
import me.usainsrht.basicdeathchest.database.model.ChestStatus;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import me.usainsrht.basicdeathchest.util.ItemStackSerializer;
import me.usainsrht.basicdeathchest.util.LocationUtil;
import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import me.usainsrht.itemapi.itemtext.ItemText;
import me.usainsrht.itemapi.itemtext.ItemTextOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
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
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            if (player.isOnline()) {
                player.sendMessage(plugin.getMessagesManager().chestPermissionRequired());
            }
            saveEntry(identity, timestamp, causeInfo, deathLoc, ChestStatus.NO_PERMISSION);
            return;
        }

        // ── Create death chest on the region thread if world is allowed and drops are not empty ───
        if (worldAllowed && !drops.isEmpty()) {
            // Clear vanilla drops — chest will contain them
            event.getDrops().clear();

            final List<ItemStack> finalDrops = drops;
            FoliaUtil.runOnRegion(plugin, deathLoc, () -> {
                ChestStatus status = plugin.getDeathChestManager().createDeathChest(player, deathLoc, finalDrops);
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
            default -> handleVanillaDeathMessage(event, player);
        }
    }

    /**
     * Optionally rewrites player-name / item args and/or wraps Paper's vanilla death message.
     * Keeps the {@link TranslatableComponent} key so clients still translate/locale.
     */
    private void handleVanillaDeathMessage(PlayerDeathEvent event, Player player) {
        ConfigManager cfg = plugin.getConfigManager();
        boolean styleEnabled = cfg.isVanillaDeathStyleEnabled();
        boolean placeholdersEnabled = cfg.isVanillaDeathPlaceholdersEnabled();
        boolean itemTextEnabled = cfg.isVanillaDeathItemTextEnabled();
        if (!styleEnabled && !placeholdersEnabled && !itemTextEnabled) {
            return;
        }

        Component message = event.deathMessage();
        if (message == null) {
            return;
        }

        Component result = message;
        if (placeholdersEnabled) {
            result = rewriteVanillaPlayerArgs(result, player, event.getEntity().getKiller());
        }
        if (itemTextEnabled) {
            result = rewriteVanillaItemArgs(result, event);
        }

        if (styleEnabled) {
            String format = cfg.getVanillaDeathStyleFormat();
            if (format != null && !format.isBlank()) {
                result = MiniMessageUtil.parseWithComponents(format, "death_message", result);
            }
        }

        event.deathMessage(result);
    }

    /**
     * Replaces translation args whose plain text matches an involved player's name
     * with MiniPlaceholders-resolved display names (same format, different audience).
     */
    private Component rewriteVanillaPlayerArgs(Component message, Player victim, Player killer) {
        if (!(message instanceof TranslatableComponent translatable)) {
            return message;
        }

        Map<String, Player> playersByName = new HashMap<>(4);
        indexPlayerNames(playersByName, victim);
        if (killer != null) {
            indexPlayerNames(playersByName, killer);
        }

        String playerFormat = plugin.getConfigManager().getVanillaDeathPlayerFormat();
        List<TranslationArgument> oldArgs = translatable.arguments();
        if (oldArgs.isEmpty()) {
            return message;
        }

        List<Component> newArgs = new ArrayList<>(oldArgs.size());
        boolean changed = false;
        for (TranslationArgument arg : oldArgs) {
            Component argComponent = arg.asComponent();
            String plain = MiniMessageUtil.plain(argComponent);
            Player matched = playersByName.get(plain);
            if (matched != null) {
                newArgs.add(plugin.getMiniPlaceholders().resolve(playerFormat, matched));
                changed = true;
            } else {
                newArgs.add(argComponent);
            }
        }

        if (!changed) {
            return message;
        }
        return translatable.arguments(newArgs);
    }

    private static void indexPlayerNames(Map<String, Player> map, Player player) {
        map.put(player.getName(), player);
        String displayPlain = MiniMessageUtil.plain(player.displayName());
        if (!displayPlain.isBlank()) {
            map.put(displayPlain, player);
        }
    }

    /**
     * Replaces item/weapon translation args with {@link ItemText}-formatted Components.
     */
    private Component rewriteVanillaItemArgs(Component message, PlayerDeathEvent event) {
        if (!(message instanceof TranslatableComponent translatable)) {
            return message;
        }

        ItemStack weapon = resolveDeathWeapon(event);
        if (weapon == null || weapon.getType().isAir() || weapon.getAmount() <= 0) {
            return message;
        }

        List<TranslationArgument> oldArgs = translatable.arguments();
        if (oldArgs.isEmpty()) {
            return message;
        }

        ItemTextOptions options = ItemTextOptions.defaults().toBuilder()
                .displayCustomName(plugin.getConfigManager().isVanillaDeathItemTextShowCustomName())
                .build();
        Component itemComponent = ItemText.format(weapon, options);

        List<Component> newArgs = new ArrayList<>(oldArgs.size());
        boolean changed = false;
        for (TranslationArgument arg : oldArgs) {
            Component argComponent = arg.asComponent();
            if (isItemArg(argComponent)) {
                newArgs.add(itemComponent);
                changed = true;
            } else {
                newArgs.add(argComponent);
            }
        }

        if (!changed) {
            return message;
        }
        return translatable.arguments(newArgs);
    }

    /**
     * True for death-message args that represent an item (hover show_item or item/block translation).
     */
    private static boolean isItemArg(Component component) {
        if (component == null) {
            return false;
        }
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && HoverEvent.Action.SHOW_ITEM.equals(hover.action())) {
            return true;
        }
        if (component instanceof TranslatableComponent tc) {
            String key = tc.key();
            return key.startsWith("item.") || key.startsWith("block.");
        }
        return false;
    }

    /**
     * Best-effort weapon used in the killing blow (killer main hand, else damager equipment).
     */
    private static ItemStack resolveDeathWeapon(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            ItemStack hand = killer.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir()) {
                return hand;
            }
        }

        EntityDamageEvent last = event.getEntity().getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }

        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooterEntity) {
            damager = shooterEntity;
        }

        if (damager instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                ItemStack hand = equipment.getItemInMainHand();
                if (hand != null && !hand.getType().isAir()) {
                    return hand;
                }
            }
        }
        return null;
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

        if (killer == null || killer.isBlank()) {
            try {
                org.bukkit.damage.DamageSource damageSource = last.getDamageSource();
                if (damageSource != null) {
                    if (damageSource.getCausingEntity() != null) {
                        killer = resolveKillerName(damageSource.getCausingEntity());
                    } else if (damageSource.getDirectEntity() != null) {
                        killer = resolveKillerName(damageSource.getDirectEntity());
                    }
                }
            } catch (Throwable ignored) {
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

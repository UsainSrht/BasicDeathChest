package me.usainsrht.basicdeathchest.teleport;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages player teleportation to death locations, including:
 * <ul>
 * <li>Vault economy cost deduction</li>
 * <li>Free-use tracking per player</li>
 * <li>Arrival potion effects</li>
 * <li>Arrival sound (heard by nearby players)</li>
 * <li>Bodyguard spawning</li>
 * </ul>
 *
 * <p>
 * Teleports are performed on the player's entity scheduler (Folia-safe).
 * Economy charges are applied BEFORE the teleport on the calling thread.
 */
public class TeleportManager {

    private final BasicDeathChest plugin;
    /** In-memory free-use counter: UUID → number of free teleports USED. */
    private final Map<UUID, Integer> freeUsesConsumed = new java.util.concurrent.ConcurrentHashMap<>();

    public TeleportManager(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to teleport {@code player} to the location stored in {@code entry}.
     *
     * <p>
     * Must be called on the player's region thread (e.g. inside an inventory
     * click handler or entity scheduler callback).
     *
     * @param player the player to teleport
     * @param entry  the target death entry
     */
    public void teleport(Player player, DeathEntry entry) {
        if (!plugin.getConfigManager().isTeleportEnabled()) {
            player.sendMessage(plugin.getMessagesManager().teleportDisabled());
            playFailureSound(player);
            return;
        }

        World world = Bukkit.getWorld(entry.getWorld());
        if (world == null) {
            player.sendMessage(plugin.getMessagesManager().teleportWorldNotLoaded());
            playFailureSound(player);
            return;
        }

        // Check and deduct cost
        if (!handleCost(player))
            return;

        Location destination = new Location(world, entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5);

        // Teleport on the player's entity scheduler
        FoliaUtil.runOnEntity(plugin, player, () -> {
            player.teleportAsync(destination).thenAccept(success -> {
                if (!success) {
                    playFailureSound(player);
                    return;
                }
                // Post-teleport effects — schedule on the player's new region thread
                FoliaUtil.runOnEntity(plugin, player, () -> {
                    applyArrivalEffects(player);
                    if (plugin.getConfigManager().isBodyguardsEnabled()) {
                        plugin.getBodyguardManager().spawnBodyguards(destination, player);
                    }
                    player.sendMessage(plugin.getMessagesManager().teleportSuccess());

                    // Delay sound playing slightly to allow the client to load terrain and receive
                    // the sound packet
                    FoliaUtil.runDelayedOnEntity(plugin, player, () -> {
                        playArrivalSound(player, destination);
                    }, null, 1L);
                }, null);
            });
        }, null);
    }

    /**
     * Loads the free-use counter for a player from the database.
     */
    public void loadFreeUses(UUID playerUUID) {
        plugin.getDatabaseManager().getFreeUsesConsumed(playerUUID, count -> {
            freeUsesConsumed.put(playerUUID, count);
        });
    }

    /**
     * Unloads the free-use counter for a player to prevent memory leaks.
     */
    public void unloadPlayer(UUID playerUUID) {
        freeUsesConsumed.remove(playerUUID);
    }

    /**
     * Resets the free-use counter for a player (e.g. on server restart or explicit
     * admin reset).
     */
    public void resetFreeUses(UUID playerUUID) {
        freeUsesConsumed.remove(playerUUID);
        FoliaUtil.runAsync(plugin, () -> plugin.getDatabaseManager().saveFreeUsesConsumed(playerUUID, 0));
    }

    /** Returns how many free teleports the player has remaining. */
    public int getRemainingFreeUses(Player player) {
        int max = plugin.getConfigManager().getTeleportFreeUses();
        int used = freeUsesConsumed.getOrDefault(player.getUniqueId(), 0);
        return Math.max(0, max - used);
    }

    /** Plays the teleport failure sound to the player. */
    public void playFailureSound(Player player) {
        Sound sound = plugin.getConfigManager().getTeleportFailureSound();
        if (sound != null) {
            FoliaUtil.runOnEntity(plugin, player, () -> player.playSound(sound), null);
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Checks free uses and Vault balance, charges if applicable.
     *
     * @return {@code true} if the teleport should proceed
     */
    private boolean handleCost(Player player) {
        // Permission bypass
        if (player.hasPermission("basicdeathchest.teleport.free"))
            return true;

        int maxFree = plugin.getConfigManager().getTeleportFreeUses();
        int used = freeUsesConsumed.getOrDefault(player.getUniqueId(), 0);

        if (used < maxFree) {
            freeUsesConsumed.merge(player.getUniqueId(), 1, Integer::sum);
            int remaining = maxFree - used - 1;
            player.sendMessage(plugin.getMessagesManager().teleportFreeRemaining(
                    String.valueOf(remaining)));
            int newUsed = used + 1;
            FoliaUtil.runAsync(plugin, () -> plugin.getDatabaseManager().saveFreeUsesConsumed(player.getUniqueId(), newUsed));
            return true;
        }

        // Paid teleport
        double cost = plugin.getConfigManager().getTeleportCost();
        if (cost > 0 && plugin.getVaultEconomy().isEnabled()) {
            if (!plugin.getVaultEconomy().canAfford(player, cost)) {
                double balance = plugin.getVaultEconomy().getBalance(player);
                player.sendMessage(plugin.getMessagesManager().teleportInsufficientFunds(
                        plugin.getVaultEconomy().format(cost),
                        plugin.getVaultEconomy().format(balance)));
                playFailureSound(player);
                return false;
            }
            plugin.getVaultEconomy().charge(player, cost);
            player.sendMessage(plugin.getMessagesManager().teleportCost(
                    plugin.getVaultEconomy().format(cost)));
        }
        return true;
    }

    private void applyArrivalEffects(Player player) {
        for (PotionEffect effect : plugin.getConfigManager().getArrivalEffects()) {
            player.addPotionEffect(effect);
        }
    }

    private void playArrivalSound(Player player, Location location) {
        Sound sound = plugin.getConfigManager().getArrivalSound();
        double radius = plugin.getConfigManager().getArrivalSoundRadius();

        // Play sound to the teleported player directly so they definitely hear it
        player.playSound(sound);

        // Play sound at location (heard by nearby players within radius)
        location.getWorld().getNearbyPlayers(location, radius)
                .forEach(nearby -> {
                    if (!nearby.getUniqueId().equals(player.getUniqueId())) {
                        nearby.playSound(sound, location.getX(), location.getY(), location.getZ());
                    }
                });
    }
}

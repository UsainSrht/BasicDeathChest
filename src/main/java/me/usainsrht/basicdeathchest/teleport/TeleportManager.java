package me.usainsrht.basicdeathchest.teleport;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.database.model.DeathEntry;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages player teleportation to death locations, including:
 * <ul>
 *   <li>Vault economy cost deduction</li>
 *   <li>Free-use tracking per player</li>
 *   <li>Arrival potion effects</li>
 *   <li>Arrival sound (heard by nearby players)</li>
 *   <li>Bodyguard spawning</li>
 * </ul>
 *
 * <p>Teleports are performed on the player's entity scheduler (Folia-safe).
 * Economy charges are applied BEFORE the teleport on the calling thread.
 */
public class TeleportManager {

    private final BasicDeathChest plugin;
    /** In-memory free-use counter: UUID → number of free teleports USED. */
    private final Map<UUID, Integer> freeUsesConsumed = new HashMap<>();

    public TeleportManager(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to teleport {@code player} to the location stored in {@code entry}.
     *
     * <p>Must be called on the player's region thread (e.g. inside an inventory
     * click handler or entity scheduler callback).
     *
     * @param player the player to teleport
     * @param entry  the target death entry
     */
    public void teleport(Player player, DeathEntry entry) {
        if (!plugin.getConfigManager().isTeleportEnabled()) {
            player.sendMessage(plugin.getMessagesManager().teleportDisabled());
            return;
        }

        World world = Bukkit.getWorld(entry.getWorld());
        if (world == null) {
            player.sendMessage(plugin.getMessagesManager().teleportWorldNotLoaded());
            return;
        }

        // Check and deduct cost
        if (!handleCost(player)) return;

        Location destination = new Location(world, entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5);

        // Teleport on the player's entity scheduler
        FoliaUtil.runOnEntity(plugin, player, () -> {
            player.teleportAsync(destination).thenAccept(success -> {
                if (!success) return;
                // Post-teleport effects — schedule on the player's new region thread
                FoliaUtil.runOnEntity(plugin, player, () -> {
                    applyArrivalEffects(player);
                    playArrivalSound(destination);
                    if (plugin.getConfigManager().isBodyguardsEnabled()) {
                        plugin.getBodyguardManager().spawnBodyguards(destination, player);
                    }
                    player.sendMessage(plugin.getMessagesManager().teleportSuccess());
                }, null);
            });
        }, null);
    }

    /**
     * Resets the free-use counter for a player (e.g. on server restart or explicit admin reset).
     */
    public void resetFreeUses(UUID playerUUID) {
        freeUsesConsumed.remove(playerUUID);
    }

    /** Returns how many free teleports the player has remaining. */
    public int getRemainingFreeUses(Player player) {
        int max = plugin.getConfigManager().getTeleportFreeUses();
        int used = freeUsesConsumed.getOrDefault(player.getUniqueId(), 0);
        return Math.max(0, max - used);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Checks free uses and Vault balance, charges if applicable.
     *
     * @return {@code true} if the teleport should proceed
     */
    private boolean handleCost(Player player) {
        // Permission bypass
        if (player.hasPermission("basicdeathchest.teleport.free")) return true;

        int maxFree = plugin.getConfigManager().getTeleportFreeUses();
        int used = freeUsesConsumed.getOrDefault(player.getUniqueId(), 0);

        if (used < maxFree) {
            freeUsesConsumed.merge(player.getUniqueId(), 1, Integer::sum);
            int remaining = maxFree - used - 1;
            player.sendMessage(plugin.getMessagesManager().teleportFreeRemaining(
                    String.valueOf(remaining)));
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

    private void playArrivalSound(Location location) {
        Sound sound = plugin.getConfigManager().getArrivalSound();
        float volume = plugin.getConfigManager().getArrivalSoundVolume();
        float pitch = plugin.getConfigManager().getArrivalSoundPitch();
        double radius = plugin.getConfigManager().getArrivalSoundRadius();

        // Play sound at location (heard by nearby players within radius)
        location.getWorld().getNearbyPlayers(location, radius)
                .forEach(nearby -> nearby.playSound(location, sound, volume, pitch));
    }
}

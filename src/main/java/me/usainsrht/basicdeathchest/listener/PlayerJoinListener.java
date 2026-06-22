package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles player join logic.
 */
public class PlayerJoinListener implements Listener {

    private final BasicDeathChest plugin;

    public PlayerJoinListener(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Nothing required on join currently.
        // This listener is a placeholder for future join-time logic
        // (e.g., notifying the player of expired chests, etc.)
    }
}


package me.usainsrht.basicdeathchest.listener;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.gui.GUIListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles player join logic and intercepts the {@code /deathchest} command.
 *
 * <p>Command routing is done via {@link PlayerCommandPreprocessEvent} rather than
 * a separate {@link org.bukkit.command.CommandExecutor} to keep Folia scheduling
 * straightforward — the event fires on the player's region thread.
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

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase().trim();
        boolean matches = msg.startsWith("/deathchest ") || msg.equals("/deathchest")
                || msg.startsWith("/dc ") || msg.equals("/dc")
                || msg.startsWith("/bdc ") || msg.equals("/bdc")
                || msg.startsWith("/death ") || msg.equals("/death");
        if (!matches) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("basicdeathchest.use")) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessagesManager().noPermission());
            return;
        }

        event.setCancelled(true);

        // Parse subcommand
        String[] parts = event.getMessage().split("\\s+", 2);
        String sub = (parts.length > 1) ? parts[1].toLowerCase() : "gui";

        switch (sub) {
            case "gui", "history" -> {
                if (!player.hasPermission("basicdeathchest.gui")) {
                    player.sendMessage(plugin.getMessagesManager().noPermission());
                    return;
                }
                GUIListener.openFor(plugin, player);
            }
            case "reload" -> {
                if (!player.hasPermission("basicdeathchest.admin")) {
                    player.sendMessage(plugin.getMessagesManager().noPermission());
                    return;
                }
                player.sendMessage(plugin.getMessagesManager().get("admin-reload-start"));
                try {
                    plugin.reload();
                    player.sendMessage(plugin.getMessagesManager().reloadSuccess());
                } catch (Exception e) {
                    plugin.getLogger().severe("Reload failed: " + e.getMessage());
                    player.sendMessage(plugin.getMessagesManager().reloadFail());
                }
            }
            case "info", "help" -> sendHelp(player);
            default -> {
                // Treat unknown sub as "gui"
                if (player.hasPermission("basicdeathchest.gui")) {
                    GUIListener.openFor(plugin, player);
                } else {
                    sendHelp(player);
                }
            }
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessagesManager().parse(
                "<gold><bold>BasicDeathChest</bold></gold> <gray>v" + plugin.getPluginMeta().getVersion()));
        player.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/deathchest gui</yellow> <gray>— Open your death locations history."));
        player.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/deathchest reload</yellow> <gray>— Reload configuration (admin)."));
        player.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/deathchest info</yellow> <gray>— Show this help message."));
    }
}

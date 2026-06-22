package me.usainsrht.basicdeathchest.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.gui.GUIListener;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DeathChestCommand implements BasicCommand {

    private final BasicDeathChest plugin;

    public DeathChestCommand(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        // 1. Check main command permission
        String cmdPermission = plugin.getConfigManager().getCommandPermission();
        if (cmdPermission != null && !cmdPermission.isEmpty() && !sender.hasPermission(cmdPermission)) {
            sender.sendMessage(plugin.getMessagesManager().noPermission());
            return;
        }

        // 2. Parse subcommand
        String sub = (args.length > 0) ? args[0].toLowerCase() : "gui";

        switch (sub) {
            case "gui", "history" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.getMessagesManager().playerOnly());
                    return;
                }
                String guiPermission = plugin.getConfigManager().getCommandGuiPermission();
                if (guiPermission != null && !guiPermission.isEmpty() && !player.hasPermission(guiPermission)) {
                    player.sendMessage(plugin.getMessagesManager().noPermission());
                    return;
                }
                GUIListener.openFor(plugin, player);
            }
            case "reload" -> {
                String adminPermission = plugin.getConfigManager().getCommandAdminPermission();
                if (adminPermission != null && !adminPermission.isEmpty() && !sender.hasPermission(adminPermission)) {
                    sender.sendMessage(plugin.getMessagesManager().noPermission());
                    return;
                }
                sender.sendMessage(plugin.getMessagesManager().get("admin-reload-start"));
                try {
                    plugin.reload();
                    sender.sendMessage(plugin.getMessagesManager().reloadSuccess());
                } catch (Exception e) {
                    plugin.getLogger().severe("Reload failed: " + e.getMessage());
                    sender.sendMessage(plugin.getMessagesManager().reloadFail());
                }
            }
            case "info", "help" -> sendHelp(sender);
            default -> {
                // If it's an unknown subcommand, treat as gui/history if player, else show help
                if (sender instanceof Player player) {
                    String guiPermission = plugin.getConfigManager().getCommandGuiPermission();
                    if (guiPermission != null && !guiPermission.isEmpty() && player.hasPermission(guiPermission)) {
                        GUIListener.openFor(plugin, player);
                    } else {
                        sendHelp(player);
                    }
                } else {
                    sendHelp(sender);
                }
            }
        }
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        CommandSender sender = source.getSender();
        
        // Check main permission first
        String cmdPermission = plugin.getConfigManager().getCommandPermission();
        if (cmdPermission != null && !cmdPermission.isEmpty() && !sender.hasPermission(cmdPermission)) {
            return List.of();
        }

        String adminPermission = plugin.getConfigManager().getCommandAdminPermission();
        String guiPermission = plugin.getConfigManager().getCommandGuiPermission();

        if (adminPermission != null && sender.hasPermission(adminPermission)) {
            list.add("gui");
            list.add("history");
            list.add("reload");
            list.add("info");
            list.add("help");
        } else if (guiPermission != null && sender.hasPermission(guiPermission)) {
            list.add("gui");
            list.add("history");
            list.add("info");
            list.add("help");
        } else {
            list.add("info");
            list.add("help");
        }

        String current = args.length == 0 ? "" : args[0].toLowerCase();
        return list.stream().filter(s -> s.startsWith(current)).toList();
    }

    @Override
    public @Nullable String permission() {
        return plugin.getConfigManager().getCommandPermission();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessagesManager().parse(
                "<gold><bold>BasicDeathChest</bold></gold> <gray>v" + plugin.getPluginMeta().getVersion()));
        
        String cmdName = plugin.getConfigManager().getCommandName();
        sender.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/" + cmdName + " gui</yellow> <gray>— Open your death locations history."));
        sender.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/" + cmdName + " reload</yellow> <gray>— Reload configuration (admin)."));
        sender.sendMessage(plugin.getMessagesManager().parse(
                "<yellow>/" + cmdName + " info</yellow> <gray>— Show this help message."));
    }
}

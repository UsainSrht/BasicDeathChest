package me.usainsrht.basicdeathchest.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.usainsrht.basicdeathchest.BasicDeathChest;
import me.usainsrht.basicdeathchest.gui.GUIListener;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DeathChestCommand {

    public static LiteralCommandNode<CommandSourceStack> build(BasicDeathChest plugin) {
        return Commands.literal(plugin.getConfigManager().getCommandName())
                .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandPermission()))
                
                // gui subcommand
                .then(Commands.literal("gui")
                        .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandGuiPermission()))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                GUIListener.openFor(plugin, player);
                            } else {
                                sender.sendMessage(plugin.getMessagesManager().playerOnly());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandAdminPermission()))
                                .suggests((ctx, builder) -> {
                                    String current = builder.getRemaining().toLowerCase();
                                    org.bukkit.Bukkit.getOnlinePlayers().stream()
                                            .map(Player::getName)
                                            .filter(name -> name.toLowerCase().startsWith(current))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    if (sender instanceof Player player) {
                                        String target = StringArgumentType.getString(ctx, "player");
                                        GUIListener.openAdminGuiFor(plugin, player, target);
                                    } else {
                                        sender.sendMessage(plugin.getMessagesManager().playerOnly());
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                
                // history subcommand (same logic)
                .then(Commands.literal("history")
                        .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandGuiPermission()))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                GUIListener.openFor(plugin, player);
                            } else {
                                sender.sendMessage(plugin.getMessagesManager().playerOnly());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandAdminPermission()))
                                .suggests((ctx, builder) -> {
                                    String current = builder.getRemaining().toLowerCase();
                                    org.bukkit.Bukkit.getOnlinePlayers().stream()
                                            .map(Player::getName)
                                            .filter(name -> name.toLowerCase().startsWith(current))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    if (sender instanceof Player player) {
                                        String target = StringArgumentType.getString(ctx, "player");
                                        GUIListener.openAdminGuiFor(plugin, player, target);
                                    } else {
                                        sender.sendMessage(plugin.getMessagesManager().playerOnly());
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                
                // reload subcommand
                .then(Commands.literal("reload")
                        .requires(stack -> stack.getSender().hasPermission(plugin.getConfigManager().getCommandAdminPermission()))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            sender.sendMessage(plugin.getMessagesManager().get("admin-reload-start"));
                            try {
                                plugin.reload();
                                sender.sendMessage(plugin.getMessagesManager().reloadSuccess());
                            } catch (Exception e) {
                                plugin.getLogger().severe("Reload failed: " + e.getMessage());
                                sender.sendMessage(plugin.getMessagesManager().reloadFail());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                )
                
                // info subcommand
                .then(Commands.literal("info")
                        .executes(ctx -> {
                            sendHelp(plugin, ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                
                // help subcommand
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            sendHelp(plugin, ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                
                // Default executor for the command itself
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player player) {
                        String guiPermission = plugin.getConfigManager().getCommandGuiPermission();
                        if (guiPermission == null || guiPermission.isEmpty() || player.hasPermission(guiPermission)) {
                            GUIListener.openFor(plugin, player);
                        } else {
                            sendHelp(plugin, player);
                        }
                    } else {
                        sendHelp(plugin, sender);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static void sendHelp(BasicDeathChest plugin, CommandSender sender) {
        sender.sendMessage(plugin.getMessagesManager().getHelpHeader(plugin.getPluginMeta().getVersion()));
        
        String cmdName = plugin.getConfigManager().getCommandName();
        sender.sendMessage(plugin.getMessagesManager().getHelpGui(cmdName));
        if (sender.hasPermission(plugin.getConfigManager().getCommandAdminPermission())) {
            sender.sendMessage(plugin.getMessagesManager().getHelpAdminGui(cmdName));
            sender.sendMessage(plugin.getMessagesManager().getHelpReload(cmdName));
        }
        sender.sendMessage(plugin.getMessagesManager().getHelpInfo(cmdName));
    }
}

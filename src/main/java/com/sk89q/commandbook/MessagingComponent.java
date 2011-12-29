/*
 * CommandBook
 * Copyright (C) 2011 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.commandbook;

import com.sk89q.commandbook.components.AbstractComponent;
import com.sk89q.commandbook.components.InjectComponent;
import com.sk89q.commandbook.config.ConfigurationBase;
import com.sk89q.commandbook.config.Setting;
import com.sk89q.commandbook.events.CommandSenderMessageEvent;
import com.sk89q.commandbook.events.SharedMessageEvent;
import com.sk89q.commandbook.events.core.BukkitEvent;
import com.sk89q.commandbook.session.SessionComponent;
import com.sk89q.commandbook.util.PlayerUtil;
import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

import static com.sk89q.commandbook.CommandBookUtil.replaceColorMacros;

/**
 * @author zml2008
 */
public class MessagingComponent extends AbstractComponent implements Listener {

    @InjectComponent private SessionComponent sessions;
    
    private LocalConfiguration config;
    
    @Override
    public void initialize() {
        config = configure(new LocalConfiguration());
        registerCommands(Commands.class);
        CommandBook.inst().getEventManager().registerEvents(this, this);
    }

    @Override
    public void reload() {
        configure(config);
    }

    private static class LocalConfiguration extends ConfigurationBase {
        @Setting("console-say-format") public String consoleSayFormat = "<`r*Console`w> %s";
        @Setting("broadcast-format") public String broadcastFormat = "`r[Broadcast] %s";
    }

    /**
     * Called on player chat.
     * 
     * @param event Relevant event details
     */
    @BukkitEvent(type = Event.Type.PLAYER_CHAT)
    public void onChat(PlayerChatEvent event) {
        if (sessions.getAdminSession(event.getPlayer()).isMute()) {
            event.getPlayer().sendMessage(ChatColor.RED + "You are muted.");
            event.setCancelled(true);
        }
    }

    public class Commands {
        @Command(aliases = {"me"}, usage = "<message...>", desc = "Send an action message", min = 1, max = -1)
        @CommandPermissions({"commandbook.say.me"})
        public void me(CommandContext args, CommandSender sender) throws CommandException {
            if (sender instanceof Player && sessions.getAdminSession((Player) sender).isMute()) {
                sender.sendMessage(ChatColor.RED + "You are muted.");
                return;
            }

            String name = PlayerUtil.toName(sender);
            String msg = args.getJoinedStrings(0);

            CommandBook.inst().getEventManager().callEvent(
                    new SharedMessageEvent(name + " " + msg));

            CommandBook.server().broadcastMessage("* " + name + " " + msg);
        }

        @Command(aliases = {"say"}, usage = "<message...>", desc = "Send a message", min = 1, max = -1)
        @CommandPermissions({"commandbook.say"})
        public void say(CommandContext args, CommandSender sender) throws CommandException {
            if (sender instanceof Player && sessions.getAdminSession((Player) sender).isMute()) {
                sender.sendMessage(ChatColor.RED + "You are muted.");
                return;
            }

            String msg = args.getJoinedStrings(0);

            if (sender instanceof Player) {
                if (CommandBook.inst().getEventManager().callEvent(
                        new PlayerChatEvent((Player) sender, msg)).isCancelled()) {
                    return;
                }
            }

            CommandBook.inst().getEventManager().callEvent(
                    new CommandSenderMessageEvent(sender, msg));

            if (sender instanceof Player) {
                CommandBook.server().broadcastMessage(
                        "<" + PlayerUtil.toColoredName(sender, ChatColor.WHITE)
                                + "> " + args.getJoinedStrings(0));
            } else {
                CommandBook.server().broadcastMessage(
                        replaceColorMacros(config.consoleSayFormat).replace(
                                "%s", args.getJoinedStrings(0)));
            }
        }

        @Command(aliases = {"msg"}, usage = "<target> <message...>", desc = "Private message a user", min = 2, max = -1)
        @CommandPermissions({"commandbook.msg"})
        public void msg(CommandContext args, CommandSender sender) throws CommandException {
            // This will throw errors as needed
            CommandSender receiver =
                    PlayerUtil.matchPlayerOrConsole(sender, args.getString(0));
            String message = args.getJoinedStrings(1);

            if (receiver instanceof Player && sessions.getSession((Player) receiver).getIdleStatus() != null) {
                String status = sessions.getSession((Player) receiver).getIdleStatus();
                sender.sendMessage(ChatColor.GRAY + PlayerUtil.toName(receiver) + " is afk. "
                        + "They might not see your message."
                        + (status.isEmpty() ? "" : " (" + status + ")"));
            }

            receiver.sendMessage(ChatColor.GRAY + "(From "
                    + PlayerUtil.toName(sender) + "): "
                    + ChatColor.WHITE + message);

            sender.sendMessage(ChatColor.GRAY + "(To "
                    + PlayerUtil.toName(receiver) + "): "
                    + ChatColor.WHITE + message);

            CommandBook.logger().info("(PM) " + PlayerUtil.toName(sender) + " -> "
                    + PlayerUtil.toName(receiver) + ": " + message);

            sessions.getSession(sender).setLastRecipient(receiver);

            // If the receiver hasn't had any player talk to them yet or hasn't
            // send a message, then we add it to the receiver's last message target
            // so s/he can /reply easily
            sessions.getSession(receiver).setNewLastRecipient(sender);
        }

        @Command(aliases = {"reply"}, usage = "<message...>", desc = "Reply to last user", min = 1, max = -1)
        @CommandPermissions({"commandbook.msg"})
        public void reply(CommandContext args, CommandSender sender) throws CommandException {
            String message = args.getJoinedStrings(0);
            CommandSender receiver;

            String lastRecipient = sessions.getSession(sender).getLastRecipient();

            if (lastRecipient != null) {
                // This will throw errors as needed
                receiver = PlayerUtil.matchPlayerOrConsole(sender, lastRecipient);
            } else {
                sender.sendMessage(ChatColor.RED + "You haven't messaged anyone.");
                return;
            }

            if (receiver instanceof Player && sessions.getSession((Player) receiver).getIdleStatus() != null) {
                String status = sessions.getSession((Player) receiver).getIdleStatus();
                sender.sendMessage(ChatColor.GRAY + PlayerUtil.toName(receiver) + " is afk. "
                        + "They might not see your message."
                        + (status.isEmpty() ? "" : " (" + status + ")"));
            }

            receiver.sendMessage(ChatColor.GRAY + "(From "
                    + PlayerUtil.toName(sender) + "): "
                    + ChatColor.WHITE + message);

            sender.sendMessage(ChatColor.GRAY + "(To "
                    + PlayerUtil.toName(receiver) + "): "
                    + ChatColor.WHITE + message);

            CommandBook.logger().info("(PM) " + PlayerUtil.toName(sender) + " -> "
                    + PlayerUtil.toName(receiver) + ": " + message);

            // If the receiver hasn't had any player talk to them yet or hasn't
            // send a message, then we add it to the receiver's last message target
            // so s/he can /reply easily
            sessions.getSession(receiver).setNewLastRecipient(sender);
        }

        @Command(aliases = {"afk"},
                usage = "", desc = "Set yourself as away",
                flags = "", min = 0, max = -1)
        @CommandPermissions({"commandbook.away"})
        public void afk(CommandContext args, CommandSender sender) throws CommandException {

            Player player = PlayerUtil.checkPlayer(sender);

            if (sessions.getSession(player).getIdleStatus() == null) {
                String status = "";
                if (args.argsLength() > 0) {
                    status = args.getJoinedStrings(0);
                    sessions.getSession(player).setIdleStatus(status);
                }

                player.sendMessage(ChatColor.YELLOW
                        + (status.isEmpty() ? "Set as away" : "Set away status to \"" + status + "\"")
                        + ". To return, type /afk again.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "You are no longer away.");
                sessions.getSession(player).setIdleStatus(null);
            }
        }

        @Command(aliases = {"mute"}, usage = "<target>", desc = "Mute a player", min = 1, max = 1)
        @CommandPermissions({"commandbook.mute"})
        public void mute(CommandContext args, CommandSender sender) throws CommandException {

            Player player = PlayerUtil.matchSinglePlayer(sender, args.getString(0));

            sessions.getAdminSession(player).setMute(true);

            player.sendMessage(ChatColor.YELLOW + "You've been muted by "
                    + PlayerUtil.toName(sender));
            sender.sendMessage(ChatColor.YELLOW + "You've muted "
                    + PlayerUtil.toName(player));
        }

        @Command(aliases = {"unmute"}, usage = "<target>", desc = "Unmute a player", min = 1, max = 1)
        @CommandPermissions({"commandbook.mute"})
        public void unmute(CommandContext args, CommandSender sender) throws CommandException {
            Player player = PlayerUtil.matchSinglePlayer(sender, args.getString(0));

            sessions.getAdminSession(player).setMute(false);

            player.sendMessage(ChatColor.YELLOW + "You've been unmuted by "
                    + PlayerUtil.toName(sender));
            sender.sendMessage(ChatColor.YELLOW + "You've unmuted "
                    + PlayerUtil.toName(player));
        }

        @Command(aliases = {"broadcast"}, usage = "<message...>", desc = "Broadcast a message", min = 1, max = -1)
        @CommandPermissions({"commandbook.broadcast"})
        public void broadcast(CommandContext args, CommandSender sender) throws CommandException {
            CommandBook.server().broadcastMessage(
                    replaceColorMacros(config.broadcastFormat).replace(
                            "%s", args.getJoinedStrings(0)));
        }
    }
}
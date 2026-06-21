package me.usainsrht.basicdeathchest.api.events;

import me.usainsrht.basicdeathchest.api.interfaces.IDeathChest;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player opens a death chest inventory.
 * Cancelling prevents the inventory from being opened.
 */
public class DeathChestOpenEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final IDeathChest chest;
    private final Player opener;
    private boolean cancelled;

    public DeathChestOpenEvent(IDeathChest chest, Player opener) {
        super(false);
        this.chest = chest;
        this.opener = opener;
        this.cancelled = false;
    }

    /**
     * Returns the chest being opened.
     */
    public IDeathChest getChest() {
        return chest;
    }

    /**
     * Returns the player attempting to open the chest.
     */
    public Player getOpener() {
        return opener;
    }

    /**
     * Returns {@code true} if the opener is the owner of the chest.
     */
    public boolean isOwner() {
        return opener.getUniqueId().equals(chest.getOwnerUUID());
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

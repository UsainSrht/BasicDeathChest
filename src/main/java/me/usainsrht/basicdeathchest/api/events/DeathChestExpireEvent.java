package me.usainsrht.basicdeathchest.api.events;

import me.usainsrht.basicdeathchest.api.interfaces.IDeathChest;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the region thread for the chest's location when the chest's timer
 * reaches zero and it is about to expire.
 *
 * <p>Cancelling this event prevents the expiration: the chest's timer will
 * not reset, but the removal will be aborted for this tick. Use sparingly.
 */
public class DeathChestExpireEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final IDeathChest chest;
    private boolean cancelled;

    public DeathChestExpireEvent(IDeathChest chest) {
        super(false);
        this.chest = chest;
        this.cancelled = false;
    }

    /**
     * Returns the chest that is about to expire.
     */
    public IDeathChest getChest() {
        return chest;
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

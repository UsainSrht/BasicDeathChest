package me.usainsrht.basicdeathchest.api.events;

import me.usainsrht.basicdeathchest.api.interfaces.IDeathChest;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired (asynchronously safe, but called on the region thread) just before a
 * death chest is created for a player.
 *
 * <p>Cancelling this event causes all captured drops to be released naturally
 * at the death location, as if no plugin had intercepted them.
 */
public class DeathChestCreateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final List<ItemStack> drops;
    private boolean cancelled;

    public DeathChestCreateEvent(Player player, List<ItemStack> drops) {
        super(false); // synchronous — called on the owning region thread
        this.player = player;
        this.drops = drops;
        this.cancelled = false;
    }

    /**
     * Returns the player for whom the chest is being created.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Returns the mutable list of items that will be placed inside the chest.
     * Modifications affect what ends up in the chest.
     */
    public List<ItemStack> getDrops() {
        return drops;
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

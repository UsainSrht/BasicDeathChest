package me.usainsrht.basicdeathchest.hologram;

import me.usainsrht.basicdeathchest.chest.DeathChest;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.List;

/**
 * Represents the hologram display associated with a single {@link DeathChest}.
 *
 * <p>Each implementation wraps backend-specific entity/handle references and
 * exposes lifecycle methods that the {@link HologramManager} uses to control
 * the display.
 */
public abstract class DeathChestHologram {

    protected final DeathChest owningChest;
    private ScheduledTask updateTask;

    protected DeathChestHologram(DeathChest owningChest) {
        this.owningChest = owningChest;
    }

    /**
     * Spawns (or shows) the hologram in the world.
     * Called on the region thread for the chest's location.
     */
    public abstract void spawn();

    /**
     * Updates the text lines shown by this hologram.
     * Called on the region thread for the chest's location.
     *
     * @param lines resolved, ready-to-display MiniMessage strings
     */
    public abstract void update(List<String> lines);

    /**
     * Permanently removes this hologram from the world.
     * Must be safe to call even if {@link #spawn()} was never invoked.
     */
    public abstract void remove();

    // ─── Update task management ───────────────────────────────────────────────

    /** @return the owning chest this hologram tracks. */
    public DeathChest getOwningChest() {
        return owningChest;
    }

    /** Stores the Folia scheduled task responsible for refreshing the hologram text. */
    public void setUpdateTask(ScheduledTask task) {
        this.updateTask = task;
    }

    /** Cancels the update task if one is active. */
    public void cancelUpdateTask() {
        if (updateTask != null && !updateTask.isCancelled()) {
            updateTask.cancel();
        }
    }

    /** Returns whether the update task is still active. */
    public boolean hasActiveUpdateTask() {
        return updateTask != null && !updateTask.isCancelled();
    }
}

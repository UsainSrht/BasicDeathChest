package me.usainsrht.basicdeathchest.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thread-safe scheduling helpers that exclusively use the Folia
 * regionized scheduler API. Never falls back to {@code Bukkit.getScheduler()}.
 *
 * <p>All world/block operations must be performed on the region owning the
 * target location. Use {@link #runOnRegion} for one-shot work and
 * {@link #runRepeatingOnRegion} for periodic tasks.
 */
public final class FoliaUtil {

    private FoliaUtil() {}

    // ─── Region Scheduler ────────────────────────────────────────────────────

    /**
     * Runs {@code task} on the region thread responsible for {@code location}
     * at the next available opportunity.
     */
    public static ScheduledTask runOnRegion(Plugin plugin, Location location, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin, location, $ -> task.run());
    }

    /**
     * Runs {@code task} on the region thread for {@code location} after
     * {@code delayTicks} server ticks.
     */
    public static ScheduledTask runDelayedOnRegion(Plugin plugin, Location location,
                                                    Runnable task, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, location, $ -> task.run(), delayTicks);
    }

    /**
     * Schedules a repeating task on the region thread for {@code location}.
     *
     * @param initialDelayTicks ticks to wait before the first execution (minimum 1)
     * @param periodTicks       ticks between subsequent executions (minimum 1)
     * @return the {@link ScheduledTask} handle — call {@link ScheduledTask#cancel()} to stop it
     */
    public static ScheduledTask runRepeatingOnRegion(Plugin plugin, Location location,
                                                      Consumer<ScheduledTask> task,
                                                      long initialDelayTicks, long periodTicks) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task,
                initialDelayTicks, periodTicks);
    }

    // ─── Async Scheduler ─────────────────────────────────────────────────────

    /**
     * Runs {@code task} asynchronously (off all region threads).
     * Suitable for database I/O, network calls, etc.
     */
    public static ScheduledTask runAsync(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, $ -> task.run());
    }

    /**
     * Schedules a repeating async task.
     *
     * @param initialDelay initial delay in {@code unit}
     * @param period       period in {@code unit}
     * @param unit         time unit for the above values
     */
    public static ScheduledTask runRepeatingAsync(Plugin plugin, Consumer<ScheduledTask> task,
                                                   long initialDelay, long period, TimeUnit unit) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task, initialDelay, period, unit);
    }

    // ─── Entity Scheduler ────────────────────────────────────────────────────

    /**
     * Runs {@code task} on the entity's owning region thread.
     *
     * @param retired called if the entity is removed before the task executes; may be {@code null}
     */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        entity.getScheduler().run(plugin, $ -> task.run(), retired);
    }

    /**
     * Runs {@code task} on the entity's owning region thread after {@code delayTicks}.
     */
    public static void runDelayedOnEntity(Plugin plugin, Entity entity, Runnable task,
                                           Runnable retired, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, $ -> task.run(), retired, delayTicks);
    }

    /**
     * Schedules a repeating task on the entity's owning region thread.
     */
    public static ScheduledTask runRepeatingOnEntity(Plugin plugin, Entity entity,
                                                      Consumer<ScheduledTask> task,
                                                      Runnable retired,
                                                      long initialDelayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired,
                initialDelayTicks, periodTicks);
    }

    // ─── Global Region Scheduler ─────────────────────────────────────────────

    /**
     * Runs {@code task} on the global region (suitable for server-wide operations
     * that don't belong to any specific world region).
     */
    public static ScheduledTask runGlobal(Plugin plugin, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, $ -> task.run());
    }

    /**
     * Schedules a repeating task on the global region thread.
     */
    public static ScheduledTask runRepeatingGlobal(Plugin plugin, Consumer<ScheduledTask> task,
                                                     long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task,
                initialDelayTicks, periodTicks);
    }
}

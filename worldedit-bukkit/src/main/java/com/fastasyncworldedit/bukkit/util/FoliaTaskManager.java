package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import javax.annotation.Nonnull;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;

public class FoliaTaskManager extends TaskManager {

    private final static MethodHandle IS_GLOBAL_TICK_THREAD;

    static {
        try {
            IS_GLOBAL_TICK_THREAD = lookup().findStatic(Bukkit.class, "isGlobalTickThread", methodType(boolean.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
          throw new AssertionError("Incompatile Folia version", e);
        }
    }

    private final AtomicInteger idCounter = new AtomicInteger();

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        // TODO (folia) return some kind of own ScheduledTask instead of int
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                WorldEditPlugin.getInstance(),
                asConsumer(runnable),
                1, // Folia doesn't allow initial delay <= 0, use 1 tick minimum
                interval
        );
        return idCounter.getAndIncrement();
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        // TODO (folia) return some kind of own ScheduledTask instead of int
        Bukkit.getAsyncScheduler().runAtFixedRate(
                WorldEditPlugin.getInstance(),
                asConsumer(runnable),
                1, // Folia doesn't allow initial delay <= 0, use 1ms minimum
                ticksToMs(interval),
                TimeUnit.MILLISECONDS
        );
        return idCounter.getAndIncrement();
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(WorldEditPlugin.getInstance(), asConsumer(runnable));
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        taskGlobal(runnable);
    }

    
    public void taskGlobal(final Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().run(WorldEditPlugin.getInstance(), asConsumer(runnable));
    }

    public void later(@NotNull final Runnable runnable, final Location location, final int delay) {
        if (delay <= 0) {
            // Folia doesn't allow delay <= 0, run immediately instead
            Bukkit.getRegionScheduler().run(
                    WorldEditPlugin.getInstance(),
                    BukkitAdapter.adapt(location),
                    asConsumer(runnable)
            );
        } else {
            Bukkit.getRegionScheduler().runDelayed(
                    WorldEditPlugin.getInstance(),
                    BukkitAdapter.adapt(location),
                    asConsumer(runnable),
                    delay
            );
        }
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        laterGlobal(runnable, delay);
    }

    public void laterGlobal(@NotNull final Runnable runnable, final int delay) {
        if (delay <= 0) {
            // Folia doesn't allow delay <= 0, run immediately instead
            Bukkit.getGlobalRegionScheduler().run(
                    WorldEditPlugin.getInstance(),
                    asConsumer(runnable)
            );
        } else {
            Bukkit.getGlobalRegionScheduler().runDelayed(
                    WorldEditPlugin.getInstance(),
                    asConsumer(runnable),
                    delay
            );
        }
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (delay <= 0) {
            // Folia doesn't allow delay <= 0, run immediately instead
            Bukkit.getAsyncScheduler().runNow(
                    WorldEditPlugin.getInstance(),
                    asConsumer(runnable)
            );
        } else {
            Bukkit.getAsyncScheduler().runDelayed(
                    WorldEditPlugin.getInstance(),
                    asConsumer(runnable),
                    ticksToMs(delay),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void cancel(final int task) {
        // In Folia, task cancellation is handled differently
        // For now, we'll just log the cancellation attempt
        if (FoliaSupport.isFolia()) {
            System.out.println("[FAWE] Task cancellation requested for task ID: " + task + " (Folia - not implemented)");
        } else {
            // Traditional Bukkit/Spigot - use scheduler
            Bukkit.getScheduler().cancelTask(task);
        }
    }

    public <T> T syncAt(final Supplier<T> supplier, final World world, final int chunkX, final int chunkZ) {
        final org.bukkit.World adapt = BukkitAdapter.adapt(world);
        if (Bukkit.isOwnedByCurrentRegion(adapt, chunkX, chunkZ)) {
            return supplier.get();
        }
        // Check if this is a QueueHandler task
        if (supplier instanceof QueueHandler) {
            // In Folia, there is no traditional Bukkit main thread. We align FAWE's
            // notion of main-thread to scheduler thread we execute on.
            boolean isGlobalTick = FoliaSupport.getRethrowing(() -> (boolean) IS_GLOBAL_TICK_THREAD.invokeExact());
            
            Thread prev = Fawe.instance().getMainThread();
            try {
                Fawe.instance().setMainThread();
                supplier.get();
            } finally {
                // Restore previous main thread reference
                if (prev != null && prev != Thread.currentThread()) {
                    // Only restore if it changed
                    //noinspection ResultOfMethodCallIgnored
                    Fawe.instance().setMainThread();
                }
            }
        }
        
        FutureTask<T> task = new FutureTask<>(supplier::get);
        Bukkit.getRegionScheduler().run(
                WorldEditPlugin.getInstance(),
                adapt,
                chunkX,
                chunkZ,
                asConsumer(task)
        );
        try {
            return task.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <R> Consumer<R> asConsumer(Runnable runnable) {
        return __ -> runnable.run();
    }

    public <T> T syncWith(final Supplier<T> supplier, final Player context) {
        final org.bukkit.entity.Player adapt = BukkitAdapter.adapt(context);
        if (Bukkit.isOwnedByCurrentRegion(adapt)) {
            return supplier.get();
        }
        ensureOffTickThread();
        FutureTask<T> task = new FutureTask<>(supplier::get);
        adapt.getScheduler().execute(WorldEditPlugin.getInstance(), task, null, 0);
        try {
            return task.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T syncGlobal(final Supplier<T> supplier) {
        if (com.fastasyncworldedit.core.util.FoliaSupport.isFolia()) {
            // In Folia, run immediately on current thread
            return supplier.get();
        } else {
            // Traditional Bukkit/Spigot - use global scheduler
            FutureTask<T> task = new FutureTask<>(supplier::get);
            Bukkit.getGlobalRegionScheduler().run(WorldEditPlugin.getInstance(), asConsumer(task));
            try {
                return task.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private boolean isGlobalTickThread() {
        return FoliaSupport.getRethrowing(() -> (boolean) IS_GLOBAL_TICK_THREAD.invokeExact());
    }

    private void ensureOffTickThread() {
        if (FoliaSupport.isTickThread()) {
            throw new IllegalStateException("Expected to be off tick thread");
        }
    }

    private int ticksToMs(int ticks) {
        // 1 tick = 50ms
        return ticks * 50;
    }

    private <T> T fail(String message) {
        throw new UnsupportedOperationException(message);
    }

}

package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FoliaTaskManager extends TaskManager {

    private final Plugin plugin;
    private final Map<Integer, ScheduledTask> tasks = new HashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(0);
    private final Executor mainThreadExecutor;

    public FoliaTaskManager(final Plugin plugin) {
        this.plugin = plugin;
        this.mainThreadExecutor = new MainThreadExecutor(plugin);
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        int taskId = taskIdCounter.incrementAndGet();
        
        // Check if this is a QueueHandler task
        if (runnable instanceof QueueHandler) {
            // In Folia, there is no traditional Bukkit main thread. We align FAWE's
            // notion of main-thread to the scheduler thread we execute on.
            ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> {
                        Thread prev = Fawe.instance().getMainThread();
                        try {
                            Fawe.instance().setMainThread();
                            runnable.run();
                        } finally {
                            // Restore previous main thread reference
                            if (prev != null && prev != Thread.currentThread()) {
                                // Only restore if it changed
                                //noinspection ResultOfMethodCallIgnored
                                Fawe.instance().setMainThread();
                                // setMainThread() sets to current thread, so instead directly restore via reflection-like
                                // Fallback: if API lacks setter to arbitrary thread, skip restoration.
                            }
                        }
                    }, 1, interval);
            tasks.put(taskId, task);
        } else {
            // For other tasks, use the regular scheduler
            ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> runnable.run(), 1, interval);
            tasks.put(taskId, task);
        }
        return taskId;
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        int taskId = taskIdCounter.incrementAndGet();
        AtomicReference<ScheduledTask> taskRef = new AtomicReference<>();
        ScheduledTask task = Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, t -> {
                    runnable.run();
                    taskRef.set(t);
                }, 0, interval * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
        tasks.put(taskId, task);
        return taskId;
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        if (runnable instanceof QueueHandler) {
            // Execute via global region scheduler and align FAWE's main thread
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                Thread prev = Fawe.instance().getMainThread();
                try {
                    Fawe.instance().setMainThread();
                    runnable.run();
                } finally {
                    if (prev != null && prev != Thread.currentThread()) {
                        // see note above
                    }
                }
            });
        } else {
            // For other tasks, use the regular scheduler
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
            } else {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
            }
        }
    }

    public void later(@Nonnull final Runnable runnable, final int delay) {
        int actualDelay = Math.max(1, delay);  // Ensure delay is at least 1
        
        if (runnable instanceof QueueHandler) {
            // For QueueHandler, always use Folia schedulers and set FAWE main-thread
            if (delay <= 0) {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    Thread prev = Fawe.instance().getMainThread();
                    try {
                        Fawe.instance().setMainThread();
                        runnable.run();
                    } finally {
                        if (prev != null && prev != Thread.currentThread()) {
                            // see note above
                        }
                    }
                });
            } else {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                    Thread prev = Fawe.instance().getMainThread();
                    try {
                        Fawe.instance().setMainThread();
                        runnable.run();
                    } finally {
                        if (prev != null && prev != Thread.currentThread()) {
                            // see note above
                        }
                    }
                }, actualDelay);
            }
        } else {
            // For other tasks, use the regular scheduler
            if (delay <= 0) {
                if (Bukkit.isPrimaryThread()) {
                    runnable.run();
                } else {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
                }
            } else {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), actualDelay);
            }
        }
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), 
            delay * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(final int taskId) {
        ScheduledTask task = tasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }
}

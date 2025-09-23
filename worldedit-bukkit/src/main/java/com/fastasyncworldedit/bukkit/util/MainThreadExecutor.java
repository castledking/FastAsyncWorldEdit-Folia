package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executor;

/**
 * Executor that ensures tasks are run on the main server thread.
 */
public class MainThreadExecutor implements Executor {
    private final Plugin plugin;
    
    public MainThreadExecutor(Plugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void execute(Runnable command) {
        if (command instanceof QueueHandler) {
            // Folia: run on global region scheduler and align FAWE main-thread
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                Thread prev = Fawe.instance().getMainThread();
                try {
                    Fawe.instance().setMainThread();
                    command.run();
                } finally {
                    if (prev != null && prev != Thread.currentThread()) {
                        // see FoliaTaskManager note on restoration
                    }
                }
            });
        } else {
            // For other tasks, use the global region scheduler
            if (Bukkit.isPrimaryThread()) {
                command.run();
            } else {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> command.run());
            }
        }
    }
}

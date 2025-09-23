package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_8.regen;

import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.SingleThreadQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.RegenOptions;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import com.sk89q.worldedit.EditSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

public class PaperweightDirectRegen extends Regenerator {

    private static final Logger LOGGER = Logger.getLogger(PaperweightDirectRegen.class.getName());

    private ServerLevel originalServerWorld;
    private ServerChunkCache chunkSource;
    private Plugin wePlugin;
    private boolean isFolia;
    private ScheduledTask foliaScheduledTask;
    private SingleThreadQueueExtent source;

    public PaperweightDirectRegen(
            org.bukkit.World originalBukkitWorld,
            Region region,
            Extent target,
            RegenOptions options
    ) {
        super(originalBukkitWorld, region, target, options);
    }

    @Override
    protected void runTasks(BooleanSupplier shouldKeepTicking) {
        final int tasksPerTick = 5;
        if (isFolia) {
            if (wePlugin == null) {
                LOGGER.severe("WorldEdit plugin not found, cannot schedule tasks");
                return;
            }
            if (foliaScheduledTask == null) {
                foliaScheduledTask = wePlugin.getServer().getGlobalRegionScheduler().runAtFixedRate(wePlugin, scheduledTask -> {
                    if (!shouldKeepTicking.getAsBoolean()) {
                        scheduledTask.cancel();
                        return;
                    }
                    boolean more = true;
                    for (int i = 0; i < tasksPerTick && more; i++) {
                        more = chunkSource.runDistanceManagerUpdates();
                    }
                    if (!more) {
                        scheduledTask.cancel();
                    }
                }, 1L, 1L);
            }
        } else {
            while (shouldKeepTicking.getAsBoolean()) {
                if (!chunkSource.pollTask()) {
                    return;
                }
            }
        }
    }

    @Override
    protected boolean prepare() {
        this.originalServerWorld = ((org.bukkit.craftbukkit.CraftWorld) originalBukkitWorld).getHandle();
        this.chunkSource = originalServerWorld.getChunkSource();
        seed = options.getSeed().orElse(originalServerWorld.getSeed());
        isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {
        }
        wePlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (wePlugin == null) {
            LOGGER.severe("WorldEdit plugin not found");
            return false;
        }
        foliaScheduledTask = null;
        LOGGER.info("Prepared direct regeneration for world: " + originalBukkitWorld.getName() + ", target extent: " + target.getClass().getSimpleName());
        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        LOGGER.info("Direct regeneration: initializing chunk regeneration");

        List<CompletableFuture<Void>> chunkLoadings = new ArrayList<>();
        List<BlockVector2> chunks = new ArrayList<>(region.getChunks());
        LOGGER.info("Regenerating " + chunks.size() + " chunks: " + chunks);

        for (BlockVector2 bvChunk : chunks) {
            int cx = bvChunk.x();
            int cz = bvChunk.z();

            // Unload chunk
            if (isFolia) {
                CompletableFuture<Void> unloadFuture = new CompletableFuture<>();
                Location loc = new Location(originalBukkitWorld, cx * 16 + 8, 64, cz * 16 + 8);
                wePlugin.getServer().getRegionScheduler().run(wePlugin, loc, task -> {
                    try {
                        unloadChunk(cx, cz);
                        unloadFuture.complete(null);
                    } catch (Exception e) {
                        LOGGER.warning("Failed to unload chunk " + cx + ", " + cz + ": " + e.getMessage());
                        unloadFuture.completeExceptionally(e);
                    }
                });
                try {
                    unloadFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    LOGGER.severe("Failed to unload chunk " + cx + ", " + cz + ": " + e.getMessage());
                    throw e;
                }
            } else {
                unloadChunk(cx, cz);
            }

            // Load/Generate chunk
            if (isFolia) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                Location loc = new Location(originalBukkitWorld, cx * 16 + 8, 64, cz * 16 + 8);
                wePlugin.getServer().getRegionScheduler().run(wePlugin, loc, task -> {
                    try {
                        org.bukkit.Chunk chunk = originalBukkitWorld.getChunkAt(cx, cz);
                        chunk.load(true); // Force generation
                        LOGGER.info("Loaded chunk at " + cx + ", " + cz);
                        future.complete(null);
                    } catch (Exception e) {
                        LOGGER.severe("Failed to load chunk at " + cx + ", " + cz + ": " + e.getMessage());
                        future.completeExceptionally(e);
                    }
                });
                chunkLoadings.add(future);
            } else {
                org.bukkit.Chunk chunk = originalBukkitWorld.getChunkAt(cx, cz);
                chunk.load(true); // Force generation
                LOGGER.info("Loaded chunk at " + cx + ", " + cz);
                chunkLoadings.add(CompletableFuture.completedFuture(null));
            }
        }

        // Wait for all
        try {
            CompletableFuture.allOf(chunkLoadings.toArray(new CompletableFuture<?>[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.severe("Failed to complete chunk loading: " + e.getMessage());
            throw e;
        }

        // Initialize source for copying
        createSource();
        LOGGER.info("Direct regeneration: all chunks generated");
        return true;
    }

    private void unloadChunk(int cx, int cz) {
        try {
            org.bukkit.Chunk bChunk = originalBukkitWorld.getChunkAt(cx, cz);
            if (bChunk.isLoaded()) {
                bChunk.unload(true);
                LOGGER.info("Unloaded chunk at " + cx + ", " + cz);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to unload chunk " + cx + ", " + cz + ": " + e.getMessage());
        }
    }

    public void copyToWorld() {
        LOGGER.info("Copying regenerated chunks to world via base Regenerator");
        super.copyToWorld(); // Use base Regenerator's copyToWorld to apply changes

        // Commit EditSession changes
        if (target instanceof EditSession) {
            try {
                ((EditSession) target).commit();
                LOGGER.info("Committed EditSession to apply changes to world");
            } catch (Exception e) {
                LOGGER.severe("Failed to commit EditSession: " + e.getMessage());
            }
        }

        // Force client-side chunk update
        List<CompletableFuture<Void>> refreshFutures = new ArrayList<>();
        for (BlockVector2 bvChunk : region.getChunks()) {
            int cx = bvChunk.x();
            int cz = bvChunk.z();
            if (isFolia) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                Location loc = new Location(originalBukkitWorld, cx * 16 + 8, 64, cz * 16 + 8);
                wePlugin.getServer().getRegionScheduler().run(wePlugin, loc, task -> {
                    try {
                        org.bukkit.Chunk chunk = originalBukkitWorld.getChunkAt(cx, cz);
                        chunk.load(true); // Ensure chunk is loaded
                        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot();
                        LOGGER.info("Captured snapshot for chunk " + cx + ", " + cz + ", height: " + snapshot.getHighestBlockYAt(8, 8));
                        for (Player player : originalBukkitWorld.getPlayers()) {
                            ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.send(
                                new ClientboundLevelChunkWithLightPacket(
                                    originalServerWorld.getChunk(cx, cz),
                                    originalServerWorld.getLightEngine(),
                                    null,
                                    null
                                )
                            );
                        }
                        future.complete(null);
                    } catch (Exception e) {
                        LOGGER.warning("Failed to refresh chunk " + cx + ", " + cz + ": " + e.getMessage());
                        future.completeExceptionally(e);
                    }
                });
                refreshFutures.add(future);
            } else {
                org.bukkit.Chunk chunk = originalBukkitWorld.getChunkAt(cx, cz);
                chunk.load(true); // Ensure chunk is loaded
                org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot();
                LOGGER.info("Captured snapshot for chunk " + cx + ", " + cz + ", height: " + snapshot.getHighestBlockYAt(8, 8));
                for (Player player : originalBukkitWorld.getPlayers()) {
                    ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.send(
                        new ClientboundLevelChunkWithLightPacket(
                            originalServerWorld.getChunk(cx, cz),
                            originalServerWorld.getLightEngine(),
                            null,
                            null
                        )
                    );
                }
            }
        }

        // Wait for refreshes in Folia
        if (isFolia) {
            try {
                CompletableFuture.allOf(refreshFutures.toArray(new CompletableFuture<?>[0])).get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.severe("Failed to complete chunk refresh: " + e.getMessage());
            }
        }

        LOGGER.info("Finished copying and refreshing chunks to world");
    }

    private void createSource() {
        source = new SingleThreadQueueExtent(
            originalBukkitWorld.getMinHeight(),
            originalBukkitWorld.getMaxHeight()
        );
        source.init(target, initSourceQueueCache(), null);
    }

    @Override
    protected void cleanup() {
        if (isFolia && foliaScheduledTask != null) {
            foliaScheduledTask.cancel();
            foliaScheduledTask = null;
        }
        LOGGER.info("Direct regeneration cleanup completed");
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return new ChunkCache<>(BukkitAdapter.adapt(originalBukkitWorld));
    }
}
package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_8.regen;

import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Lifecycle;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.adapter.Refraction;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.generator.BiomeProvider;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static net.minecraft.core.registries.Registries.BIOME;

public class PaperweightRegen extends Regenerator {

    private static final Logger LOGGER = Logger.getLogger(PaperweightRegen.class.getName());

    private static final Field serverWorldsField;
    private static final Field paperConfigField;
    private static final Field generatorSettingBaseSupplierField;

    static {
        try {
            serverWorldsField = CraftServer.class.getDeclaredField("worlds");
            serverWorldsField.setAccessible(true);

            Field tmpPaperConfigField;
            try {
                tmpPaperConfigField = Level.class.getDeclaredField("paperConfig");
                tmpPaperConfigField.setAccessible(true);
            } catch (Exception e) {
                tmpPaperConfigField = null;
            }
            paperConfigField = tmpPaperConfigField;

            generatorSettingBaseSupplierField = NoiseBasedChunkGenerator.class.getDeclaredField(Refraction.pickName(
                    "settings", "e"));
            generatorSettingBaseSupplierField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ServerLevel originalServerWorld;
    private ServerLevel freshWorld;
    private LevelStorageSource.LevelStorageAccess session;
    private Path tempDir;

    public PaperweightRegen(
            World originalBukkitWorld,
            Region region,
            Extent target,
            RegenOptions options
    ) {
        super(originalBukkitWorld, region, target, options);
    }

    @Override
    protected void runTasks(final BooleanSupplier shouldKeepTicking) {
        boolean isFolia;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }

        if (isFolia) {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
            if (plugin == null) {
                LOGGER.severe("WorldEdit plugin not found, cannot schedule tasks");
                return;
            }

            net.minecraft.server.level.ServerChunkCache chunkSource = this.freshWorld.getChunkSource();
            final int tasksPerTick = 10;

            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> {
                if (!shouldKeepTicking.getAsBoolean()) {
                    scheduledTask.cancel();
                    return;
                }
                for (int i = 0; i < tasksPerTick; i++) {
                    if (!chunkSource.runDistanceManagerUpdates()) {
                        scheduledTask.cancel();
                        return;
                    }
                }
            }, 1L, 1L);
        } else {
            while (shouldKeepTicking.getAsBoolean()) {
                if (!this.freshWorld.getChunkSource().pollTask()) {
                    return;
                }
            }
        }
    }

    @Override
    protected boolean prepare() {
        this.originalServerWorld = ((CraftWorld) originalBukkitWorld).getHandle();
        seed = options.getSeed().orElse(originalServerWorld.getSeed());
        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory("FastAsyncWorldEditWorldGen");

        org.bukkit.World.Environment environment = originalBukkitWorld.getEnvironment();
        org.bukkit.generator.ChunkGenerator generator = originalBukkitWorld.getGenerator();
        LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(tempDir);
        ResourceKey<LevelStem> levelStemResourceKey = getWorldDimKey(environment);
        session = levelStorageSource.createAccess("faweregentempworld", levelStemResourceKey);
        PrimaryLevelData originalWorldData = originalServerWorld.serverLevelData;

        MinecraftServer server = originalServerWorld.getCraftServer().getServer();
        WorldOptions originalOpts = originalWorldData.worldGenOptions();
        WorldOptions newOpts = options.getSeed().isPresent()
                ? originalOpts.withSeed(OptionalLong.of(seed))
                : originalOpts;
        LevelSettings newWorldSettings = new LevelSettings(
                "faweregentempworld",
                originalWorldData.settings.gameType(),
                originalWorldData.settings.hardcore(),
                originalWorldData.settings.difficulty(),
                originalWorldData.settings.allowCommands(),
                originalWorldData.settings.gameRules(),
                originalWorldData.settings.getDataConfiguration()
        );

        PrimaryLevelData.SpecialWorldProperty specialWorldProperty =
                originalWorldData.isFlatWorld()
                        ? PrimaryLevelData.SpecialWorldProperty.FLAT
                        : originalWorldData.isDebugWorld()
                        ? PrimaryLevelData.SpecialWorldProperty.DEBUG
                        : PrimaryLevelData.SpecialWorldProperty.NONE;

        // Set a safe default spawn position
        final net.minecraft.core.BlockPos spawnPos = originalServerWorld.getSharedSpawnPos() != null ?
            originalServerWorld.getSharedSpawnPos() : new net.minecraft.core.BlockPos(0, 64, 0);
        final net.minecraft.world.level.ChunkPos spawnChunkPos = new ChunkPos(spawnPos);
        if (spawnPos.getY() == 0) {
            LOGGER.warning("Original world spawn position is null or invalid, using default (0, 64, 0)");
        }

        // Create PrimaryLevelData and set spawn position
        PrimaryLevelData newWorldData = new PrimaryLevelData(newWorldSettings, newOpts, specialWorldProperty, Lifecycle.stable());
        newWorldData.setSpawn(spawnPos, 0.0f);
        LOGGER.info("Set spawn position on PrimaryLevelData: " + spawnPos);

        final PrimaryLevelData finalWorldData = newWorldData;
        BiomeProvider biomeProvider = getBiomeProvider();

        freshWorld = Fawe.instance().getQueueHandler().sync((Supplier<ServerLevel>) () -> {
            try {
                ServerLevel world = new ServerLevel(
                        server,
                        server.executor,
                        session,
                        finalWorldData,
                        originalServerWorld.dimension(),
                        new LevelStem(
                                originalServerWorld.dimensionTypeRegistration(),
                                originalServerWorld.getChunkSource().getGenerator()
                        ),
                        new RegenNoOpWorldLoadListener(),
                        originalServerWorld.isDebug(),
                        seed,
                        ImmutableList.of(),
                        false,
                        originalServerWorld.getRandomSequences(),
                        environment,
                        generator,
                        biomeProvider
                ) {
                    private final Holder<Biome> singleBiome = options.hasBiomeType() ? DedicatedServer.getServer().registryAccess()
                            .lookupOrThrow(BIOME).asHolderIdMap().byIdOrThrow(
                                    WorldEditPlugin.getInstance().getBukkitImplAdapter().getInternalBiomeId(options.getBiomeType())
                            ) : null;

                    @Override
                    public @Nonnull Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
                        if (options.hasBiomeType()) {
                            return singleBiome;
                        }
                        return super.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
                    }

                    @Override
                    public void save(
                            final ProgressListener progressListener,
                            final boolean flush,
                            final boolean savingDisabled
                    ) {
                        // noop, spigot
                    }

                    @Override
                    public void save(
                            final ProgressListener progressListener,
                            final boolean flush,
                            final boolean savingDisabled,
                            final boolean close
                    ) {
                        // noop, paper
                    }

                    @Override
                    public net.minecraft.core.BlockPos getSharedSpawnPos() {
                        net.minecraft.core.BlockPos pos = super.getSharedSpawnPos();
                        if (pos == null) {
                            LOGGER.warning("getSharedSpawnPos returned null, returning default spawn position");
                            return new net.minecraft.core.BlockPos(0, 64, 0);
                        }
                        return pos;
                    }

                    @Override
                    public void setDefaultSpawnPos(net.minecraft.core.BlockPos pos, float angle) {
                        try {
                            super.setDefaultSpawnPos(pos, angle);
                            LOGGER.info("Successfully set default spawn position on ServerLevel: " + pos);
                        } catch (Exception e) {
                            LOGGER.warning("Failed to set default spawn position: " + e.getMessage());
                            setSpawnViaReflection(this, pos, spawnChunkPos);
                        }
                    }
                };

                // Set ChunkPos via reflection to ensure compatibility
                setSpawnViaReflection(world, spawnPos, spawnChunkPos);

                // Schedule post-initialization spawn fix for Folia
                boolean isFolia;
                try {
                    Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                    isFolia = true;
                } catch (ClassNotFoundException e) {
                    isFolia = false;
                }
                if (isFolia) {
                    org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
                    if (plugin != null) {
                        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                            try {
                                world.setDefaultSpawnPos(spawnPos, 0.0f);
                                LOGGER.info("Post-initialization spawn set on ServerLevel: " + spawnPos);
                            } catch (Exception e) {
                                LOGGER.warning("Failed to set post-initialization spawn: " + e.getMessage());
                                setSpawnViaReflection(world, spawnPos, spawnChunkPos);
                            }
                        });
                    }
                }

                // Verify spawn position after world creation
                net.minecraft.core.BlockPos currentSpawn = world.getSharedSpawnPos();
                if (currentSpawn == null) {
                    LOGGER.warning("World spawn position is null after creation, forcing default spawn");
                    world.setDefaultSpawnPos(spawnPos, 0.0f);
                } else {
                    LOGGER.info("World spawn position verified: " + currentSpawn);
                }

                return world;
            } catch (Exception e) {
                LOGGER.severe("Failed to initialize temporary world: " + e.getMessage());
                throw new RuntimeException("World initialization failed", e);
            }
        }).get();

        freshWorld.noSave = true;
        removeWorldFromWorldsMap();
        newWorldData.checkName(originalServerWorld.serverLevelData.getLevelName());
        if (paperConfigField != null) {
            paperConfigField.set(freshWorld, originalServerWorld.paperConfig());
        }

        return true;
    }

    // Helper method to set spawn position and ChunkPos via reflection
    private void setSpawnViaReflection(ServerLevel world, net.minecraft.core.BlockPos pos, ChunkPos chunkPos) {
        try {
            // Try to find and set ChunkPos field in ServerLevel or its hierarchy
            Class<?> clazz = world.getClass();
            boolean found = false;
            while (clazz != null && !found) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (ChunkPos.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        if (field.get(world) == null) {
                            field.set(world, chunkPos);
                            LOGGER.info("Successfully set ChunkPos field '" + field.getName() + "' to " + chunkPos);
                            found = true;
                            break;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
            if (!found) {
                LOGGER.warning("Could not find ChunkPos field in ServerLevel hierarchy");
            }

            // Also try to set BlockPos field for completeness
            clazz = world.getClass();
            found = false;
            while (clazz != null && !found) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (net.minecraft.core.BlockPos.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        if (field.get(world) == null || ((net.minecraft.core.BlockPos) field.get(world)).getY() < 0) {
                            field.set(world, pos);
                            LOGGER.info("Successfully set BlockPos field '" + field.getName() + "' to " + pos);
                            found = true;
                            break;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
            if (!found) {
                LOGGER.warning("Could not find BlockPos field in ServerLevel hierarchy");
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to set spawn position via reflection: " + e.getMessage());
        }
    }

    @Override
    protected void cleanup() {
        try {
            session.close();
        } catch (Exception ignored) {
        }

        try {
            Fawe.instance().getQueueHandler().sync(() -> {
                try {
                    freshWorld.getChunkSource().getDataStorage().cache.clear();
                    freshWorld.getChunkSource().close(false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception ignored) {
        }

        try {
            Fawe.instance().getQueueHandler().sync(this::removeWorldFromWorldsMap);
        } catch (Exception ignored) {
        }

        try {
            SafeFiles.tryHardToDeleteDir(tempDir);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return new ChunkCache<>(BukkitAdapter.adapt(freshWorld.getWorld()));
    }

    @SuppressWarnings("unchecked")
    private void removeWorldFromWorldsMap() {
        try {
            Map<String, org.bukkit.World> map = (Map<String, org.bukkit.World>) serverWorldsField.get(Bukkit.getServer());
            map.remove("faweregentempworld");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private ResourceKey<LevelStem> getWorldDimKey(org.bukkit.World.Environment env) {
        return switch (env) {
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

    private static class RegenNoOpWorldLoadListener implements ChunkProgressListener {
        @Override
        public void updateSpawnPos(@Nonnull ChunkPos spawnPos) {
        }

        @Override
        public void onStatusChange(
                final @Nonnull ChunkPos pos,
                @org.jetbrains.annotations.Nullable final net.minecraft.world.level.chunk.status.ChunkStatus status
        ) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }
}
/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit;

import com.fastasyncworldedit.core.util.FoliaSupport;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.entity.metadata.EntityProperties;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.NullWorld;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An adapter to adapt a Bukkit entity into a WorldEdit one.
 */
//FAWE start - made class public
public class BukkitEntity implements Entity {
//FAWE end

    private final WeakReference<org.bukkit.entity.Entity> entityRef;
    //FAWE start
    private final EntityType type;
    //FAWE end

    /**
     * Create a new instance.
     *
     * @param entity the entity
     */
    public BukkitEntity(org.bukkit.entity.Entity entity) {
        checkNotNull(entity);
        //FAWE start
        this.type = entity.getType();
        //FAWE end
        this.entityRef = new WeakReference<>(entity);
    }

    @Override
    public Extent getExtent() {
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null) {
            return BukkitAdapter.adapt(entity.getWorld());
        } else {
            return NullWorld.getInstance();
        }
    }

    @Override
    public Location getLocation() {
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null) {
            return onEntityThread(entity, () -> BukkitAdapter.adapt(entity.getLocation()), new Location(NullWorld.getInstance()));
        } else {
            return new Location(NullWorld.getInstance());
        }
    }

    @Override
    public boolean setLocation(Location location) {
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null) {
            return onEntityThread(entity, () -> entity.teleport(BukkitAdapter.adapt(location)), false);
        } else {
            return false;
        }
    }

    @Override
    public BaseEntity getState() {
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null) {
            if (entity instanceof Player) {
                return null;
            }

            BukkitImplAdapter adapter = WorldEditPlugin.getInstance().getBukkitImplAdapter();
            if (adapter != null) {
                return onEntityThread(entity, () -> adapter.getEntity(entity), null);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean remove() {
        // synchronize the whole method, not just the remove operation as we always need to synchronize and
        // can make sure the entity reference was not invalidated in the few milliseconds between the next available tick (lol)
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null) {
            return onEntityThread(entity, () -> {
                try {
                    entity.remove();
                } catch (UnsupportedOperationException e) {
                    return false;
                }
                return entity.isDead();
            }, false);
        } else {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        org.bukkit.entity.Entity entity = entityRef.get();
        if (entity != null && EntityProperties.class.isAssignableFrom(cls)) {
            return (T) new BukkitEntityProperties(entity);
        } else {
            return null;
        }
    }

    private <T> T onEntityThread(org.bukkit.entity.Entity entity, Supplier<T> supplier, T retiredValue) {
        if (!FoliaSupport.isFolia() || Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            entity.getScheduler().execute(
                    WorldEditPlugin.getInstance(),
                    () -> {
                        try {
                            future.complete(supplier.get());
                        } catch (Throwable e) {
                            future.completeExceptionally(e);
                        }
                    },
                    () -> future.complete(retiredValue),
                    0
            );
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return retiredValue;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}

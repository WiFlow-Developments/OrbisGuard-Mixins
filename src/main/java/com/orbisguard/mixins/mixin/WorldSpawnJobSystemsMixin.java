package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.spawning.world.system.WorldSpawnJobSystems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to intercept world spawn job spawning and check region protection.
 */
@Mixin(WorldSpawnJobSystems.class)
public class WorldSpawnJobSystemsMixin {

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";

    @Unique
    private static final int SPAWN_SLOT = 8;

    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    /**
     * Redirect the canSpawn() call in trySpawn(), the only spawn gate for world spawn jobs.
     */
    @Redirect(
        method = "trySpawn",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/spawning/SpawningContext;canSpawn()Lcom/hypixel/hytale/server/spawning/SpawnTestResult;"
        )
    )
    private static SpawnTestResult redirectCanSpawnNoArg(SpawningContext context) {
        if (shouldBlockSpawn(context)) {
            return SpawnTestResult.FAIL_INVALID_POSITION;
        }
        return context.canSpawn();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    @Unique
    private static boolean shouldBlockSpawn(SpawningContext context) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null || bridge.get(SPAWN_SLOT) == null) {
                // No bridge or hook - allow spawns
                return false;
            }

            Object hook = bridge.get(SPAWN_SLOT);
            World world = context.getWorld();
            if (world == null) {
                return false;
            }
            String worldName = world.getName();

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (WorldSpawnJobSystemsMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        // Use primitive boolean, not Boolean object
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldBlockSpawn",
                            MethodType.methodType(boolean.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            int x = (int) Math.floor(context.xSpawn);
            int y = (int) Math.floor(context.ySpawn);
            int z = (int) Math.floor(context.zSpawn);

            // Invoke returns primitive boolean
            boolean blocked = (boolean) cachedCheckHandle.invoke(hook, worldName, x, y, z);
            return blocked;
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                System.err.println("[OrbisGuard-Mixins] Hook error #" + count + ": " + e.getMessage());
            }
            // On error, allow spawns (fail-open)
            return false;
        }
    }
}

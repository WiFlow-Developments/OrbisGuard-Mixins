package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import com.hypixel.hytale.server.core.universe.world.World;
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
 * Mixin to intercept spawn marker spawning and check region protection.
 * Also suppresses the "Marker removed due to repeated spawning fails" log spam.
 */
@Mixin(SpawnMarkerEntity.class)
public class SpawnMarkerEntityMixin {

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

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    /**
     * Redirect the context.canSpawn(true, false) call in spawnNPC().
     * If region protection blocks, return FAIL_INVALID_POSITION to prevent spawn.
     */
    @Redirect(
        method = "spawnNPC",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/spawning/SpawningContext;canSpawn(ZZ)Lcom/hypixel/hytale/server/spawning/SpawnTestResult;"
        )
    )
    private SpawnTestResult redirectCanSpawn(SpawningContext context, boolean testOverlapBlocks, boolean testOverlapEntities) {
        // Check region protection
        if (shouldBlockSpawn(context)) {
            return SpawnTestResult.FAIL_INVALID_POSITION;
        }

        // Allow - call original
        return context.canSpawn(testOverlapBlocks, testOverlapEntities);
    }

    /**
     * Suppress the "Marker removed due to repeated spawning fails" log in fail() method.
     * Only suppress when spawn protection hook is active.
     */
    @Redirect(
        method = "fail",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger$Api;log(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void suppressFailLog(HytaleLogger.Api logger, String message, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        // Only suppress if spawn protection hook is active
        AtomicReferenceArray<Object> bridge = getBridge();
        if (bridge == null || bridge.get(SPAWN_SLOT) == null) {
            // No hook - let the original log through
            logger.log(message, arg1, arg2, arg3, arg4, arg5);
        }
        // Hook active - suppress the log
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
                synchronized (SpawnMarkerEntityMixin.class) {
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

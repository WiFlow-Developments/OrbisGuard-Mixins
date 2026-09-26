package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to intercept ALL NPC spawns at the final convergence point.
 * Redirects store.addEntity() in NPCPlugin.spawnEntity() to block spawns.
 */
@Mixin(NPCPlugin.class)
public class NPCPluginSpawnMixin {

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
     * Redirect the store.addEntity() call in spawnEntity() to check region protection.
     * This catches ALL NPC spawns that go through NPCPlugin.spawnEntity().
     */
    @Redirect(
        method = "spawnEntity(Lcom/hypixel/hytale/component/Store;ILorg/joml/Vector3dc;Lcom/hypixel/hytale/math/vector/Rotation3fc;Lcom/hypixel/hytale/server/core/asset/type/model/config/Model;Lcom/hypixel/hytale/function/consumer/TriConsumer;Lcom/hypixel/hytale/function/consumer/TriConsumer;)Lit/unimi/dsi/fastutil/Pair;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        )
    )
    private Ref<EntityStore> redirectAddEntity(Store<EntityStore> store, Holder<EntityStore> holder, AddReason reason) {
        // Get position from the holder's TransformComponent
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform != null) {
            Vector3d position = transform.getPosition();
            String worldName = getWorldName(store);

            if (worldName != null && shouldBlockSpawn(worldName, position)) {
                // Return null to block spawn - NPCPlugin handles this
                return null;
            }
        }

        // Allow spawn - call original method
        return store.addEntity(holder, reason);
    }

    @Unique
    private static String getWorldName(Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld().getName();
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static boolean shouldBlockSpawn(String worldName, Vector3d position) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) {
                // No bridge yet - allow spawns (permissive during startup)
                return false;
            }

            Object hook = bridge.get(SPAWN_SLOT);
            if (hook == null) {
                // Bridge exists but no hook - allow spawns
                return false;
            }

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (NPCPluginSpawnMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        // Use primitive boolean, not Boolean object
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldBlockSpawn",
                            MethodType.methodType(boolean.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            int x = (int) Math.floor(position.x);
            int y = (int) Math.floor(position.y);
            int z = (int) Math.floor(position.z);

            // Invoke returns primitive boolean
            boolean blocked = (boolean) cachedCheckHandle.invoke(hook, worldName, x, y, z);
            return blocked;
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                System.err.println("[OrbisGuard-Mixins] Hook error #" + count + ": " + e.getMessage());
            }
            // On error, allow spawns (fail-open for safety)
            return false;
        }
    }
}

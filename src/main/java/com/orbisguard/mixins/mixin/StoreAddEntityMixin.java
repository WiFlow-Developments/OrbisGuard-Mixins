package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to intercept Store.addEntity() to block NPC spawns from prefabs.
 * This catches entities added with AddReason.LOAD (prefab entities).
 * Uses @Redirect instead of @Inject to avoid ClassNotFoundException on world thread.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(Store.class)
public class StoreAddEntityMixin {

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
     * Redirect the inner addEntity call to check for prefab NPCs.
     * addEntity(Holder, AddReason) calls addEntity(Holder, Ref, AddReason) internally.
     */
    @Redirect(
        method = "addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        )
    )
    private Ref redirectAddEntity(Store store, Holder holder, Ref ref, AddReason reason) {
        // Only intercept LOAD reason (prefab entities)
        if (reason == AddReason.LOAD) {
            // Check if this is for EntityStore
            Object externalData = store.getExternalData();
            if (externalData instanceof EntityStore entityStore) {
                // Check if this is an NPC entity
                try {
                    // Cast to raw Holder to work around generic type issues
                    @SuppressWarnings("unchecked")
                    Holder<EntityStore> entityHolder = (Holder<EntityStore>) (Object) holder;

                    NPCEntity npcComponent = entityHolder.getComponent(NPCEntity.getComponentType());
                    if (npcComponent != null) {
                        // Get position from transform
                        TransformComponent transform = entityHolder.getComponent(TransformComponent.getComponentType());
                        if (transform != null) {
                            Vector3d pos = transform.getPosition();
                            World world = entityStore.getWorld();
                            String worldName = world != null ? world.getName() : "default";

                            if (shouldBlockPrefabSpawn(worldName, pos)) {
                                return null; // Block the spawn
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore errors - allow the entity
                }
            }
        }

        // Allow - call original
        return store.addEntity(holder, ref, reason);
    }

    @Unique
    private static boolean shouldBlockPrefabSpawn(String worldName, Vector3d position) {
        int x = (int) Math.floor(position.x);
        int y = (int) Math.floor(position.y);
        int z = (int) Math.floor(position.z);

        // First try the hook (faster path when available)
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge != null) {
                Object hook = bridge.get(SPAWN_SLOT);
                if (hook != null) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        synchronized (StoreAddEntityMixin.class) {
                            if (cachedBridge != bridge || cachedCheckHandle == null) {
                                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                                cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldBlockSpawn",
                                    MethodType.methodType(boolean.class, String.class, int.class, int.class, int.class));
                                cachedBridge = bridge;
                            }
                        }
                    }
                    return (boolean) cachedCheckHandle.invoke(hook, worldName, x, y, z);
                }
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                System.err.println("[OrbisGuard-Mixins] Hook error #" + count + ": " + e.getMessage());
            }
        }

        // Fallback: try accessing OrbisGuardAPI directly via reflection
        return checkViaApiReflection(worldName, x, y, z);
    }

    @Unique
    private static Method apiGetInstance = null;
    @Unique
    private static Method apiGetRegionContainer = null;
    @Unique
    private static Method containerGetRegionsAt = null;
    @Unique
    private static Method regionGetId = null;
    @Unique
    private static AtomicBoolean apiReflectionFailed = new AtomicBoolean(false);

    @Unique
    private static boolean checkViaApiReflection(String worldName, int x, int y, int z) {
        // API reflection fallback - only block if we can confirm a protected region
        // During early startup, allow spawns to prevent Store state issues
        if (apiReflectionFailed.get()) {
            return false;
        }

        try {
            // Lazy init reflection methods
            if (apiGetInstance == null) {
                Class<?> apiClass = Class.forName("com.orbisguard.api.OrbisGuardAPI");
                apiGetInstance = apiClass.getMethod("getInstance");
                apiGetRegionContainer = apiClass.getMethod("getRegionContainer");
                Class<?> containerClass = Class.forName("com.orbisguard.api.region.IRegionContainer");
                containerGetRegionsAt = containerClass.getMethod("getRegionsAt", String.class, int.class, int.class, int.class);
                Class<?> regionClass = Class.forName("com.orbisguard.api.region.IRegion");
                regionGetId = regionClass.getMethod("getId");
            }

            Object api = apiGetInstance.invoke(null);
            if (api == null) {
                // API not initialized yet - allow spawn to prevent Store issues
                return false;
            }

            Object container = apiGetRegionContainer.invoke(api);
            if (container == null) {
                return false;
            }

            Object regions = containerGetRegionsAt.invoke(container, worldName, x, y, z);
            if (regions instanceof java.util.Set<?> regionSet) {
                for (Object region : regionSet) {
                    String id = (String) regionGetId.invoke(region);
                    if (id != null && !id.equalsIgnoreCase("__global__")) {
                        // Found a non-global region - block spawn
                        return true;
                    }
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            // OrbisGuard API not loaded yet
            return false;
        } catch (Exception e) {
            apiReflectionFailed.set(true);
            return false;
        }
    }
}

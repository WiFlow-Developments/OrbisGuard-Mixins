package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialStructure;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerItemEntityPickupSystem;
import com.orbisguard.mixins.ProtectionBridge;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(PlayerItemEntityPickupSystem.class)
public abstract class PlayerItemEntityPickupSystemMixin {

    static {
        ProtectionBridge.publishMixinMetadata();
        System.setProperty("orbisguard.mixin.pickup.loaded", "true");
        ProtectionBridge.setPickupMixinLoaded(true);
    }

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int PICKUP_SLOT = 0;

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    // Captured item position per tick
    @Unique
    private static final ThreadLocal<Vector3d> itemPosition = new ThreadLocal<>();

    // Store reference captured at tick HEAD for use in the ordered() redirect
    @Unique
    private static final ThreadLocal<Store<EntityStore>> tickStore = new ThreadLocal<>();

    // Cached MethodHandle for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    /**
     * Capture store by redirecting the store.getResource() call in tick.
     * Uses @Redirect instead of @Inject to avoid CallbackInfo ClassNotFoundException
     * on the TransformingClassLoader.
     */
    @SuppressWarnings("unchecked")
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;getResource(Lcom/hypixel/hytale/component/ResourceType;)Lcom/hypixel/hytale/component/Resource;"
        )
    )
    private <R extends Resource<EntityStore>> R captureStore(Store<EntityStore> store, ResourceType<EntityStore, R> resourceType) {
        tickStore.set(store);
        return store.getResource(resourceType);
    }

    // Capture item position from first getPosition() call in tick()
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/entity/component/TransformComponent;getPosition()Lorg/joml/Vector3d;", ordinal = 0)
    )
    private Vector3d captureItemPosition(TransformComponent transformComponent) {
        Vector3d position = transformComponent.getPosition();
        itemPosition.set(position);
        return position;
    }

    // Intercept ordered() to drop denied players from both pickup paths (Pickup interaction
    // chain and direct giveItem). Since 0.6.8 both paths query nearby players via ordered().
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/spatial/SpatialStructure;ordered(Lorg/joml/Vector3dc;DLjava/util/List;)V")
    )
    private void filterOrderedPickup(SpatialStructure spatialStructure, Vector3dc position, double radius, List results) {
        spatialStructure.ordered(position, radius, results);

        Vector3d itemPos = itemPosition.get();
        if (itemPos == null || results.isEmpty()) return;

        Store<EntityStore> store = tickStore.get();
        if (store == null) return;

        String worldName = null;
        if (store.getExternalData() != null && store.getExternalData().getWorld() != null) {
            worldName = store.getExternalData().getWorld().getName();
        }
        if (worldName == null) return;

        // Iterate backwards for safe removal
        for (int i = results.size() - 1; i >= 0; i--) {
            try {
                Ref<EntityStore> ref = (Ref<EntityStore>) results.get(i);
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) continue;

                if (playerRef.hasPermission("orbisguard.bypass")) continue;

                if (!isPickupAllowed(playerRef.getUuid(), worldName, itemPos.x, itemPos.y, itemPos.z)) {
                    results.remove(i);
                }
            } catch (Exception e) {
                // Fail-open: leave player in list on error
            }
        }
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
    private static boolean isPickupAllowed(UUID playerUuid, String worldName, double x, double y, double z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return true;

            Object hook = bridge.get(PICKUP_SLOT);
            if (hook == null) return true;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (PlayerItemEntityPickupSystemMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "check",
                            MethodType.methodType(boolean.class, UUID.class, String.class,
                                double.class, double.class, double.class, String.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z, "AUTO");
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard pickup hook error #" + count, e);
            }
        }
        return true;
    }
}

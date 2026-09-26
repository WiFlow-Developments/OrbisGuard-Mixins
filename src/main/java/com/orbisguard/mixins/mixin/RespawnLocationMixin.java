package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.gameplay.respawn.HomeOrSpawnPoint;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.orbisguard.mixins.ProtectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(HomeOrSpawnPoint.class)
public abstract class RespawnLocationMixin {

    static {
        ProtectionBridge.publishMixinMetadata();
        ProtectionBridge.setRespawnMixinLoaded(true);
    }

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int RESPAWN_SLOT = 4;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    // Cached MethodHandle for hook invocation with bridge identity check
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
     * Redirects static Player.getRespawnPosition to check for custom region respawn location.
     * For static methods, the redirect must also be static.
     * Note: getRespawnPosition now returns CompletableFuture<Transform> in newer server versions.
     */
    @Redirect(
        method = "respawnPlayer",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/entity/entities/Player;getRespawnPosition(Lcom/hypixel/hytale/component/Ref;Ljava/lang/String;Lcom/hypixel/hytale/component/ComponentAccessor;)Ljava/util/concurrent/CompletableFuture;")
    )
    private static java.util.concurrent.CompletableFuture<Transform> redirectGetRespawnPosition(
            // Params of the static method being redirected (Player.getRespawnPosition)
            Ref<EntityStore> playerReference, String worldName, ComponentAccessor<EntityStore> componentAccessor,
            // Params of the enclosing method (respawnPlayer)
            World world, Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> enclosingAccessor) {

        // Get player UUID and position for flag check
        UUID playerUuid = null;
        int x = 0, y = 0, z = 0;

        try {
            PlayerRef pRef = componentAccessor.getComponent(playerReference, PlayerRef.getComponentType());
            if (pRef != null) {
                playerUuid = pRef.getUuid();
                // Get player's death position from transform
                TransformComponent transform = componentAccessor.getComponent(playerReference, TransformComponent.getComponentType());
                if (transform != null) {
                    x = (int) Math.floor(transform.getPosition().x);
                    y = (int) Math.floor(transform.getPosition().y);
                    z = (int) Math.floor(transform.getPosition().z);
                }
            }
        } catch (Exception e) {
            // Fall through to default behavior
        }

        // Check for custom respawn location
        double[] customLocation = getCustomRespawnLocation(playerUuid, worldName, x, y, z);
        if (customLocation != null) {
            // customLocation format: [x, y, z, yaw, pitch] or [x, y, z]
            float pitch = customLocation.length > 4 ? (float) customLocation[4] : 0f;
            float yaw = customLocation.length > 3 ? (float) customLocation[3] : 0f;

            // Transform constructor: (x, y, z, pitch, yaw, roll)
            Transform customTransform = new Transform(customLocation[0], customLocation[1], customLocation[2], pitch, yaw, 0f);
            return java.util.concurrent.CompletableFuture.completedFuture(customTransform);
        }

        // Fall back to default behavior
        return Player.getRespawnPosition(playerReference, worldName, componentAccessor);
    }

    @Unique
    private static double[] getCustomRespawnLocation(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(RESPAWN_SLOT);
            if (hook == null) return null;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (RespawnLocationMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "getRespawnLocation",
                            MethodType.methodType(double[].class, UUID.class, String.class,
                                int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (double[]) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard respawn hook error #" + count, e);
            }
        }
        return null;
    }
}

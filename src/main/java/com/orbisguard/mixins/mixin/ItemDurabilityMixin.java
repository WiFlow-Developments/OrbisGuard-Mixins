package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.orbisguard.mixins.ProtectionBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(ItemUtils.class)
public abstract class ItemDurabilityMixin {

    static {
        ProtectionBridge.publishMixinMetadata();
        System.setProperty("orbisguard.mixin.durability.loaded", "true");
        ProtectionBridge.setDurabilityMixinLoaded(true);
    }

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int DURABILITY_SLOT = 5;
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
     * @author OrbisGuard
     * @reason Prevent item durability loss in protected regions. Wraps the vanilla
     *         durability check: the null-player / Creative-mode decision is delegated to
     *         vanilla, and the region guard is only applied when vanilla would allow the loss.
     */
    @WrapMethod(method = "canDecreaseItemStackDurability")
    private static boolean orbisguard$wrapCanDecreaseItemStackDurability(@Nonnull Ref<EntityStore> ref,
                                                         @Nonnull ComponentAccessor<EntityStore> componentAccessor,
                                                         @Nonnull Operation<Boolean> original) {
        if (!original.call(ref, componentAccessor)) {
            return false;
        }

        // Vanilla allows durability loss; apply OrbisGuard region protection.
        try {
            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
                if (transform != null) {
                    UUID playerUuid = playerRef.getUuid();
                    String worldName = componentAccessor.getExternalData().getWorld().getName();
                    Vector3d pos = transform.getPosition();
                    int x = (int) Math.floor(pos.x);
                    int y = (int) Math.floor(pos.y);
                    int z = (int) Math.floor(pos.z);

                    if (shouldPreventDurabilityLoss(playerUuid, worldName, x, y, z)) {
                        return false;
                    }
                }
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }

        return true;
    }

    @Unique
    private static boolean shouldPreventDurabilityLoss(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;
            Object hook = bridge.get(DURABILITY_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (ItemDurabilityMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldPreventDurabilityLoss",
                            MethodType.methodType(boolean.class, UUID.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }
        return false;
    }
}

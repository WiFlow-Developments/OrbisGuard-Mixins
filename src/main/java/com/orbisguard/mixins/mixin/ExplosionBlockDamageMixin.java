package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Intercepts the explosion path through {@code BlockHarvestUtils.performBlockDamage}.
 *
 * <p>In Update 5 (May 2026), {@code Operation#tick/simulateTick} dropped their {@code LivingEntity}
 * parameter. {@code performBlockDamage} followed: the 12-arg variant is now 11-arg (no LivingEntity),
 * and the 8-arg convenience overload now uses {@code ComponentAccessor<EntityStore>} instead of
 * {@code CommandBuffer<EntityStore>}.
 */
@Mixin(BlockHarvestUtils.class)
public class ExplosionBlockDamageMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int EXPLOSION_SLOT = 9;
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

    // Redirect from the 8-param convenience overload into the 11-param underlying call.
    // Explosions pass null ref (the convenience overload supplies null for entity-related params).
    @Redirect(
        method = "performBlockDamage(Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;FIZLcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;)Z",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;performBlockDamage(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/Ref;Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;Ljava/lang/String;ZFIZLcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;)Z")
    )
    private static boolean redirectExplosionBlockDamage(
            @Nullable Ref<EntityStore> ref,
            @Nullable Ref<EntityStore> sourceRef,
            @Nonnull Vector3i targetBlockPos,
            @Nullable ItemStack itemStack,
            @Nullable ItemTool tool,
            @Nullable String toolId,
            boolean matchTool,
            float damageScale,
            int setBlockSettings,
            boolean skipInteractions,
            @Nonnull Ref<ChunkStore> chunkReference,
            @Nonnull ComponentAccessor<EntityStore> entityStore,
            @Nonnull ComponentAccessor<ChunkStore> chunkStore) {

        if (ref == null) {
            try {
                World world = entityStore.getExternalData().getWorld();
                if (world != null && shouldBlockExplosion(world, targetBlockPos)) {
                    return false;
                }
            } catch (Throwable e) {
                long count = errorCount.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    LOGGER.log(Level.WARNING, "Hook error #" + count, e);
                }
            }
        }

        return BlockHarvestUtils.performBlockDamage(
            ref, sourceRef, targetBlockPos, itemStack, tool, toolId, matchTool,
            damageScale, setBlockSettings, skipInteractions, chunkReference, entityStore, chunkStore
        );
    }

    @Unique
    private static boolean shouldBlockExplosion(World world, Vector3i targetBlock) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;
            Object hook = bridge.get(EXPLOSION_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (ExplosionBlockDamageMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldBlockExplosion",
                            MethodType.methodType(boolean.class, World.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedCheckHandle.invoke(hook, world,
                targetBlock.x, targetBlock.y, targetBlock.z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }
        return false;
    }
}

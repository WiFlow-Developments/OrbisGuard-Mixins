package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * Mixin to intercept fire fluid spreading in protected regions.
 *
 * Fire operates as a fluid system (FireFluidTicker) that bypasses all ECS events.
 * Uses @Redirect instead of @Inject to avoid ClassNotFoundException on world thread.
 *
 * Targets FluidTicker.process() to redirect the spread() call, which gives us access
 * to the World parameter for protection checks without needing CallbackInfoReturnable.
 * Fire that spreads into protected regions is removed on the next tick.
 */
@Mixin(FluidTicker.class)
public abstract class FireFluidTickerMixin {

    @Shadow
    protected abstract BlockTickStrategy spread(
            World world, long tick, FluidTicker.Accessor accessor,
            FluidSection fluidSection, BlockSection blockSection,
            Fluid fluid, int fluidId, byte fluidLevel,
            int worldX, int worldY, int worldZ
    );

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int FIRE_SLOT = 15;

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
     * Redirect the spread() call in FluidTicker.process() to intercept fire ticking.
     * If fire is in a protected region, remove it and return SLEEP.
     * Only affects FireFluidTicker instances; other fluid types pass through unchanged.
     */
    @Redirect(
        method = "process",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker;spread(Lcom/hypixel/hytale/server/core/universe/world/World;JLcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker$Accessor;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/FluidSection;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/server/core/asset/type/fluid/Fluid;IBIII)Lcom/hypixel/hytale/server/core/asset/type/blocktick/BlockTickStrategy;"
        )
    )
    private BlockTickStrategy onSpread(FluidTicker self, World world, long tick,
                                       FluidTicker.Accessor accessor,
                                       FluidSection fluidSection, BlockSection blockSection,
                                       Fluid fluid, int fluidId, byte fluidLevel,
                                       int worldX, int worldY, int worldZ) {
        // Use class name check instead of instanceof to avoid NoClassDefFoundError
        // on release servers where FireFluidTicker doesn't exist
        if (isFireFluidTicker(self) && shouldBlockFire(world, worldX, worldY, worldZ)) {
            // Remove fire fluid and stop ticking
            fluidSection.setFluid(worldX, worldY, worldZ, 0, (byte) 0);
            return BlockTickStrategy.SLEEP;
        }
        return this.spread(world, tick, accessor, fluidSection, blockSection,
                fluid, fluidId, fluidLevel, worldX, worldY, worldZ);
    }

    @Unique
    private static boolean isFireFluidTicker(FluidTicker ticker) {
        return ticker.getClass().getSimpleName().equals("FireFluidTicker");
    }

    @Unique
    private static boolean shouldBlockFire(World world, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;
            Object hook = bridge.get(FIRE_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (FireFluidTickerMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldBlockFire",
                            MethodType.methodType(boolean.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            String worldName = world.getName();
            return (boolean) cachedCheckHandle.invoke(hook, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                System.err.println("[OrbisGuard-Mixins] Hook error #" + count + ": " + e.getMessage());
            }
            return false;
        }
    }
}

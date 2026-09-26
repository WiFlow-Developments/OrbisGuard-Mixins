package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to suppress "Attempted to store sync data" log spam when OrbisGuard blocks interactions.
 */
@Mixin(InteractionChain.class)
public class InteractionChainMixin {

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int USE_SLOT = 7;

    @Unique
    private static volatile MethodHandle cachedSuppressHandle;
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
     * Suppress the sync data log in putInteractionSyncData() when use protection is active and config allows.
     */
    @Redirect(
        method = "putInteractionSyncData",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger$Api;log(Ljava/lang/String;II)V"
        ),
        require = 0
    )
    private void suppressSyncDataLog(HytaleLogger.Api logger, String message, int arg1, int arg2) {
        if (shouldSuppressLog()) {
            return; // Suppress the log
        }
        // Let the original log through
        logger.log(message, arg1, arg2);
    }

    @Unique
    private static boolean shouldSuppressLog() {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;
            Object hook = bridge.get(USE_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedSuppressHandle == null) {
                synchronized (InteractionChainMixin.class) {
                    if (cachedBridge != bridge || cachedSuppressHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedSuppressHandle = lookup.findVirtual(hook.getClass(), "shouldSuppressDesyncLogs",
                            MethodType.methodType(boolean.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedSuppressHandle.invoke(hook);
        } catch (Throwable e) {
            return false;
        }
    }
}

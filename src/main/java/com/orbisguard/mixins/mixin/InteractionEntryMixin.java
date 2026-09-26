package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to suppress "Client/Server desync" log spam when OrbisGuard blocks interactions.
 */
@Mixin(InteractionEntry.class)
public class InteractionEntryMixin {

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";

    @Unique
    private static final int USE_SLOT = 7;

    @Unique
    private static volatile MethodHandle cachedSuppressHandle;
    @Unique
    private static volatile Object cachedBridge;

    /**
     * Suppress the desync log in setClientState() when use protection is active and config allows.
     */
    @Redirect(
        method = "setClientState",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger$Api;log(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void suppressDesyncLog(HytaleLogger.Api logger, String message, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
        if (shouldSuppressLog()) {
            return; // Suppress the log
        }
        // Let the original log through
        logger.log(message, arg1, arg2, arg3, arg4, arg5, arg6);
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
    private static boolean shouldSuppressLog() {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;
            Object hook = bridge.get(USE_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedSuppressHandle == null) {
                synchronized (InteractionEntryMixin.class) {
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

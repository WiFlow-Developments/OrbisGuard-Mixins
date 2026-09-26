package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.spawning.util.FloodFillPositionSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Mixin to suppress "Spawn beacon at: ... unable to find any suitable positions to check" log spam
 * when spawn protection is active.
 */
@Mixin(FloodFillPositionSelector.class)
public class FloodFillPositionSelectorMixin {

    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";

    @Unique
    private static final int SPAWN_SLOT = 8;

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
     * Suppress the "unable to find any suitable positions" log in buildPositionCache().
     * Only suppress when spawn protection hook is active.
     */
    @Redirect(
        method = "buildPositionCache",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger$Api;log(Ljava/lang/String;Ljava/lang/Object;)V"
        ),
        require = 0
    )
    private void suppressNoPositionsLog(HytaleLogger.Api logger, String message, Object arg) {
        // The same call shape is used for the debug map dump; only the no-positions line is ours
        if (message.contains("unable to find any suitable positions")) {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge != null && bridge.get(SPAWN_SLOT) != null) {
                return; // Hook active - suppress the log
            }
        }
        logger.log(message, arg);
    }
}

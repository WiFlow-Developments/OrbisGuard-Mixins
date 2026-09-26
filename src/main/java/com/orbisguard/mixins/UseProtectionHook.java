package com.orbisguard.mixins;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Hook interface for use (block interaction) protection.
 * Registered via HookRegistry.
 */
public interface UseProtectionHook {

    /**
     * Checks if a block use interaction should be blocked.
     *
     * @param player The player attempting to use the block
     * @param playerRef The player reference
     * @param worldName The world name
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return true if the interaction should be blocked
     */
    boolean shouldBlockUse(Player player, PlayerRef playerRef, String worldName, int x, int y, int z);

    /**
     * Gets the denial message to show when use is blocked.
     */
    String getDenialMessage();

    /**
     * Returns true if interaction desync logs should be suppressed.
     * This is configurable in the OrbisGuard config.
     */
    boolean shouldSuppressDesyncLogs();

    /**
     * Registers a hook instance.
     */
    static void register(UseProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.USE_SLOT, hook);
    }

    /**
     * Unregisters the hook.
     */
    static void unregister() {
        ProtectionBridge.unregister(ProtectionBridge.USE_SLOT);
    }

    /**
     * Gets the registered hook instance.
     */
    static UseProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.USE_SLOT);
        if (hook instanceof UseProtectionHook) {
            return (UseProtectionHook) hook;
        }
        return null;
    }
}

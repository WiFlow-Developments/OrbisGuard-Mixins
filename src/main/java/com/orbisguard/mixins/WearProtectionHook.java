package com.orbisguard.mixins;

import java.util.UUID;

/**
 * Hook interface for armor/elytra equip protection.
 * Checks the WEAR flag - if denied, prevents armor and elytra equipping.
 */
public interface WearProtectionHook {

    /**
     * Checks if armor/elytra equipping should be blocked.
     * Returns denial message if blocked, null if allowed.
     *
     * @param playerUuid The player's UUID
     * @param worldName The world name
     * @param x Player X coordinate
     * @param y Player Y coordinate
     * @param z Player Z coordinate
     * @return Denial message string if blocked, null if allowed
     */
    String check(UUID playerUuid, String worldName, int x, int y, int z);

    /**
     * Gets the registered hook instance.
     */
    static WearProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.WEAR_SLOT);
        if (hook instanceof WearProtectionHook) {
            return (WearProtectionHook) hook;
        }
        return null;
    }

    /**
     * Registers a hook instance.
     */
    static void register(WearProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.WEAR_SLOT, hook);
    }
}

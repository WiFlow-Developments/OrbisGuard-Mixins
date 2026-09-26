package com.orbisguard.mixins;

import java.util.UUID;

/** Hook interface for pickup protection. */
public final class PickupProtectionHook {

    /**
     * Type of item pickup action.
     */
    public enum PickupType {
        /** Automatic pickup - walking over items */
        AUTO,
        /** Manual pickup - pressing F key */
        MANUAL
    }

    @FunctionalInterface
    public interface PickupCheckHook {
        boolean isPickupAllowed(UUID playerUuid, double x, double y, double z, PickupType type);
    }

    private static volatile PickupCheckHook pickupAllowedHook = null;

    private PickupProtectionHook() {
    }

    public static void setHook(PickupCheckHook hook) {
        pickupAllowedHook = hook;
    }

    public static boolean isPickupAllowed(UUID playerUuid, double x, double y, double z, PickupType type) {
        PickupCheckHook hook = pickupAllowedHook;
        if (hook == null) return true;
        return hook.isPickupAllowed(playerUuid, x, y, z, type);
    }
}

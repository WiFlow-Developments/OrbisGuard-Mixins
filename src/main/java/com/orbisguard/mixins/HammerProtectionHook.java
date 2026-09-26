package com.orbisguard.mixins;

import java.util.UUID;

/** Hook interface for hammer protection. */
public final class HammerProtectionHook {

    @FunctionalInterface
    public interface HammerCheckHook {
        boolean isHammerAllowed(UUID playerUuid, String worldName, int x, int y, int z);
    }

    private static volatile HammerCheckHook hammerAllowedHook = null;

    private HammerProtectionHook() {
    }

    public static void setHook(HammerCheckHook hook) {
        hammerAllowedHook = hook;
    }

    public static boolean isHammerAllowed(UUID playerUuid, String worldName, int x, int y, int z) {
        HammerCheckHook hook = hammerAllowedHook;
        if (hook == null) return true;
        return hook.isHammerAllowed(playerUuid, worldName, x, y, z);
    }
}

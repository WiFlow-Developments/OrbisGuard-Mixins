package com.orbisguard.mixins;

/**
 * Hook interface for fire/flame protection.
 * The main OrbisGuard plugin registers an implementation of this hook,
 * which is then called by the FireFluidTickerMixin to check if fire should be blocked.
 *
 * Checks the FIRE_SPREAD flag - if denied, fire spreading and block burning is blocked.
 */
public interface FireProtectionHook {

    /**
     * Checks if fire should be blocked at the given location.
     * Returns true if the FIRE_SPREAD flag is denied in any applicable region.
     *
     * @param worldName The world name
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return true if fire should be blocked, false to allow
     */
    boolean shouldBlockFire(String worldName, int x, int y, int z);

    /**
     * Gets the registered hook instance.
     */
    static FireProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.FIRE_SLOT);
        if (hook instanceof FireProtectionHook) {
            return (FireProtectionHook) hook;
        }
        return null;
    }

    /**
     * Registers a hook instance.
     */
    static void register(FireProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.FIRE_SLOT, hook);
    }
}

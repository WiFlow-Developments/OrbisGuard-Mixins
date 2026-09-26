package com.orbisguard.mixins;

/**
 * Hook interface for mob spawning protection.
 * The main OrbisGuard plugin registers an implementation of this hook,
 * which is then called by the mixin to check if mob spawning should be blocked.
 *
 * Checks the MOB_SPAWNING flag - if denied, natural mob spawns are prevented.
 */
public interface SpawnProtectionHook {

    /**
     * Checks if mob spawning should be blocked at the given location.
     * Returns true if MOB_SPAWNING flag is denied.
     *
     * @param worldName The world name
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return true if spawning should be blocked, false to allow
     */
    boolean shouldBlockSpawn(String worldName, int x, int y, int z);

    /**
     * Gets the registered hook instance.
     *
     * @return The hook instance, or null if not registered
     */
    static SpawnProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.SPAWN_SLOT);
        if (hook instanceof SpawnProtectionHook) {
            return (SpawnProtectionHook) hook;
        }
        return null;
    }

    /**
     * Registers a hook instance.
     *
     * @param hook The hook to register
     */
    static void register(SpawnProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.SPAWN_SLOT, hook);
    }
}

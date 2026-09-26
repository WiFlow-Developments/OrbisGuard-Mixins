package com.orbisguard.mixins;

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Hook interface for explosion protection.
 * The main OrbisGuard plugin registers an implementation of this hook,
 * which is then called by the mixin to check if explosion block damage should be blocked.
 *
 * Checks both BLOCK_BREAK and EXPLOSIONS flags - if either is denied, explosion is blocked.
 */
public interface ExplosionProtectionHook {

    /**
     * Checks if explosion block damage should be blocked at the given location.
     * Returns true if BLOCK_BREAK or EXPLOSIONS flag is denied.
     *
     * @param world The world
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return true if the explosion should be blocked, false to allow
     */
    boolean shouldBlockExplosion(World world, int x, int y, int z);

    /**
     * Gets the registered hook instance.
     *
     * @return The hook instance, or null if not registered
     */
    static ExplosionProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.EXPLOSION_SLOT);
        if (hook instanceof ExplosionProtectionHook) {
            return (ExplosionProtectionHook) hook;
        }
        return null;
    }

    /**
     * Registers a hook instance.
     *
     * @param hook The hook to register
     */
    static void register(ExplosionProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.EXPLOSION_SLOT, hook);
    }
}

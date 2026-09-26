package com.orbisguard.mixins;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * High-performance cross-classloader hook registry for mixin communication.
 *
 * Replaces the old ConcurrentHashMap-based HookRegistry with an AtomicReferenceArray
 * indexed by integer slots for O(1) access. Mixins use cached MethodHandles to eliminate
 * repeated reflection overhead, achieving 10-100x performance improvement.
 *
 * Architecture:
 * - System.properties stores ONE AtomicReferenceArray under "orbisguard.bridge"
 * - Integer slot constants provide O(1) access (vs HashMap string lookup)
 * - Mixins cache MethodHandles with identity checks to detect bridge reloads
 * - Enhanced error tracking: log first error + every 100th error (not every error)
 *
 * This is optimized for cross-classloader communication in Hytale where mixins
 * (earlyplugins) and plugins (mods) run in isolated classloaders.
 *
 * @since 0.8.4
 */
public final class ProtectionBridge {

    private static final String BRIDGE_KEY = "orbisguard.bridge";
    private static final int SLOT_COUNT = 32;

    public static final int BRIDGE_PROTOCOL_VERSION = 2;
    public static final String BRIDGE_CAPABILITIES = "atomic-slot-bridge,plain-object-hooks,methodhandles,hyinit";
    public static final String MAIN_PROTOCOL_KEY = "orbisguard.bridge.protocol";
    public static final String MAIN_CAPABILITIES_KEY = "orbisguard.bridge.capabilities";
    public static final String MIXINS_LOADED_KEY = "orbisguard.mixins.loaded";
    public static final String MIXINS_PROTOCOL_KEY = "orbisguard.mixins.bridge.protocol";
    public static final String MIXINS_CAPABILITIES_KEY = "orbisguard.mixins.bridge.capabilities";
    public static final String MIXINS_CLASSLOADER_KEY = "orbisguard.mixins.classloader";

    // Hook slots (0-15)
    public static final int PICKUP_SLOT = 0;
    public static final int HAMMER_SLOT = 1;
    public static final int HARVEST_SLOT = 2;
    public static final int DEATH_SLOT = 3;
    public static final int RESPAWN_SLOT = 4;
    public static final int DURABILITY_SLOT = 5;
    public static final int CLAIM_SLOT = 6;
    public static final int USE_SLOT = 7;
    public static final int SPAWN_SLOT = 8;
    public static final int EXPLOSION_SLOT = 9;
    public static final int COMMAND_SLOT = 10;
    public static final int WORKBENCH_SLOT = 11;
    public static final int BUILDERTOOLS_SLOT = 12;
    public static final int TELEPORTER_SLOT = 13;
    public static final int PORTAL_SLOT = 14;
    public static final int FIRE_SLOT = 15;
    public static final int WEAR_SLOT = 16;

    // Policy flag slots (17-31)
    public static final int SPAWN_READY_SLOT = 17;
    public static final int SPAWN_ALLOW_STARTUP_SLOT = 18;
    public static final int MIXINS_LOADED_SLOT = 19;
    public static final int MIXIN_PICKUP_LOADED_SLOT = 20;
    public static final int MIXIN_RESPAWN_LOADED_SLOT = 21;
    public static final int MIXIN_DEATH_LOADED_SLOT = 22;
    public static final int MIXIN_DURABILITY_LOADED_SLOT = 23;
    // Slots 24-31 reserved for future use

    private ProtectionBridge() {}

    /**
     * Gets the shared bridge array, creating it if necessary.
     * Uses double-checked locking for thread safety.
     */
    public static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            @SuppressWarnings("unchecked")
            AtomicReferenceArray<Object> array = (AtomicReferenceArray<Object>) bridge;
            return array;
        }

        // Create new bridge if not exists
        synchronized (ProtectionBridge.class) {
            bridge = System.getProperties().get(BRIDGE_KEY);
            if (bridge instanceof AtomicReferenceArray) {
                @SuppressWarnings("unchecked")
                AtomicReferenceArray<Object> array = (AtomicReferenceArray<Object>) bridge;
                return array;
            }

            AtomicReferenceArray<Object> newBridge = new AtomicReferenceArray<>(SLOT_COUNT);
            System.getProperties().put(BRIDGE_KEY, newBridge);
            return newBridge;
        }
    }

    /**
     * Registers a hook at the given slot.
     */
    public static void register(int slot, Object hook) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Slot index out of range: " + slot);
        }
        getBridge().set(slot, hook);
    }

    /**
     * Gets a hook from the given slot.
     */
    public static Object get(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return null;
        }
        return getBridge().get(slot);
    }

    /**
     * Checks if a hook is registered at the given slot.
     */
    public static boolean isRegistered(int slot) {
        return get(slot) != null;
    }

    /**
     * Removes a hook from the given slot.
     */
    public static void unregister(int slot) {
        if (slot >= 0 && slot < SLOT_COUNT) {
            getBridge().set(slot, null);
        }
    }

    public static void publishMixinMetadata() {
        System.setProperty(MIXINS_LOADED_KEY, "true");
        System.setProperty(MIXINS_PROTOCOL_KEY, Integer.toString(BRIDGE_PROTOCOL_VERSION));
        System.setProperty(MIXINS_CAPABILITIES_KEY, BRIDGE_CAPABILITIES);
        setMixinsLoaded(true);
    }

    // Boolean flag helpers
    private static boolean getFlag(int slot) {
        Object flag = get(slot);
        return Boolean.TRUE.equals(flag);
    }

    private static void setFlag(int slot, boolean value) {
        getBridge().set(slot, value);
    }

    // Spawn protection policy methods
    /**
     * Checks if spawn protection is ready (plugin loaded and hook registered).
     * If not ready and not in permissive mode, spawns should be blocked.
     */
    public static boolean isSpawnProtectionReady() {
        return getFlag(SPAWN_READY_SLOT);
    }

    /**
     * Checks if spawns should be allowed during startup (before plugin loads).
     * Default is false (block spawns for safety).
     */
    public static boolean isSpawnAllowedDuringStartup() {
        return getFlag(SPAWN_ALLOW_STARTUP_SLOT);
    }

    /**
     * Sets spawn protection as ready. Called by main plugin after regions load.
     */
    public static void setSpawnProtectionReady(boolean ready) {
        setFlag(SPAWN_READY_SLOT, ready);
    }

    /**
     * Sets whether spawns are allowed during startup.
     * Called by main plugin based on config.
     */
    public static void setSpawnAllowedDuringStartup(boolean allow) {
        setFlag(SPAWN_ALLOW_STARTUP_SLOT, allow);
    }

    // Mixin loaded flag methods
    public static void setMixinsLoaded(boolean loaded) {
        setFlag(MIXINS_LOADED_SLOT, loaded);
    }

    public static boolean isMixinsLoaded() {
        return getFlag(MIXINS_LOADED_SLOT);
    }

    public static void setPickupMixinLoaded(boolean loaded) {
        setFlag(MIXIN_PICKUP_LOADED_SLOT, loaded);
    }

    public static boolean isPickupMixinLoaded() {
        return getFlag(MIXIN_PICKUP_LOADED_SLOT);
    }

    public static void setRespawnMixinLoaded(boolean loaded) {
        setFlag(MIXIN_RESPAWN_LOADED_SLOT, loaded);
    }

    public static boolean isRespawnMixinLoaded() {
        return getFlag(MIXIN_RESPAWN_LOADED_SLOT);
    }

    public static void setDeathMixinLoaded(boolean loaded) {
        setFlag(MIXIN_DEATH_LOADED_SLOT, loaded);
    }

    public static boolean isDeathMixinLoaded() {
        return getFlag(MIXIN_DEATH_LOADED_SLOT);
    }

    public static void setDurabilityMixinLoaded(boolean loaded) {
        setFlag(MIXIN_DURABILITY_LOADED_SLOT, loaded);
    }

    public static boolean isDurabilityMixinLoaded() {
        return getFlag(MIXIN_DURABILITY_LOADED_SLOT);
    }
}

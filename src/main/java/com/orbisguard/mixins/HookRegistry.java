package com.orbisguard.mixins;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-classloader hook registry for mixin communication.
 *
 * @deprecated Replaced by {@link ProtectionBridge} in v0.8.4 for 10-100x performance improvement.
 *             This class remains for backward compatibility with third-party plugins.
 *             New code should use ProtectionBridge directly.
 *
 * Uses a single ConcurrentHashMap stored in System.properties under one key,
 * minimizing pollution of system properties (which expect String values).
 *
 * NOTE: This stores ONE non-String object in System.properties, which is required
 * for cross-classloader communication in Hytale where mixins (earlyplugins) and
 * plugins (mods) run in isolated classloaders. Other plugins iterating over
 * System.properties should use stringPropertyNames() or getProperty() to avoid
 * ClassCastException on this entry.
 *
 * This is the recommended pattern for cross-classloader communication in Hytale.
 */
@Deprecated
public final class HookRegistry {

    private static final String REGISTRY_KEY = "orbisguard.hook.registry";

    private HookRegistry() {}

    /**
     * Gets the shared hook registry map, creating it if necessary.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getRegistry() {
        Object registry = System.getProperties().get(REGISTRY_KEY);
        if (registry instanceof Map) {
            return (Map<String, Object>) registry;
        }

        // Create new registry if not exists
        synchronized (HookRegistry.class) {
            registry = System.getProperties().get(REGISTRY_KEY);
            if (registry instanceof Map) {
                return (Map<String, Object>) registry;
            }

            Map<String, Object> newRegistry = new ConcurrentHashMap<>();
            System.getProperties().put(REGISTRY_KEY, newRegistry);
            return newRegistry;
        }
    }

    /**
     * Registers a hook with the given key.
     * @deprecated Use {@link ProtectionBridge#register(int, Object)} instead.
     */
    @Deprecated
    public static void register(String key, Object hook) {
        int slot = getSlot(key);
        if (slot >= 0) {
            ProtectionBridge.register(slot, hook);
        } else {
            getRegistry().put(key, hook);
        }
    }

    /**
     * Gets a hook by key.
     * @deprecated Use {@link ProtectionBridge#get(int)} instead.
     */
    @Deprecated
    public static Object get(String key) {
        int slot = getSlot(key);
        if (slot >= 0) {
            return ProtectionBridge.get(slot);
        }
        return getRegistry().get(key);
    }

    /**
     * Checks if a hook is registered.
     * @deprecated Use {@link ProtectionBridge#isRegistered(int)} instead.
     */
    @Deprecated
    public static boolean isRegistered(String key) {
        int slot = getSlot(key);
        if (slot >= 0) {
            return ProtectionBridge.isRegistered(slot);
        }
        return getRegistry().containsKey(key);
    }

    /**
     * Removes a hook.
     * @deprecated Use {@link ProtectionBridge#unregister(int)} instead.
     */
    @Deprecated
    public static void unregister(String key) {
        int slot = getSlot(key);
        if (slot >= 0) {
            ProtectionBridge.unregister(slot);
        } else {
            getRegistry().remove(key);
        }
    }

    /**
     * Maps old hook key strings to new ProtectionBridge slot indices.
     * Returns -1 if key is not mapped to a slot.
     */
    private static int getSlot(String key) {
        return switch (key) {
            case PICKUP_HOOK -> ProtectionBridge.PICKUP_SLOT;
            case HAMMER_HOOK -> ProtectionBridge.HAMMER_SLOT;
            case HARVEST_HOOK -> ProtectionBridge.HARVEST_SLOT;
            case DEATH_HOOK -> ProtectionBridge.DEATH_SLOT;
            case RESPAWN_HOOK -> ProtectionBridge.RESPAWN_SLOT;
            case DURABILITY_HOOK -> ProtectionBridge.DURABILITY_SLOT;
            case CLAIM_HOOK -> ProtectionBridge.CLAIM_SLOT;
            case USE_HOOK -> ProtectionBridge.USE_SLOT;
            case SPAWN_HOOK -> ProtectionBridge.SPAWN_SLOT;
            case EXPLOSION_HOOK -> ProtectionBridge.EXPLOSION_SLOT;
            case COMMAND_HOOK -> ProtectionBridge.COMMAND_SLOT;
            case WORKBENCH_HOOK -> ProtectionBridge.WORKBENCH_SLOT;
            case BUILDERTOOLS_HOOK -> ProtectionBridge.BUILDERTOOLS_SLOT;
            case TELEPORTER_HOOK -> ProtectionBridge.TELEPORTER_SLOT;
            case PORTAL_HOOK -> ProtectionBridge.PORTAL_SLOT;
            case FIRE_HOOK -> ProtectionBridge.FIRE_SLOT;
            case WEAR_HOOK -> ProtectionBridge.WEAR_SLOT;
            default -> -1; // Not mapped, use old registry
        };
    }

    /**
     * Checks if spawn protection is ready (plugin loaded and hook registered).
     * If not ready and not in permissive mode, spawns should be blocked.
     * @deprecated Use {@link ProtectionBridge#isSpawnProtectionReady()} instead.
     */
    @Deprecated
    public static boolean isSpawnProtectionReady() {
        return ProtectionBridge.isSpawnProtectionReady();
    }

    /**
     * Checks if spawns should be allowed during startup (before plugin loads).
     * Default is false (block spawns for safety).
     * @deprecated Use {@link ProtectionBridge#isSpawnAllowedDuringStartup()} instead.
     */
    @Deprecated
    public static boolean isSpawnAllowedDuringStartup() {
        return ProtectionBridge.isSpawnAllowedDuringStartup();
    }

    /**
     * Sets spawn protection as ready. Called by main plugin after regions load.
     * @deprecated Use {@link ProtectionBridge#setSpawnProtectionReady(boolean)} instead.
     */
    @Deprecated
    public static void setSpawnProtectionReady(boolean ready) {
        ProtectionBridge.setSpawnProtectionReady(ready);
    }

    /**
     * Sets whether spawns are allowed during startup.
     * Called by main plugin based on config.
     * @deprecated Use {@link ProtectionBridge#setSpawnAllowedDuringStartup(boolean)} instead.
     */
    @Deprecated
    public static void setSpawnAllowedDuringStartup(boolean allow) {
        ProtectionBridge.setSpawnAllowedDuringStartup(allow);
    }

    // Hook keys - using full key strings for backward compatibility with mixins
    public static final String PICKUP_HOOK = "orbisguard.pickup.hook";
    public static final String HAMMER_HOOK = "orbisguard.hammer.hook";
    public static final String HARVEST_HOOK = "orbisguard.harvest.hook";
    public static final String DEATH_HOOK = "orbisguard.death.hook";
    public static final String RESPAWN_HOOK = "orbisguard.respawn.hook";
    public static final String DURABILITY_HOOK = "orbisguard.durability.hook";
    public static final String CLAIM_HOOK = "orbisguard.claim.hook";
    public static final String USE_HOOK = "orbisguard.use.hook";  // Still used by log suppression mixins
    public static final String SPAWN_HOOK = "orbisguard.spawn.hook";
    public static final String EXPLOSION_HOOK = "orbisguard.explosion.hook";
    public static final String COMMAND_HOOK = "orbisguard.command.hook";
    public static final String WORKBENCH_HOOK = "orbisguard.workbench.hook";
    public static final String BUILDERTOOLS_HOOK = "orbisguard.buildertools.hook";
    public static final String TELEPORTER_HOOK = "orbisguard.teleporter.hook";
    public static final String PORTAL_HOOK = "orbisguard.portal.hook";
    public static final String FIRE_HOOK = "orbisguard.fire.hook";
    public static final String WEAR_HOOK = "orbisguard.wear.hook";

    // Spawn protection policy flags
    /**
     * When true, spawn protection is ready and the hook should be used.
     * When false (default), spawn protection blocks ALL spawns during startup.
     * This prevents mobs from spawning in protected areas before OrbisGuard loads.
     */
    public static final String SPAWN_PROTECTION_READY = "orbisguard.spawn.ready";
    /**
     * When true, allows spawning during startup before OrbisGuard is ready.
     * Set to true for permissive mode (legacy behavior).
     * Default is false (blocking mode).
     */
    public static final String SPAWN_ALLOW_DURING_STARTUP = "orbisguard.spawn.allow_startup";

    // Mixin loaded flags
    public static final String MIXINS_LOADED = "orbisguard.mixins.loaded";
    public static final String MIXIN_PICKUP_LOADED = "orbisguard.mixin.pickup.loaded";
    public static final String MIXIN_RESPAWN_LOADED = "orbisguard.mixin.respawn.loaded";
    public static final String MIXIN_DEATH_LOADED = "orbisguard.mixin.death.loaded";
    public static final String MIXIN_DURABILITY_LOADED = "orbisguard.mixin.durability.loaded";
}

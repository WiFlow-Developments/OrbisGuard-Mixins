package com.orbisguard.mixins;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Hook interface for command protection.
 * The main OrbisGuard plugin registers an implementation of this hook,
 * which is then called by the mixin to check if a command should be blocked.
 */
public interface CommandProtectionHook {

    /**
     * Checks if a command should be blocked for the given player.
     *
     * @param playerRef The player attempting to run the command
     * @param command The command string (without leading slash)
     * @return true if the command should be blocked, false to allow
     */
    boolean shouldBlockCommand(PlayerRef playerRef, String command);

    /**
     * Gets the denial message to show when a command is blocked.
     *
     * @return The denial message
     */
    String getDenialMessage();

    /**
     * Gets the registered hook instance.
     *
     * @return The hook instance, or null if not registered
     */
    static CommandProtectionHook getHook() {
        Object hook = ProtectionBridge.get(ProtectionBridge.COMMAND_SLOT);
        if (hook instanceof CommandProtectionHook) {
            return (CommandProtectionHook) hook;
        }
        return null;
    }

    /**
     * Registers a hook instance.
     *
     * @param hook The hook to register
     */
    static void register(CommandProtectionHook hook) {
        ProtectionBridge.register(ProtectionBridge.COMMAND_SLOT, hook);
    }
}

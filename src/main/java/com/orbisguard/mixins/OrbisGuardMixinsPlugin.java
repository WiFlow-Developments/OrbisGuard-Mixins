package com.orbisguard.mixins;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.logging.Level;

/**
 * Mixin companion plugin. Requires Hyinit.
 */
public class OrbisGuardMixinsPlugin extends JavaPlugin {

    public OrbisGuardMixinsPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        // Publish compatibility metadata before the main plugin registers cross-classloader hooks.
        ProtectionBridge.publishMixinMetadata();

        // Register our classloader so mixins can load utility classes via reflection
        System.getProperties().put(ProtectionBridge.MIXINS_CLASSLOADER_KEY, getClass().getClassLoader());

        getLogger().at(Level.INFO).log(
            "OrbisGuard-Mixins loaded! bridge protocol " + ProtectionBridge.BRIDGE_PROTOCOL_VERSION);
        getLogger().at(Level.INFO).log("Mixin protections: auto-pickup, interactive-pickup, hammer, harvest, fluid-place");
    }
}

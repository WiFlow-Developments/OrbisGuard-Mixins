package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.orbisguard.mixins.ProtectionBridge;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(DeathSystems.DropPlayerDeathItems.class)
public abstract class DeathItemDropMixin {

    static {
        ProtectionBridge.publishMixinMetadata();
        System.setProperty("orbisguard.mixin.death.loaded", "true");
        ProtectionBridge.setDeathMixinLoaded(true);
    }

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int DEATH_SLOT = 3;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    // Cached MethodHandle for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    /**
     * Redirects store.getComponent() in onComponentAdded to check keep-inventory flag.
     * If the flag is set, we also set the death component's items loss mode to NONE.
     *
     * Gets DeathComponent directly from the store instead of via ThreadLocal, avoiding
     * the need for @Inject + CallbackInfo which causes ClassNotFoundException on
     * the TransformingClassLoader.
     */
    @Redirect(
        method = "onComponentAdded",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/Store;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;", ordinal = 0)
    )
    private <T extends Component<EntityStore>> T redirectGetPlayer(Store<EntityStore> store, Ref<EntityStore> ref, ComponentType<EntityStore, T> type) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform != null) {
                    UUID playerUuid = playerRef.getUuid();
                    String worldName = store.getExternalData().getWorld().getName();
                    Vector3d pos = transform.getPosition();
                    int x = (int) Math.floor(pos.x);
                    int y = (int) Math.floor(pos.y);
                    int z = (int) Math.floor(pos.z);

                    if (shouldKeepInventory(playerUuid, worldName, x, y, z)) {
                        DeathComponent component = store.getComponent(ref, DeathComponent.getComponentType());
                        if (component != null) {
                            component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }

        // Return the actual component
        return store.getComponent(ref, type);
    }

    @Unique
    private static boolean shouldKeepInventory(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;

            Object hook = bridge.get(DEATH_SLOT);
            if (hook == null) return false;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (DeathItemDropMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "shouldKeepInventory",
                            MethodType.methodType(boolean.class, UUID.class, String.class,
                                int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard death hook error #" + count, e);
            }
        }
        return false;
    }
}

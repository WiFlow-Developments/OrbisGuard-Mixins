package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import org.joml.Vector3d;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(NPCDamageSystems.DropDeathItems.class)
public abstract class NPCDeathItemDropMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int DEATH_SLOT = 3;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    @Unique
    private static volatile MethodHandle cachedSuppressMobLootHandle;
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
     * @author OrbisGuard
     * @reason Suppress mob loot drops when the mob dies in a region with mob-loot denied.
     *         When suppression applies, the death-items flag is set and the vanilla drop
     *         body is skipped; otherwise the full vanilla drop is delegated to original.call().
     */
    @WrapMethod(method = "tick")
    public void orbisguard$wrapTick(
        float dt,
        int index,
        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull Operation<Void> original
    ) {
        DeathComponent deathComponent = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        if (deathComponent != null && deathComponent.getItemsLossMode() == DeathConfig.ItemsLossMode.ALL) {
            NPCEntity npcComponent = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
            if (npcComponent != null) {
                Role role = npcComponent.getRole();
                if (role != null && !role.hasDroppedDeathItems()) {
                    TransformComponent transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
                    if (transformComponent != null && shouldSuppressMobLoot(store, transformComponent.getPosition())) {
                        role.setDeathItemsDropped();
                        return;
                    }
                }
            }
        }

        original.call(dt, index, archetypeChunk, store, commandBuffer);
    }

    @Unique
    private static boolean shouldSuppressMobLoot(Store<EntityStore> store, Vector3d position) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return false;

            Object hook = bridge.get(DEATH_SLOT);
            if (hook == null) return false;

            if (cachedBridge != bridge || cachedSuppressMobLootHandle == null) {
                synchronized (NPCDeathItemDropMixin.class) {
                    if (cachedBridge != bridge || cachedSuppressMobLootHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedSuppressMobLootHandle = lookup.findVirtual(hook.getClass(), "shouldSuppressMobLoot",
                            MethodType.methodType(boolean.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            EntityStore entityStore = store.getExternalData();
            if (entityStore == null || entityStore.getWorld() == null) {
                return false;
            }

            String worldName = entityStore.getWorld().getName();
            if (worldName == null) {
                return false;
            }
            int x = (int) Math.floor(position.x);
            int y = (int) Math.floor(position.y);
            int z = (int) Math.floor(position.z);

            return (boolean) cachedSuppressMobLootHandle.invoke(hook, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard mob loot hook error #" + count, e);
            }
        }
        return false;
    }
}

package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mixin for CraftingManager to filter protected containers from workbench material search.
 * This prevents the exploit where placing a workbench adjacent to a protected region
 * allows accessing chests inside that region.
 */
@Mixin(targets = "com.hypixel.hytale.builtin.crafting.component.CraftingManager")
public abstract class CraftingManagerMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int WORKBENCH_SLOT = 11;
    @Unique
    private static final String CONTEXT_UUID_KEY = "orbisguard.workbench.context.uuid";
    @Unique
    private static final String CONTEXT_WORLD_KEY = "orbisguard.workbench.context.world";
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    // Thread-local to track whether the current chest should be skipped
    @Unique
    private static final ThreadLocal<Boolean> skipCurrentChest = ThreadLocal.withInitial(() -> false);

    // Thread-local to store the current chest's computed position for protection checks
    @Unique
    private static final ThreadLocal<int[]> currentChestPosition = new ThreadLocal<>();

    /**
     * Shadow isValidBenchForRecipe so we can call the original from our redirect.
     */
    @Shadow
    protected abstract boolean isValidBenchForRecipe(Ref<EntityStore> ref,
            ComponentAccessor<EntityStore> componentAccessor, CraftingRecipe recipe);

    @Redirect(
        method = "craftItem",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;isValidBenchForRecipe(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/server/core/asset/type/item/config/CraftingRecipe;)Z"
        )
    )
    private boolean withContextOnCraftItem(CraftingManager self, Ref<EntityStore> ref,
            ComponentAccessor<EntityStore> componentAccessor, CraftingRecipe recipe) {
        return withCraftingContext(ref, componentAccessor,
            () -> isValidBenchForRecipe(ref, componentAccessor, recipe));
    }

    /**
     * Preserve player/world context while queueCraft validates bench-adjacent containers.
     * Hytale 0.5.1 fires CraftRecipeEvent.Pre natively for queued crafts, so this
     * redirect must not synthesize another event.
     */
    @Redirect(
        method = "queueCraft",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;isValidBenchForRecipe(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/server/core/asset/type/item/config/CraftingRecipe;)Z"
        )
    )
    private boolean withContextOnQueueCraft(CraftingManager self, Ref<EntityStore> ref,
            ComponentAccessor<EntityStore> componentAccessor, CraftingRecipe recipe) {
        return withCraftingContext(ref, componentAccessor,
            () -> isValidBenchForRecipe(ref, componentAccessor, recipe));
    }

    /**
     * Redirect ref.isValid() in getContainersAroundBench to capture the current Ref
     * and pre-compute the chest's world position for protection checks.
     * This runs at the top of each loop iteration, before getItemContainer() is called.
     */
    @Redirect(
        method = "getContainersAroundBench",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Ref;isValid()Z"
        )
    )
    private static boolean captureRefAndComputePosition(Ref<ChunkStore> ref) {
        if (!ref.isValid()) return false;

        try {
            Store<ChunkStore> store = ref.getStore();
            BlockModule.BlockStateInfo blockStateInfo = store.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            if (blockStateInfo != null) {
                Vector3i worldPos = new Vector3i();
                if (blockStateInfo.fillWorldPos(store, worldPos)) {
                    currentChestPosition.set(new int[]{worldPos.x, worldPos.y, worldPos.z});
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        currentChestPosition.remove();
        return true;
    }

    /**
     * Redirect getItemContainer() to check if this chest should be filtered.
     * We store the result in a ThreadLocal so states.add() can also skip.
     */
    @Redirect(
        method = "getContainersAroundBench",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/block/components/ItemContainerBlock;getItemContainer()Lcom/hypixel/hytale/server/core/inventory/container/SimpleItemContainer;"
        )
    )
    private static SimpleItemContainer redirectGetItemContainer(ItemContainerBlock chest) {
        // Check if this chest is accessible using position from captureRefAndComputePosition
        if (!canAccessContainerAtCurrentPosition()) {
            // Mark that we should skip this chest
            skipCurrentChest.set(true);
            // Return a marker - we'll filter it out in the add redirect
            return null;
        }

        skipCurrentChest.set(false);
        return chest.getItemContainer();
    }

    /**
     * Redirect containers.add() to skip if the container was marked for filtering.
     */
    @Redirect(
        method = "getContainersAroundBench",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
            ordinal = 0  // containers.add(chest.getItemContainer())
        )
    )
    private static boolean redirectContainersAdd(List<ItemContainer> list, Object element) {
        if (skipCurrentChest.get() || element == null) {
            // Skip - container is in protected region
            return false;
        }
        return list.add((ItemContainer) element);
    }

    /**
     * Redirect states.add() to skip if the container was marked for filtering.
     */
    @Redirect(
        method = "getContainersAroundBench",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
            ordinal = 1  // states.add(chest) - after containers.add()
        )
    )
    private static boolean redirectStatesAdd(List<ItemContainerBlock> list, Object element) {
        if (skipCurrentChest.get()) {
            // Skip - container is in protected region
            // Clear the flag for next iteration
            skipCurrentChest.set(false);
            return false;
        }
        return list.add((ItemContainerBlock) element);
    }

    /**
     * Check if player can access a container at the current chest's position.
     * Position is pre-computed by captureRefAndComputePosition and stored in ThreadLocal.
     */
    @Unique
    private static boolean canAccessContainerAtCurrentPosition() {
        // Get player context from System properties
        Object uuidObj = System.getProperties().get(CONTEXT_UUID_KEY);
        Object worldObj = System.getProperties().get(CONTEXT_WORLD_KEY);

        UUID playerUuid = uuidObj instanceof UUID ? (UUID) uuidObj : null;
        String worldName = worldObj instanceof String ? (String) worldObj : null;

        // If no context, allow (no filtering)
        if (playerUuid == null || worldName == null) {
            return true;
        }

        // Get position from ThreadLocal (set by captureRefAndComputePosition)
        int[] pos = currentChestPosition.get();
        if (pos == null) {
            return true;
        }

        return canAccessContainer(playerUuid, worldName, pos[0], pos[1], pos[2]);
    }

    /**
     * Check if player can access a container at the given location via hook.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static boolean canAccessContainer(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return true;
            Object hook = bridge.get(WORKBENCH_SLOT);
            if (hook == null) return true;

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (CraftingManagerMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "canAccessContainer",
                            MethodType.methodType(boolean.class, UUID.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (boolean) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);

        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
            return true;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    @Unique
    private static boolean withCraftingContext(Ref<EntityStore> ref,
            ComponentAccessor<EntityStore> componentAccessor, BooleanSupplier action) {
        UUID playerUuid = null;
        String worldName = null;

        try {
            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                playerUuid = playerRef.getUuid();
            }

            Object externalData = componentAccessor.getExternalData();
            if (externalData instanceof EntityStore entityStore
                    && entityStore.getWorld() != null) {
                worldName = entityStore.getWorld().getName();
            }
        } catch (Exception ignored) {
            // Fall through - validation will fail open if context is unavailable.
        }

        try {
            if (playerUuid != null) {
                System.getProperties().put(CONTEXT_UUID_KEY, playerUuid);
            }
            if (worldName != null) {
                System.getProperties().put(CONTEXT_WORLD_KEY, worldName);
            }
            return action.getAsBoolean();
        } finally {
            System.getProperties().remove(CONTEXT_UUID_KEY);
            System.getProperties().remove(CONTEXT_WORLD_KEY);
        }
    }
}

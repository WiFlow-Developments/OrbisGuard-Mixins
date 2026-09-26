package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.entity.entities.player.windows.MaterialExtraResourcesSection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Mixin for BenchWindow to set player context for workbench chest protection.
 * Sets the player UUID and world name in System properties so that
 * CraftingManagerMixin can filter out protected containers.
 */
@Mixin(targets = "com.hypixel.hytale.builtin.crafting.window.BenchWindow")
public abstract class BenchWindowMixin {

    @Unique
    private static final String CONTEXT_UUID_KEY = "orbisguard.workbench.context.uuid";
    @Unique
    private static final String CONTEXT_WORLD_KEY = "orbisguard.workbench.context.world";

    @Unique
    private static final ThreadLocal<Ref<EntityStore>> capturedRef = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Store<EntityStore>> capturedStore = new ThreadLocal<>();
    @Unique
    private UUID orbisguard$playerUuid;
    @Unique
    private String orbisguard$worldName;

    /**
     * Capture ref and store by redirecting the first store.getComponent() call in onOpen0.
     * Uses @Redirect instead of @Inject to avoid CallbackInfoReturnable ClassNotFoundException
     * on the TransformingClassLoader when callback classes are unavailable at runtime.
     * Cleanup is done in the wrapFeedExtraResources finally block.
     */
    @Redirect(
        method = "onOpen0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;"
        )
    )
    private <T extends Component<EntityStore>> T captureContext(Store<EntityStore> store,
                                                                 Ref<EntityStore> ref,
                                                                 ComponentType<EntityStore, T> type) {
        capturedRef.set(ref);
        capturedStore.set(store);
        return store.getComponent(ref, type);
    }

    /**
     * Redirect feedExtraResourcesSection call to wrap it with player context.
     * The context is read by CraftingManagerMixin to filter protected containers.
     */
    @Redirect(
        method = "onOpen0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;feedExtraResourcesSection(Lcom/hypixel/hytale/server/core/universe/world/World;IIILcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;ILcom/hypixel/hytale/server/core/asset/type/blocktype/config/bench/Bench;ILcom/hypixel/hytale/server/core/entity/entities/player/windows/MaterialExtraResourcesSection;)I"
        )
    )
    private int wrapFeedExtraResources(World world, int x, int y, int z, BlockType blockType, int rotationIndex, Bench bench, int tierLevel, MaterialExtraResourcesSection extraResourcesSection) {

        UUID playerUuid = null;
        String worldName = null;

        try {
            Ref<EntityStore> ref = capturedRef.get();
            Store<EntityStore> store = capturedStore.get();

            if (ref != null && store != null) {
                // Get player UUID from the entity opening the window
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    playerUuid = playerRef.getUuid();
                }

                // Get world name
                if (store.getExternalData() != null && store.getExternalData().getWorld() != null) {
                    worldName = store.getExternalData().getWorld().getName();
                }
            }
        } catch (Exception ignored) {
            // If we can't get context, proceed without filtering
        }

        try {
            this.orbisguard$playerUuid = playerUuid;
            this.orbisguard$worldName = worldName;

            return orbisguard$withWorkbenchContext(() ->
                com.hypixel.hytale.builtin.crafting.component.CraftingManager
                    .feedExtraResourcesSection(world, x, y, z, blockType, rotationIndex, bench, tierLevel, extraResourcesSection)
            );

        } finally {
            capturedRef.remove();
            capturedStore.remove();
        }
    }

    @Unique
    private int orbisguard$withWorkbenchContext(IntSupplier action) {
        try {
            // Set player context for CraftingManagerMixin to use
            if (this.orbisguard$playerUuid != null) {
                System.getProperties().put(CONTEXT_UUID_KEY, this.orbisguard$playerUuid);
            }
            if (this.orbisguard$worldName != null) {
                System.getProperties().put(CONTEXT_WORLD_KEY, this.orbisguard$worldName);
            }

            return action.getAsInt();

        } finally {
            System.getProperties().remove(CONTEXT_UUID_KEY);
            System.getProperties().remove(CONTEXT_WORLD_KEY);
        }
    }

    /**
     * Also wrap the call in getExtraResourcesSection for when it's re-validated.
     */
    @Redirect(
        method = "getExtraResourcesSection",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;feedExtraResourcesSection(Lcom/hypixel/hytale/server/core/universe/world/World;IIILcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;ILcom/hypixel/hytale/server/core/asset/type/blocktype/config/bench/Bench;ILcom/hypixel/hytale/server/core/entity/entities/player/windows/MaterialExtraResourcesSection;)I"
        )
    )
    private int wrapFeedExtraResourcesInRefresh(World world, int x, int y, int z, BlockType blockType, int rotationIndex, Bench bench, int tierLevel, MaterialExtraResourcesSection extraResourcesSection) {
        return orbisguard$withWorkbenchContext(() ->
            com.hypixel.hytale.builtin.crafting.component.CraftingManager
                .feedExtraResourcesSection(world, x, y, z, blockType, rotationIndex, bench, tierLevel, extraResourcesSection)
        );
    }
}

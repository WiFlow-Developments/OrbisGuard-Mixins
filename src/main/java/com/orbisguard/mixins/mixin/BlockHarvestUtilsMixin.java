package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hypixel.hytale.server.core.Message;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(BlockHarvestUtils.class)
public abstract class BlockHarvestUtilsMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int HARVEST_SLOT = 2;
    @Unique
    private static Method parseMessageMethod;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    // Cached MethodHandles for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile MethodHandle cachedCheckPickupHandle;
    @Unique
    private static volatile Object cachedBridge;

    @Unique
    private static final ThreadLocal<Boolean> denied = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<String> denyMessage = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<PlayerRef> cachedPlayerRef = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Vector3i> cachedTargetBlock = new ThreadLocal<>();

    @Shadow
    protected static void removeBlock(Vector3i blockPosition, BlockType blockType, int setBlockSettings,
            Ref<ChunkStore> chunkReference, BlockSection blockSection, ComponentAccessor<ChunkStore> chunkStore) {
        throw new UnsupportedOperationException("Shadow stub"); // replaced at runtime
    }

    /**
     * Initialize protection state at isUnknown() call (fires before other redirects).
     * Uses @Redirect instead of @Inject to avoid CallbackInfo ClassNotFoundException
     * on the TransformingClassLoader.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;isUnknown()Z")
    )
    private static boolean initProtectionState(BlockType blockType) {
        denied.set(false);
        denyMessage.set(null);
        cachedPlayerRef.set(null);
        cachedTargetBlock.set(null);
        return blockType.isUnknown();
    }

    /**
     * Capture target block position from getRotationIndex() call.
     * Fires after filler adjustment, before the permission check.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;getRotationIndex(III)I"
        )
    )
    private static int captureTargetBlock(BlockSection section, int x, int y, int z) {
        cachedTargetBlock.set(new Vector3i(x, y, z));
        return section.getRotationIndex(x, y, z);
    }

    /**
     * Check permissions by redirecting BlockInteractionUtils.isNaturalAction().
     * This fires after getRotationIndex (so we have the block position) and before
     * removeBlock (so we can set the denied flag in time).
     * Gives us ref and entityStore from the call arguments.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockInteractionUtils;isNaturalAction(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)Z"
        )
    )
    private static boolean checkPermission(Ref<EntityStore> ref, ComponentAccessor<EntityStore> entityStore) {
        try {
            PlayerRef playerRef = entityStore.getComponent(ref, PlayerRef.getComponentType());
            cachedPlayerRef.set(playerRef);

            if (playerRef != null && playerRef.hasPermission("orbisguard.bypass")) {
                return BlockInteractionUtils.isNaturalAction(ref, entityStore);
            }

            UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
            World world = entityStore.getExternalData().getWorld();
            String worldName = world != null ? world.getName() : "unknown";

            Vector3i targetBlock = cachedTargetBlock.get();
            if (targetBlock != null) {
                String msg = checkBlockBreak(playerUuid, worldName, targetBlock.x, targetBlock.y, targetBlock.z);
                if (msg != null) {
                    denied.set(true);
                    denyMessage.set(msg);
                }
            }
        } catch (Exception ignored) {}

        return BlockInteractionUtils.isNaturalAction(ref, entityStore);
    }

    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;removeBlock(Lorg/joml/Vector3i;Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;ILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void handleRemoveBlock(Vector3i blockPosition, BlockType blockType, int setBlockSettings,
            Ref<ChunkStore> chunkReference, BlockSection blockSection, ComponentAccessor<ChunkStore> chunkStore) {

        if (Boolean.TRUE.equals(denied.get())) {
            // Resync block to client using redirect parameters directly
            try {
                BlockChunk blockChunk = chunkStore.getComponent(chunkReference, BlockChunk.getComponentType());
                if (blockChunk != null) {
                    BlockSection section = blockChunk.getSectionAtBlockY(blockPosition.y);
                    if (section != null) {
                        section.invalidateBlock(blockPosition.x, blockPosition.y, blockPosition.z);
                    }
                }
            } catch (Exception ignored) {}

            // Send denial message
            try {
                PlayerRef playerRef = cachedPlayerRef.get();
                String msg = denyMessage.get();
                if (playerRef != null && msg != null && !msg.isEmpty()) {
                    playerRef.sendMessage(parseMessage(msg));
                }
            } catch (Exception ignored) {}
            return;
        }

        // Call original via @Shadow
        removeBlock(blockPosition, blockType, setBlockSettings, chunkReference, blockSection, chunkStore);
    }

    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/entity/ItemUtils;interactivelyPickupItem(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lorg/joml/Vector3d;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void handlePickup(Ref<EntityStore> ref, ItemStack itemStack, Vector3d origin,
            ComponentAccessor<EntityStore> componentAccessor) {

        // Check ThreadLocal first (set by checkPermission for block break denial)
        if (Boolean.TRUE.equals(denied.get())) {
            // Message was already sent by handleRemoveBlock
            return;
        }

        // Also directly check item pickup permission as a second layer of protection
        // This catches cases where block break is allowed but item pickup is not
        try {
            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null && !playerRef.hasPermission("orbisguard.bypass")) {
                UUID playerUuid = playerRef.getUuid();
                World world = componentAccessor.getExternalData().getWorld();
                String worldName = world != null ? world.getName() : null;

                if (worldName != null && origin != null) {
                    String msg = checkPickupPermission(playerUuid, worldName,
                            (int) origin.x, (int) origin.y, (int) origin.z);
                    if (msg != null) {
                        // Send denial message and block pickup
                        playerRef.sendMessage(parseMessage(msg));
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}

        ItemUtils.interactivelyPickupItem(ref, itemStack, origin, componentAccessor);
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

    /**
     * Parses a message with color codes using reflection to avoid classloader issues.
     * Retries if classloader wasn't available yet.
     */
    @Unique
    private static Message parseMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return Message.raw("");
        }

        // Try to use MixinMessageUtil via reflection (loaded from early plugin classloader)
        if (parseMessageMethod == null) {
            try {
                ClassLoader earlyLoader = (ClassLoader) System.getProperties().get("orbisguard.mixins.classloader");
                if (earlyLoader != null) {
                    Class<?> utilClass = Class.forName("com.orbisguard.mixins.util.MixinMessageUtil", true, earlyLoader);
                    parseMessageMethod = utilClass.getMethod("parse", String.class);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not load MixinMessageUtil", e);
            }
        }

        if (parseMessageMethod != null) {
            try {
                return (Message) parseMessageMethod.invoke(null, msg);
            } catch (Exception ignored) {}
        }

        // Fallback: inline color parsing
        return parseMessageInline(msg);
    }

    @Unique
    private static Message parseMessageInline(String input) {
        if (!input.contains("&")) {
            return Message.raw(input);
        }

        Message result = null;
        StringBuilder currentText = new StringBuilder();
        java.awt.Color currentColor = null;
        boolean bold = false, italic = false;

        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '&' && i + 1 < input.length()) {
                char code = input.charAt(i + 1);

                // Check for hex color &#RRGGBB
                if (code == '#' && i + 8 <= input.length()) {
                    String hex = input.substring(i + 2, i + 8);
                    if (hex.matches("[0-9A-Fa-f]{6}")) {
                        result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
                        currentText.setLength(0);
                        currentColor = new java.awt.Color(Integer.parseInt(hex, 16));
                        i += 8;
                        continue;
                    }
                }

                // Check for named colors
                String remaining = input.substring(i + 1).toLowerCase();
                String[] colors = {"red", "green", "blue", "yellow", "gold", "aqua", "white", "gray", "black"};
                boolean found = false;
                for (String color : colors) {
                    if (remaining.startsWith(color)) {
                        result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
                        currentText.setLength(0);
                        currentColor = getNamedColor(color);
                        i += 1 + color.length();
                        found = true;
                        break;
                    }
                }
                if (found) continue;

                // Format codes
                if (code == 'l' || code == 'L') {
                    result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
                    currentText.setLength(0);
                    bold = true;
                    i += 2;
                    continue;
                } else if (code == 'o' || code == 'O') {
                    result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
                    currentText.setLength(0);
                    italic = true;
                    i += 2;
                    continue;
                } else if (code == 'r' || code == 'R') {
                    result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
                    currentText.setLength(0);
                    bold = false;
                    italic = false;
                    currentColor = null;
                    i += 2;
                    continue;
                }
            }
            currentText.append(input.charAt(i));
            i++;
        }

        result = appendSegment(result, currentText.toString(), bold, italic, currentColor);
        return result != null ? result : Message.raw("");
    }

    @Unique
    private static Message appendSegment(Message current, String text, boolean bold, boolean italic, java.awt.Color color) {
        if (text.isEmpty()) return current;
        Message segment = Message.raw(text);
        if (bold) segment = segment.bold(true);
        if (italic) segment = segment.italic(true);
        if (color != null) segment = segment.color(color);
        return current == null ? segment : Message.join(current, segment);
    }

    @Unique
    private static java.awt.Color getNamedColor(String name) {
        return switch (name) {
            case "red" -> new java.awt.Color(255, 85, 85);
            case "green" -> new java.awt.Color(85, 255, 85);
            case "blue" -> new java.awt.Color(85, 85, 255);
            case "yellow" -> new java.awt.Color(255, 255, 85);
            case "gold" -> new java.awt.Color(255, 170, 0);
            case "aqua" -> new java.awt.Color(85, 255, 255);
            case "white" -> new java.awt.Color(255, 255, 255);
            case "gray" -> new java.awt.Color(170, 170, 170);
            case "black" -> new java.awt.Color(0, 0, 0);
            default -> null;
        };
    }

    @Unique
    private static String checkPickupPermission(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(HARVEST_SLOT);
            if (hook == null) return null;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckPickupHandle == null) {
                synchronized (BlockHarvestUtilsMixin.class) {
                    if (cachedBridge != bridge || cachedCheckPickupHandle == null) {
                        try {
                            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                            cachedCheckPickupHandle = lookup.findVirtual(hook.getClass(), "checkPickup",
                                MethodType.methodType(String.class, UUID.class, String.class,
                                    int.class, int.class, int.class));
                        } catch (NoSuchMethodException e) {
                            // Method doesn't exist - set to null to avoid repeated lookups
                            cachedCheckPickupHandle = null;
                        }
                        cachedBridge = bridge;
                    }
                }
            }

            if (cachedCheckPickupHandle != null) {
                return (String) cachedCheckPickupHandle.invoke(hook, playerUuid, worldName, x, y, z);
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard pickup hook error #" + count, e);
            }
        }
        return null;
    }

    @Unique
    private static String checkBlockBreak(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(HARVEST_SLOT);
            if (hook == null) return null;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (BlockHarvestUtilsMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "check",
                            MethodType.methodType(String.class, UUID.class, String.class,
                                int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (String) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard harvest hook error #" + count, e);
            }
        }
        return null;
    }
}

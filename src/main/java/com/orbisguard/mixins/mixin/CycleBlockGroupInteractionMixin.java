package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.CycleBlockGroupInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

/**
 * Mixin on the base CycleBlockGroupInteraction to ensure OrbisGuard protection
 * works even when another plugin (e.g., SimpleClaims) replaces OrbisGuard's
 * codec-registered interaction override for "CycleBlockGroup".
 *
 * When a subclass calls super.interactWithBlock(), these redirects fire on the
 * base class method, providing a safety net regardless of codec registration order.
 */
@Mixin(CycleBlockGroupInteraction.class)
public abstract class CycleBlockGroupInteractionMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-CycleBlockGroup");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int HAMMER_SLOT = 1;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);
    @Unique
    private static Method parseMessageMethod;

    // Cached MethodHandle for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    // ThreadLocals for passing state between redirects within a single interactWithBlock call
    @Unique
    private static final ThreadLocal<UUID> capturedUuid = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<String> capturedWorld = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<PlayerRef> capturedPlayerRef = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Boolean> denied = new ThreadLocal<>();

    /**
     * Redirect 1: Capture player context from the commandBuffer.getComponent() call.
     * This fires early in interactWithBlock, before any block mutation logic.
     * Pinned to ordinal 0 (the Player lookup): the later Hotbar lookup runs after the
     * permission check and would otherwise reset the denied flag before setBlock.
     */
    @Redirect(
        method = "interactWithBlock",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/CommandBuffer;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;",
            ordinal = 0
        )
    )
    private <T extends Component<EntityStore>> T orbisguard$capturePlayerContext(
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            ComponentType<EntityStore, T> type) {

        // Reset state for this call
        denied.set(false);
        capturedUuid.set(null);
        capturedWorld.set(null);
        capturedPlayerRef.set(null);

        T component = commandBuffer.getComponent(ref, type);

        if (component instanceof Player) {
            try {
                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    capturedPlayerRef.set(playerRef);
                    // Skip permission capture for bypassed players
                    if (!playerRef.hasPermission("orbisguard.bypass")) {
                        capturedUuid.set(playerRef.getUuid());
                        capturedWorld.set(ref.getStore().getExternalData().getWorld().getName());
                    }
                }
            } catch (Exception ignored) {}
        }

        return component;
    }

    /**
     * Redirect 2: Check OrbisGuard permission when reading the block at the target position.
     * At this point we have coordinates from the parameters and player context from ThreadLocals.
     * If denied, sets the denied flag and sends the denial message. Returns the real block index
     * so the method continues without errors, the setBlock redirect below prevents the actual change.
     */
    @Redirect(
        method = "interactWithBlock",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;get(III)I"
        )
    )
    private int orbisguard$checkPermissionOnBlockGet(BlockSection section, int x, int y, int z) {
        UUID uuid = capturedUuid.get();
        String worldName = capturedWorld.get();

        if (uuid != null && worldName != null) {
            String denyMsg = orbisguard$invokeHammerCheck(uuid, worldName, x, y, z);
            if (denyMsg != null) {
                denied.set(true);
                PlayerRef playerRef = capturedPlayerRef.get();
                if (playerRef != null && !denyMsg.isEmpty()) {
                    playerRef.sendMessage(orbisguard$parseMessage(denyMsg));
                }
            }
        }

        return section.get(x, y, z);
    }

    /**
     * Redirect 3: Skip the actual block change if denied.
     * The method runs its normal logic (no errors), but the block mutation is blocked here.
     */
    @Redirect(
        method = "interactWithBlock",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/universe/world/chunk/BlockOperations;setBlock(Lcom/hypixel/hytale/server/core/universe/world/storage/ChunkStore;Lcom/hypixel/hytale/component/Ref;IIIILcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;III)Z"
        )
    )
    private boolean orbisguard$skipSetBlockIfDenied(ChunkStore chunkStore, Ref<ChunkStore> sectionRef,
                                                     int x, int y, int z,
                                                     int blockId, BlockType blockType,
                                                     int rotation, int filler, int settings) {
        if (Boolean.TRUE.equals(denied.get())) {
            return false; // Skip block change
        }
        return BlockOperations.setBlock(chunkStore, sectionRef, x, y, z, blockId, blockType, rotation, filler, settings);
    }

    /**
     * Redirect 4: Skip the durability cost when the cycle was denied.
     */
    @Redirect(
        method = "interactWithBlock",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/entity/ItemUtils;canDecreaseItemStackDurability(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)Z"
        )
    )
    private boolean orbisguard$skipDurabilityIfDenied(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (Boolean.TRUE.equals(denied.get())) {
            return false;
        }
        return ItemUtils.canDecreaseItemStackDurability(ref, accessor);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> orbisguard$getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    /**
     * Invoke the hammer hook's checkMessage method.
     * Returns denial message string if blocked, null if allowed.
     */
    @Unique
    private static String orbisguard$invokeHammerCheck(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = orbisguard$getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(HAMMER_SLOT);
            if (hook == null) return null;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (CycleBlockGroupInteractionMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "checkMessage",
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
                LOGGER.log(Level.WARNING, "OrbisGuard hammer hook error #" + count, e);
            }
        }
        return null;
    }

    @Unique
    private static Message orbisguard$parseMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return Message.raw("");
        }

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
        return orbisguard$parseMessageInline(msg);
    }

    @Unique
    private static Message orbisguard$parseMessageInline(String input) {
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

                if (code == '#' && i + 8 <= input.length()) {
                    String hex = input.substring(i + 2, i + 8);
                    if (hex.matches("[0-9A-Fa-f]{6}")) {
                        result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
                        currentText.setLength(0);
                        currentColor = new java.awt.Color(Integer.parseInt(hex, 16));
                        i += 8;
                        continue;
                    }
                }

                String remaining = input.substring(i + 1).toLowerCase();
                String[] colors = {"red", "green", "blue", "yellow", "gold", "aqua", "white", "gray", "black"};
                boolean found = false;
                for (String color : colors) {
                    if (remaining.startsWith(color)) {
                        result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
                        currentText.setLength(0);
                        currentColor = orbisguard$getNamedColor(color);
                        i += 1 + color.length();
                        found = true;
                        break;
                    }
                }
                if (found) continue;

                if (code == 'l' || code == 'L') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
                    currentText.setLength(0);
                    bold = true;
                    i += 2;
                    continue;
                } else if (code == 'o' || code == 'O') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
                    currentText.setLength(0);
                    italic = true;
                    i += 2;
                    continue;
                } else if (code == 'r' || code == 'R') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
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

        result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, currentColor);
        return result != null ? result : Message.raw("");
    }

    @Unique
    private static Message orbisguard$appendSegment(Message current, String text, boolean bold, boolean italic, java.awt.Color color) {
        if (text.isEmpty()) return current;
        Message segment = Message.raw(text);
        if (bold) segment = segment.bold(true);
        if (italic) segment = segment.italic(true);
        if (color != null) segment = segment.color(color);
        return current == null ? segment : Message.join(current, segment);
    }

    @Unique
    private static java.awt.Color orbisguard$getNamedColor(String name) {
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
}

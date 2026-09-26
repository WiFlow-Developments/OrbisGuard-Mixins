package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Protects against scythe/tool-based crop harvesting.
 *
 * Scythes use HarvestCropInteraction -> FarmingUtil.harvest(), which is a completely
 * different code path from F-key harvesting (BlockHarvestUtils.performPickupByInteraction).
 * Without this mixin, players can harvest crops in protected regions using scythes.
 */
@Mixin(HarvestCropInteraction.class)
public abstract class HarvestCropInteractionMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-HarvestCrop");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int HARVEST_SLOT = 2;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);
    @Unique
    private static Method parseMessageMethod;
    @Unique
    private static final long MESSAGE_COOLDOWN_MS = 2000;
    @Unique
    private static final Map<UUID, Long> lastDenyTime = new ConcurrentHashMap<>();

    // Cached MethodHandle for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    /**
     * Redirect FarmingUtil.harvest() to check OrbisGuard protection first.
     * If denied, returns false which sets InteractionState.Failed in the caller,
     * properly handling client-side state without needing explicit block resync.
     */
    @Redirect(
        method = "interactWithBlock",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/adventure/farming/FarmingUtil;harvest(Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/Ref;Lorg/joml/Vector3i;)Z"
        )
    )
    private boolean orbisguard$checkHarvestPermission(
            ComponentAccessor<ChunkStore> chunkAccessor,
            ComponentAccessor<EntityStore> accessor,
            Ref<EntityStore> ref,
            Vector3i blockPosition) {

        try {
            PlayerRef playerRef = accessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null && !playerRef.hasPermission("orbisguard.bypass")) {
                UUID uuid = playerRef.getUuid();
                String worldName = accessor.getExternalData().getWorld().getName();

                String msg = orbisguard$invokeHarvestCheck(uuid, worldName,
                        blockPosition.x, blockPosition.y, blockPosition.z);
                if (msg != null) {
                    if (!msg.isEmpty()) {
                        long now = System.currentTimeMillis();
                        Long last = lastDenyTime.get(uuid);
                        if (last == null || now - last >= MESSAGE_COOLDOWN_MS) {
                            lastDenyTime.put(uuid, now);
                            playerRef.sendMessage(orbisguard$parseMessage(msg));
                        }
                    }
                    return false;
                }
            }
        } catch (Exception ignored) {}

        return FarmingUtil.harvest(chunkAccessor, accessor, ref, blockPosition);
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
     * Invoke the harvest hook's checkScytheHarvest method.
     * Returns denial message string if blocked, null if allowed.
     */
    @Unique
    private static String orbisguard$invokeHarvestCheck(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = orbisguard$getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(HARVEST_SLOT);
            if (hook == null) return null;

            // Cache MethodHandle with identity check on bridge to detect reloads
            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (HarvestCropInteractionMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "checkScytheHarvest",
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

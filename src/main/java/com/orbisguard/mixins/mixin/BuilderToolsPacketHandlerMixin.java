package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.builtin.buildertools.BuilderToolsPacketHandler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.buildertools.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mixin to intercept builder/creative tool packets and check OrbisGuard protection.
 */
@Mixin(BuilderToolsPacketHandler.class)
public abstract class BuilderToolsPacketHandlerMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-BuilderTools");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int BUILDERTOOLS_SLOT = 12;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);
    @Unique
    private static Method parseMessageMethod;

    @Unique
    private static volatile MethodHandle cachedCheckHandle;
    @Unique
    private static volatile Object cachedBridge;

    /**
     * @author OrbisGuard
     * @reason Add region protection check for brush tools. The vanilla queueing
     *         (player-null check + addToQueue) is delegated to original.call();
     *         only the OrbisGuard guard runs before it.
     */
    @WrapMethod(method = "handleBuilderToolOnUseInteraction")
    public void orbisguard$wrapHandleBuilderToolOnUseInteraction(
            @Nonnull BuilderToolOnUseInteraction packet,
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Operation<Void> original) {
        String denyMsg = orbisguard$checkProtection(playerRef, world, packet.x, packet.y, packet.z);
        if (denyMsg != null) {
            orbisguard$sendDenial(playerRef, denyMsg);
            return;
        }
        original.call(packet, playerRef, ref, world, store);
    }

    /**
     * @author OrbisGuard
     * @reason Add region protection check for paste tool. The vanilla paste queueing
     *         (player-null check + PasteAir resolution + addToQueue) is delegated to
     *         original.call(); only the OrbisGuard guard runs before it.
     */
    @WrapMethod(method = "handleBuilderToolPasteClipboard")
    public void orbisguard$wrapHandleBuilderToolPasteClipboard(
            @Nonnull BuilderToolPasteClipboard packet,
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Operation<Void> original) {
        String denyMsg = orbisguard$checkProtection(playerRef, world, packet.x, packet.y, packet.z);
        if (denyMsg != null) {
            orbisguard$sendDenial(playerRef, denyMsg);
            return;
        }
        original.call(packet, playerRef, ref, world, store);
    }

    @Unique
    private String orbisguard$checkProtection(PlayerRef playerRef, World world, int x, int y, int z) {
        try {
            if (playerRef == null || world == null) return null;

            // Check bypass permission via PlayerRef (Player no longer implements PermissionHolder in Update 5)
            if (playerRef.hasPermission("orbisguard.bypass")) {
                return null;
            }

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();

            return orbisguard$invokeHook(playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
            return null;
        }
    }

    @Unique
    private void orbisguard$sendDenial(PlayerRef playerRef, String msg) {
        if (playerRef != null && msg != null && !msg.isEmpty()) {
            playerRef.sendMessage(orbisguard$parseMessage(msg));
        }
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

    @Unique
    @SuppressWarnings("unchecked")
    private static AtomicReferenceArray<Object> orbisguard$getBridge() {
        Object bridge = System.getProperties().get(BRIDGE_KEY);
        if (bridge instanceof AtomicReferenceArray) {
            return (AtomicReferenceArray<Object>) bridge;
        }
        return null;
    }

    @Unique
    private static String orbisguard$invokeHook(UUID playerUuid, String worldName, int x, int y, int z) {
        try {
            AtomicReferenceArray<Object> bridge = orbisguard$getBridge();
            if (bridge == null) return null;
            Object hook = bridge.get(BUILDERTOOLS_SLOT);
            if (hook == null) return null;

            if (cachedBridge != bridge || cachedCheckHandle == null) {
                synchronized (BuilderToolsPacketHandlerMixin.class) {
                    if (cachedBridge != bridge || cachedCheckHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedCheckHandle = lookup.findVirtual(hook.getClass(), "check",
                            MethodType.methodType(String.class, UUID.class, String.class, int.class, int.class, int.class));
                        cachedBridge = bridge;
                    }
                }
            }

            return (String) cachedCheckHandle.invoke(hook, playerUuid, worldName, x, y, z);
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }
        return null;
    }
}

package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.Message;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(CommandManager.class)
public class CommandManagerMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Mixins");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int COMMAND_SLOT = 10;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);
    @Unique
    private static Method parseMessageMethod;

    @Unique
    private static volatile MethodHandle cachedBlockHandle;
    @Unique
    private static volatile MethodHandle cachedDenialHandle;
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

    @Unique
    private static final ThreadLocal<String> currentCommandInput = new ThreadLocal<>();

    // Inject at acceptCall() - intercepts command execution
    @Redirect(
        method = "runCommand",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/command/system/AbstractCommand;acceptCall(Lcom/hypixel/hytale/server/core/command/system/CommandSender;Lcom/hypixel/hytale/server/core/command/system/ParserContext;Lcom/hypixel/hytale/server/core/command/system/ParseResult;)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<Void> redirectAcceptCall(AbstractCommand command, CommandSender sender,
                                                        ParserContext context, ParseResult parseResult) {
        if (sender instanceof PlayerRef playerRef) {
            String fullCommandInput = context.getInputString();
            String denialMessage = shouldBlockCommand(playerRef, fullCommandInput);
            if (denialMessage != null) {
                if (!denialMessage.isEmpty()) {
                    playerRef.sendMessage(parseMessage(denialMessage));
                }
                return CompletableFuture.completedFuture(null);
            }
        }
        return command.acceptCall(sender, context, parseResult);
    }

    @Unique
    private static Message parseMessage(String msg) {
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
    private static String shouldBlockCommand(PlayerRef playerRef, String command) {
        try {
            AtomicReferenceArray<Object> bridge = getBridge();
            if (bridge == null) return null;
            Object hook = bridge.get(COMMAND_SLOT);
            if (hook == null) return null;

            if (cachedBridge != bridge || cachedBlockHandle == null || cachedDenialHandle == null) {
                synchronized (CommandManagerMixin.class) {
                    if (cachedBridge != bridge || cachedBlockHandle == null || cachedDenialHandle == null) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        cachedBlockHandle = lookup.findVirtual(hook.getClass(), "shouldBlockCommand",
                            MethodType.methodType(boolean.class, PlayerRef.class, String.class));
                        cachedDenialHandle = lookup.findVirtual(hook.getClass(), "getDenialMessage",
                            MethodType.methodType(String.class));
                        cachedBridge = bridge;
                    }
                }
            }

            boolean blocked = (boolean) cachedBlockHandle.invoke(hook, playerRef, command);

            if (blocked) {
                return (String) cachedDenialHandle.invoke(hook);
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "Hook error #" + count, e);
            }
        }
        return null;
    }
}

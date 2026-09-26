package com.orbisguard.mixins.mixin;

import com.hypixel.hytale.builtin.adventure.teleporter.interaction.server.TeleporterInteraction;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mixin to intercept teleporter interactions (both F-key and walk-through)
 * and check OrbisGuard use-portals flag protection.
 */
@Mixin(TeleporterInteraction.class)
public abstract class TeleporterInteractionMixin {

    @Unique
    private static final Logger LOGGER = Logger.getLogger("OrbisGuard-Teleporter");
    @Unique
    private static final String BRIDGE_KEY = "orbisguard.bridge";
    @Unique
    private static final int PORTAL_SLOT = 14;
    @Unique
    private static final AtomicLong errorCount = new AtomicLong(0);

    // Cached MethodHandles for hook invocation with bridge identity check
    @Unique
    private static volatile MethodHandle cachedCheckHandle6Param;
    @Unique
    private static volatile MethodHandle cachedCheckHandle5Param;
    @Unique
    private static volatile Object cachedBridge;

    // Color parsing constants
    @Unique
    private static final Map<String, Color> NAMED_COLORS = new HashMap<>();
    @Unique
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})");
    @Unique
    private static Pattern NAMED_COLOR_PATTERN;

    static {
        NAMED_COLORS.put("black", new Color(0, 0, 0));
        NAMED_COLORS.put("dark_blue", new Color(0, 0, 170));
        NAMED_COLORS.put("dark_green", new Color(0, 170, 0));
        NAMED_COLORS.put("dark_aqua", new Color(0, 170, 170));
        NAMED_COLORS.put("dark_red", new Color(170, 0, 0));
        NAMED_COLORS.put("dark_purple", new Color(170, 0, 170));
        NAMED_COLORS.put("gold", new Color(255, 170, 0));
        NAMED_COLORS.put("gray", new Color(170, 170, 170));
        NAMED_COLORS.put("dark_gray", new Color(85, 85, 85));
        NAMED_COLORS.put("blue", new Color(85, 85, 255));
        NAMED_COLORS.put("green", new Color(85, 255, 85));
        NAMED_COLORS.put("aqua", new Color(85, 255, 255));
        NAMED_COLORS.put("red", new Color(255, 85, 85));
        NAMED_COLORS.put("light_purple", new Color(255, 85, 255));
        NAMED_COLORS.put("yellow", new Color(255, 255, 85));
        NAMED_COLORS.put("white", new Color(255, 255, 255));
        NAMED_COLORS.put("orange", new Color(255, 165, 0));

        StringBuilder sb = new StringBuilder("&(");
        boolean first = true;
        for (String name : NAMED_COLORS.keySet()) {
            if (!first) sb.append("|");
            sb.append(name);
            first = false;
        }
        sb.append(")");
        NAMED_COLOR_PATTERN = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * @author OrbisGuard
     * @reason Add region protection check for teleporter use. The vanilla body
     *         (teleporter resolution + teleport) is delegated to original.call();
     *         only the OrbisGuard guard runs before it.
     */
    @WrapMethod(method = "interactWithBlock")
    private void orbisguard$wrapInteractWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler,
            @Nonnull Operation<Void> original) {

        Ref<EntityStore> ref = context.getEntity();

        // === OrbisGuard protection check ===
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();
            boolean hasBypass = playerRef.hasPermission("orbisguard.bypass") ||
                                playerRef.hasPermission("orbisguard.bypass.portals");
            String denyMsg = orbisguard$checkProtection(playerUuid, worldName, targetBlock.x, targetBlock.y, targetBlock.z, hasBypass);
            if (denyMsg != null) {
                context.getState().state = InteractionState.Failed;
                if (!denyMsg.isEmpty()) {
                    playerRef.sendMessage(orbisguard$parseMessage(denyMsg));
                }
                return;
            }
        }
        // === End OrbisGuard protection check ===

        original.call(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
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
    private static String orbisguard$checkProtection(UUID playerUuid, String worldName, int x, int y, int z, boolean hasBypass) {
        try {
            AtomicReferenceArray<Object> bridge = orbisguard$getBridge();
            if (bridge == null) return null;

            Object hook = bridge.get(PORTAL_SLOT);
            if (hook == null) return null;

            // Cache MethodHandles with identity check on bridge to detect reloads
            if (cachedBridge != bridge || (cachedCheckHandle6Param == null && cachedCheckHandle5Param == null)) {
                synchronized (TeleporterInteractionMixin.class) {
                    if (cachedBridge != bridge || (cachedCheckHandle6Param == null && cachedCheckHandle5Param == null)) {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        try {
                            cachedCheckHandle6Param = lookup.findVirtual(hook.getClass(), "check",
                                MethodType.methodType(String.class, UUID.class, String.class,
                                    int.class, int.class, int.class, boolean.class));
                        } catch (NoSuchMethodException e) {
                            cachedCheckHandle6Param = null;
                        }
                        try {
                            cachedCheckHandle5Param = lookup.findVirtual(hook.getClass(), "check",
                                MethodType.methodType(String.class, UUID.class, String.class,
                                    int.class, int.class, int.class));
                        } catch (NoSuchMethodException e) {
                            cachedCheckHandle5Param = null;
                        }
                        cachedBridge = bridge;
                    }
                }
            }

            if (cachedCheckHandle6Param != null) {
                return (String) cachedCheckHandle6Param.invoke(hook, playerUuid, worldName, x, y, z, hasBypass);
            } else if (cachedCheckHandle5Param != null) {
                return (String) cachedCheckHandle5Param.invoke(hook, playerUuid, worldName, x, y, z);
            }
        } catch (Throwable e) {
            long count = errorCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.log(Level.WARNING, "OrbisGuard portal hook error #" + count, e);
            }
        }
        return null;
    }

    @Unique
    private static Message orbisguard$parseMessage(String input) {
        if (input == null || input.isEmpty()) {
            return Message.raw("");
        }
        if (!input.contains("&")) {
            return Message.raw(input);
        }

        boolean bold = false;
        boolean italic = false;
        boolean monospace = false;
        Color currentColor = null;

        StringBuilder currentText = new StringBuilder();
        Message result = null;

        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '&' && i + 1 < input.length()) {
                // Check for hex color: &#RRGGBB
                if (input.charAt(i + 1) == '#') {
                    Matcher hexMatcher = HEX_PATTERN.matcher(input.substring(i));
                    if (hexMatcher.lookingAt()) {
                        result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                        currentText.setLength(0);
                        String hex = hexMatcher.group(1);
                        currentColor = orbisguard$parseHexColor(hex);
                        i += hexMatcher.end();
                        continue;
                    }
                }

                // Check for named colors
                Matcher namedMatcher = NAMED_COLOR_PATTERN.matcher(input.substring(i));
                if (namedMatcher.lookingAt()) {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    String colorName = namedMatcher.group(1).toLowerCase();
                    currentColor = NAMED_COLORS.get(colorName);
                    i += namedMatcher.end();
                    continue;
                }

                // Check for format codes
                char code = input.charAt(i + 1);
                if (code == 'l' || code == 'L') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    bold = true;
                    i += 2;
                    continue;
                } else if (code == 'o' || code == 'O') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    italic = true;
                    i += 2;
                    continue;
                } else if (code == 'm' || code == 'M') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    monospace = true;
                    i += 2;
                    continue;
                } else if (code == 'r' || code == 'R') {
                    result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    bold = false;
                    italic = false;
                    monospace = false;
                    currentColor = null;
                    i += 2;
                    continue;
                }
            }

            currentText.append(input.charAt(i));
            i++;
        }

        result = orbisguard$appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
        return result != null ? result : Message.raw("");
    }

    @Unique
    private static Message orbisguard$appendSegment(Message current, String text, boolean bold, boolean italic,
                                                     boolean monospace, Color color) {
        if (text.isEmpty()) {
            return current;
        }

        Message segment = Message.raw(text);
        if (bold) segment = segment.bold(true);
        if (italic) segment = segment.italic(true);
        if (monospace) segment = segment.monospace(true);
        if (color != null) segment = segment.color(color);

        if (current == null) {
            return segment;
        }
        return Message.join(current, segment);
    }

    @Unique
    private static Color orbisguard$parseHexColor(String hex) {
        if (hex.length() == 3) {
            char r = hex.charAt(0);
            char g = hex.charAt(1);
            char b = hex.charAt(2);
            hex = "" + r + r + g + g + b + b;
        }
        int rgb = Integer.parseInt(hex, 16);
        return new Color(rgb);
    }
}

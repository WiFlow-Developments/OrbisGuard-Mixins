package com.orbisguard.mixins.util;

import com.hypixel.hytale.server.core.Message;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message formatter for color/format codes (&red, &#FF5555, &l).
 * Matches the main plugin's MessageFormatter behavior.
 */
public final class MixinMessageUtil {

    private MixinMessageUtil() {}

    private static final Map<String, Color> NAMED_COLORS = new HashMap<>();

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
        NAMED_COLORS.put("pink", new Color(255, 182, 193));
        NAMED_COLORS.put("cyan", new Color(0, 255, 255));
        NAMED_COLORS.put("brown", new Color(139, 69, 19));
        NAMED_COLORS.put("lime", new Color(50, 205, 50));
        NAMED_COLORS.put("magenta", new Color(255, 0, 255));
    }

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})");
    private static final Pattern NAMED_COLOR_PATTERN;

    static {
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

    public static Message parse(String input) {
        if (input == null || input.isEmpty()) {
            return Message.raw("");
        }

        if (!input.contains("&")) {
            return Message.raw(input);
        }

        return parseFormatted(input);
    }

    private static Message parseFormatted(String input) {
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
                        result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                        currentText.setLength(0);

                        String hex = hexMatcher.group(1);
                        currentColor = parseHexColor(hex);
                        i += hexMatcher.end();
                        continue;
                    }
                }

                // Check for named colors FIRST (before format codes to avoid &r matching &red)
                Matcher namedMatcher = NAMED_COLOR_PATTERN.matcher(input.substring(i));
                if (namedMatcher.lookingAt()) {
                    result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);

                    String colorName = namedMatcher.group(1).toLowerCase();
                    currentColor = NAMED_COLORS.get(colorName);
                    i += namedMatcher.end();
                    continue;
                }

                // Check for format codes: &l, &o, &m, &r
                char code = input.charAt(i + 1);
                if (code == 'l' || code == 'L') {
                    result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    bold = true;
                    i += 2;
                    continue;
                } else if (code == 'o' || code == 'O') {
                    result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    italic = true;
                    i += 2;
                    continue;
                } else if (code == 'm' || code == 'M') {
                    result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
                    currentText.setLength(0);
                    monospace = true;
                    i += 2;
                    continue;
                } else if (code == 'r' || code == 'R') {
                    result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);
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

        result = appendSegment(result, currentText.toString(), bold, italic, monospace, currentColor);

        return result != null ? result : Message.raw("");
    }

    private static Message appendSegment(Message current, String text, boolean bold, boolean italic,
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

    private static Color parseHexColor(String hex) {
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

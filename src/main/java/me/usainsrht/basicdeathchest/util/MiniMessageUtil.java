package me.usainsrht.basicdeathchest.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * Thin wrapper around Adventure's {@link MiniMessage} API.
 *
 * <p>All player-facing text in BasicDeathChest is parsed via this class.
 * Legacy {@code &}-colour codes are intentionally NOT supported.
 */
public final class MiniMessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private MiniMessageUtil() {}

    /**
     * Deserializes a MiniMessage string into a {@link Component}.
     */
    public static Component parse(String text) {
        if (text == null) return Component.empty();
        return MM.deserialize(text);
    }

    /**
     * Deserializes a MiniMessage string, substituting named placeholders.
     *
     * <pre>{@code
     * MiniMessageUtil.parse("<gray>Hello, %name%!", "name", "Alice");
     * }</pre>
     *
     * @param text  the MiniMessage source; use {@code %key%} style placeholders
     * @param pairs alternating key/value strings; must be an even number
     * @throws IllegalArgumentException if {@code pairs} is odd-length
     */
    public static Component parse(String text, String... pairs) {
        if (pairs == null || pairs.length == 0) return parse(text);
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("MiniMessageUtil.parse(): pairs must be key-value pairs (even count)");
        }
        // Replace %key% tokens before MiniMessage parsing so they can contain formatting tags.
        String replaced = text;
        for (int i = 0; i < pairs.length; i += 2) {
            replaced = replaced.replace("%" + pairs[i] + "%", pairs[i + 1]);
        }
        return MM.deserialize(replaced);
    }

    /**
     * Deserializes a MiniMessage string substituting all entries from {@code placeholders}.
     */
    public static Component parse(String text, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return parse(text);
        String replaced = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            replaced = replaced.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return MM.deserialize(replaced);
    }

    /**
     * Strips all MiniMessage tags and returns the plain text equivalent.
     */
    public static String stripFormatting(String text) {
        if (text == null) return "";
        return PLAIN.serialize(MM.deserialize(text));
    }

    /**
     * Returns the singleton {@link MiniMessage} instance for advanced use.
     */
    public static MiniMessage mm() {
        return MM;
    }
}

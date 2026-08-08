package me.usainsrht.basicdeathchest.placeholder;

import me.usainsrht.basicdeathchest.util.MiniMessageUtil;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft-dependency hook for the MiniPlaceholders API (v3+).
 *
 * <p>When MiniPlaceholders is absent, {@link #resolve(String, Audience)} falls back
 * to {@link Player#displayName()} for players (or an empty component otherwise).
 */
public class MiniPlaceholdersHook {

    private final Logger logger;
    private boolean available;

    public MiniPlaceholdersHook(Logger logger) {
        this.logger = logger;
    }

    /**
     * Detects MiniPlaceholders on the server. Call during plugin enable.
     *
     * @return {@code true} if MiniPlaceholders is present and usable
     */
    public boolean initialize() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MiniPlaceholders")) {
            logger.info("MiniPlaceholders not found — death-message placeholders use display names.");
            available = false;
            return false;
        }
        try {
            // Probe v3 API so an incompatible plugin jar fails soft at enable-time.
            io.github.miniplaceholders.api.MiniPlaceholders.audienceGlobalPlaceholders();
            available = true;
            logger.info("MiniPlaceholders hooked.");
            return true;
        } catch (LinkageError e) {
            available = false;
            logger.log(Level.WARNING,
                    "MiniPlaceholders is installed but incompatible (need v3+). "
                            + "Death-message placeholders will use display names.",
                    e);
            return false;
        }
    }

    /** Returns {@code true} if MiniPlaceholders is available. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Resolves a MiniMessage string with audience + global placeholders against {@code audience}.
     *
     * <p>When MiniPlaceholders is absent (or resolution fails) and {@code audience}
     * is a {@link Player}, returns {@link Player#displayName()}.
     */
    public Component resolve(String miniMessage, Audience audience) {
        if (!available || miniMessage == null || miniMessage.isBlank()) {
            return fallbackDisplayName(audience);
        }
        try {
            TagResolver resolver = io.github.miniplaceholders.api.MiniPlaceholders
                    .audienceGlobalPlaceholders();
            // Audience is supplied to deserialize (v3); resolvers are audience-independent.
            return MiniMessageUtil.mm().deserialize(miniMessage, audience, resolver);
        } catch (Exception | LinkageError e) {
            logger.log(Level.WARNING, "Failed to resolve MiniPlaceholders format: " + miniMessage, e);
            return fallbackDisplayName(audience);
        }
    }

    private static Component fallbackDisplayName(Audience audience) {
        if (audience instanceof Player player) {
            return player.displayName();
        }
        return Component.empty();
    }
}

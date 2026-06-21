package me.usainsrht.basicdeathchest.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Logger;

/**
 * Soft-dependency hook for the Vault economy API.
 *
 * <p>When Vault is absent, all operations silently succeed (treat teleportation
 * as free). Callers should use {@link #isEnabled()} to decide whether to show
 * cost-related messages to the player.
 */
public class VaultEconomyHook {

    private Economy economy;
    private boolean enabled;
    private final Logger logger;

    public VaultEconomyHook(Logger logger) {
        this.logger = logger;
    }

    /**
     * Attempts to hook into the Vault Economy service.
     * Call this during plugin enable, after all plugins are loaded.
     *
     * @return {@code true} if the hook succeeded
     */
    public boolean initialize() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            logger.info("Vault not found — economy features disabled (teleports are free).");
            enabled = false;
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            logger.warning("Vault is installed but no Economy provider was found.");
            enabled = false;
            return false;
        }
        economy = rsp.getProvider();
        enabled = true;
        logger.info("Vault economy hooked: " + economy.getName());
        return true;
    }

    /** Returns {@code true} if Vault economy is available. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the player's current balance, or {@link Double#MAX_VALUE} if Vault is absent.
     */
    public double getBalance(Player player) {
        if (!enabled) return Double.MAX_VALUE;
        return economy.getBalance(player);
    }

    /**
     * Returns {@code true} if the player can afford {@code amount}.
     * Always returns {@code true} when Vault is absent.
     */
    public boolean canAfford(Player player, double amount) {
        if (!enabled || amount <= 0) return true;
        return economy.has(player, amount);
    }

    /**
     * Withdraws {@code amount} from the player's account.
     *
     * @return {@code true} if the transaction succeeded (or Vault is absent)
     */
    public boolean charge(Player player, double amount) {
        if (!enabled || amount <= 0) return true;
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    /**
     * Returns the formatted currency string for {@code amount} (e.g. "100 coins").
     */
    public String format(double amount) {
        if (!enabled) return String.valueOf(amount);
        return economy.format(amount);
    }
}

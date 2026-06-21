package me.usainsrht.basicdeathchest;

import me.usainsrht.basicdeathchest.api.DeathChestAPI;
import me.usainsrht.basicdeathchest.chest.DeathChestManager;
import me.usainsrht.basicdeathchest.config.ConfigManager;
import me.usainsrht.basicdeathchest.config.MessagesManager;
import me.usainsrht.basicdeathchest.database.DatabaseManager;
import me.usainsrht.basicdeathchest.database.JsonDatabase;
import me.usainsrht.basicdeathchest.database.SQLiteDatabase;
import me.usainsrht.basicdeathchest.economy.VaultEconomyHook;
import me.usainsrht.basicdeathchest.gui.GUIListener;
import me.usainsrht.basicdeathchest.hologram.HologramManager;
import me.usainsrht.basicdeathchest.listener.ChestProtectionListener;
import me.usainsrht.basicdeathchest.listener.PlayerDeathListener;
import me.usainsrht.basicdeathchest.listener.PlayerJoinListener;
import me.usainsrht.basicdeathchest.teleport.BodyguardManager;
import me.usainsrht.basicdeathchest.teleport.TeleportManager;
import me.usainsrht.basicdeathchest.util.FoliaUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * BasicDeathChest — Main plugin class.
 *
 * <p>Orchestrates service initialization, listener registration, and clean shutdown.
 * All heavy initialization is performed on the global region thread via
 * {@link FoliaUtil#runGlobal} to avoid blocking the server startup thread for
 * async-sensitive operations like database connection.
 *
 * <h3>Enable order</h3>
 * <ol>
 *   <li>{@link ConfigManager#reload()} — loads config.yml</li>
 *   <li>{@link MessagesManager#reload()} — loads messages.yml</li>
 *   <li>{@link DatabaseManager} initialization (async-safe)</li>
 *   <li>{@link HologramManager#initialize()}</li>
 *   <li>{@link VaultEconomyHook#initialize()}</li>
 *   <li>Register listeners</li>
 *   <li>{@link DeathChestAPI#setInstance(BasicDeathChest)}</li>
 * </ol>
 */
public class BasicDeathChest extends JavaPlugin {

    // ─── Services ─────────────────────────────────────────────────────────────
    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private DatabaseManager databaseManager;
    private DeathChestManager deathChestManager;
    private HologramManager hologramManager;
    private TeleportManager teleportManager;
    private BodyguardManager bodyguardManager;
    private VaultEconomyHook vaultEconomy;

    /** PDC key used to tag death chest block states with the owner's UUID string. */
    private NamespacedKey deathChestKey;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        deathChestKey = new NamespacedKey(this, "death_chest_owner");

        // Config & messages (synchronous — no Bukkit world API needed)
        configManager = new ConfigManager(this);
        configManager.reload();

        messagesManager = new MessagesManager(this);
        messagesManager.reload();

        // Database (initialise synchronously — JDBC connect is fast)
        if (!initDatabase()) {
            getLogger().severe("Failed to initialise database — disabling plugin!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Hologram backend
        hologramManager = new HologramManager(this);
        hologramManager.initialize();

        // Economy hook (Vault must be loaded by now as a soft-depend)
        vaultEconomy = new VaultEconomyHook(getLogger());
        vaultEconomy.initialize();

        // Core managers
        deathChestManager = new DeathChestManager(this);
        teleportManager   = new TeleportManager(this);
        bodyguardManager  = new BodyguardManager(this);

        // Register listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerDeathListener(this), this);
        pm.registerEvents(new ChestProtectionListener(this), this);
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new GUIListener(this), this);
        pm.registerEvents(bodyguardManager, this);   // BodyguardManager is also a Listener

        // Expose public API
        DeathChestAPI.setInstance(this);

        getLogger().info("BasicDeathChest v" + getPluginMeta().getVersion() + " enabled.");
        getLogger().info("Hologram backend: " + configManager.getHologramBackend());
        getLogger().info("Database backend: " + configManager.getDatabaseBackend());
        if (vaultEconomy.isEnabled()) {
            getLogger().info("Vault economy: active");
        }
    }

    @Override
    public void onDisable() {
        // Cancel all active chest timers and remove holograms
        if (deathChestManager != null) {
            deathChestManager.disableAll();
        }

        // Remove all holograms
        if (hologramManager != null) {
            hologramManager.removeAll();
        }

        // Remove all bodyguards
        if (bodyguardManager != null) {
            bodyguardManager.removeAll();
        }

        // Close database
        if (databaseManager != null) {
            FoliaUtil.runAsync(this, () -> databaseManager.close());
        }

        getLogger().info("BasicDeathChest disabled.");
    }

    // ─── Reload support ───────────────────────────────────────────────────────

    /**
     * Hot-reloads configuration and messages.
     * Does NOT restart the database connection or re-register listeners.
     */
    public void reload() {
        configManager.reload();
        messagesManager.reload();
        // Re-initialize hologram backend in case it changed
        hologramManager.removeAll();
        hologramManager.initialize();
        getLogger().info("BasicDeathChest configuration reloaded.");
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public ConfigManager    getConfigManager()      { return configManager; }
    public MessagesManager  getMessagesManager()    { return messagesManager; }
    public DatabaseManager  getDatabaseManager()    { return databaseManager; }
    public DeathChestManager getDeathChestManager() { return deathChestManager; }
    public HologramManager  getHologramManager()   { return hologramManager; }
    public TeleportManager  getTeleportManager()   { return teleportManager; }
    public BodyguardManager getBodyguardManager()  { return bodyguardManager; }
    public VaultEconomyHook getVaultEconomy()      { return vaultEconomy; }

    /**
     * Returns the {@link NamespacedKey} used to tag death chest block states in PDC.
     * External plugins may use this to detect death chests without importing internal classes.
     */
    public NamespacedKey getDeathChestKey()        { return deathChestKey; }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private boolean initDatabase() {
        String backend = configManager.getDatabaseBackend();
        databaseManager = switch (backend) {
            case "JSON" -> new JsonDatabase(this);
            default     -> new SQLiteDatabase(this);
        };
        try {
            databaseManager.initialize();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database initialization failed", e);
            return false;
        }
    }
}

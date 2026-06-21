package me.usainsrht.basicdeathchest.config;

import me.usainsrht.basicdeathchest.BasicDeathChest;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Typed accessor for all values in {@code config.yml}.
 *
 * <p>Call {@link #reload()} to re-read the configuration from disk.
 * All getter methods return cached values set during the last reload.
 */
public class ConfigManager {

    private final BasicDeathChest plugin;

    // ── World settings ──────────────────────────────────────────────────────
    private boolean worldWhitelistMode;
    private List<String> worldList;

    // ── Permission gate ─────────────────────────────────────────────────────
    private boolean requirePermission;
    private String requiredPermission;

    // ── Container ───────────────────────────────────────────────────────────
    private Material containerType;
    private String containerTitle;
    private boolean dropOnBreak;
    private boolean openByEveryone;

    // ── Timer ───────────────────────────────────────────────────────────────
    private int timerDuration;        // seconds; ≤ 0 → infinite
    private Sound expirySound;
    private float expirySoundVolume;
    private float expirySoundPitch;
    private String expiryParticle;
    private int expiryParticleCount;

    // ── Hologram ────────────────────────────────────────────────────────────
    private String hologramBackend;
    private double hologramYOffset;
    private int hologramUpdateIntervalTicks;
    private List<String> hologramLines;
    private String hologramBillboard;
    private boolean hologramSeeThrough;
    private boolean hologramShadow;
    private String hologramBgColor;
    private float hologramScaleX, hologramScaleY, hologramScaleZ;

    // ── Database ─────────────────────────────────────────────────────────────
    private String databaseBackend;
    private int maxEntriesPerPlayer;

    // ── GUI ──────────────────────────────────────────────────────────────────
    private String guiTitle;
    private int guiMaxEntries;
    private Material guiEntryMaterial;
    private Material guiFillerMaterial;
    private int guiRows;
    private Material guiNoRecordMaterial;
    private List<Integer> guiSlots;

    // ── Coordinates ──────────────────────────────────────────────────────────
    private boolean coordinatesEnabled;
    private String coordinatesMessage;

    // ── Death messages ───────────────────────────────────────────────────────
    private String deathMessageMode;
    private String deathMessageCustomFormat;

    // ── Teleport ─────────────────────────────────────────────────────────────
    private boolean teleportEnabled;
    private int teleportFreeUses;
    private double teleportCost;
    private List<PotionEffect> arrivalEffects;
    private Sound arrivalSound;
    private float arrivalSoundVolume;
    private float arrivalSoundPitch;
    private double arrivalSoundRadius;

    // ── Bodyguards ───────────────────────────────────────────────────────────
    private boolean bodyguardsEnabled;
    private int bodyguardDuration;
    private int bodyguardCount;
    private org.bukkit.entity.EntityType bodyguardMobType;
    private int bodyguardUpdateIntervalTicks;
    private String bodyguardNameTemplate;

    // ─────────────────────────────────────────────────────────────────────────

    public ConfigManager(BasicDeathChest plugin) {
        this.plugin = plugin;
    }

    /**
     * Saves the default config (if absent) and re-reads all values from disk.
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // World
        worldWhitelistMode = cfg.getBoolean("worlds.whitelist", true);
        worldList = cfg.getStringList("worlds.list");

        // Permission gate
        requirePermission = cfg.getBoolean("require-permission", false);
        requiredPermission = cfg.getString("required-permission", "basicdeathchest.use");

        // Container
        containerType = parseMaterial(cfg.getString("container.type", "CHEST"), Material.CHEST);
        containerTitle = cfg.getString("container.title", "<gold>%player%</gold>");
        dropOnBreak = cfg.getBoolean("container.drop-on-break", false);
        openByEveryone = cfg.getBoolean("container.open-by-everyone", false);

        // Timer
        timerDuration = cfg.getInt("timer.duration", 300);
        expirySound = parseSound(cfg.getString("timer.expiry-sound", "ENTITY_ENDER_DRAGON_GROWL"));
        expirySoundVolume = (float) cfg.getDouble("timer.expiry-sound-volume", 1.0);
        expirySoundPitch = (float) cfg.getDouble("timer.expiry-sound-pitch", 1.0);
        expiryParticle = cfg.getString("timer.expiry-particle", "EXPLOSION_LARGE");
        expiryParticleCount = cfg.getInt("timer.expiry-particle-count", 10);

        // Hologram
        hologramBackend = cfg.getString("hologram.backend", "FANCY_HOLOGRAMS").toUpperCase();
        hologramYOffset = cfg.getDouble("hologram.y-offset", 1.5);
        hologramUpdateIntervalTicks = Math.max(1, cfg.getInt("hologram.update-interval-ticks", 20));
        hologramLines = cfg.getStringList("hologram.lines");
        if (hologramLines.isEmpty()) hologramLines = List.of("<gold>Death Chest</gold>", "<white>%player%");
        hologramBillboard = cfg.getString("hologram.vanilla.billboard", "CENTER").toUpperCase();
        hologramSeeThrough = cfg.getBoolean("hologram.vanilla.see-through", false);
        hologramShadow = cfg.getBoolean("hologram.vanilla.shadow", true);
        hologramBgColor = cfg.getString("hologram.vanilla.background-color", "0x40000000");
        hologramScaleX = (float) cfg.getDouble("hologram.vanilla.scale-x", 1.0);
        hologramScaleY = (float) cfg.getDouble("hologram.vanilla.scale-y", 1.0);
        hologramScaleZ = (float) cfg.getDouble("hologram.vanilla.scale-z", 1.0);

        // Database
        databaseBackend = cfg.getString("database.backend", "SQLITE").toUpperCase();
        maxEntriesPerPlayer = Math.max(1, cfg.getInt("database.max-entries-per-player", 50));

        // GUI
        guiTitle = cfg.getString("gui.title", "<dark_gray>Death Locations");
        guiMaxEntries = Math.min(45, Math.max(1, cfg.getInt("gui.max-entries", 27)));
        guiEntryMaterial = parseMaterial(cfg.getString("gui.entry-material", "COMPASS"), Material.COMPASS);
        guiFillerMaterial = parseMaterial(cfg.getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        guiRows = Math.min(6, Math.max(1, cfg.getInt("gui.rows", 6)));
        guiNoRecordMaterial = parseMaterial(cfg.getString("gui.no-record-material", "BARRIER"), Material.BARRIER);
        List<Integer> rawSlots = cfg.getIntegerList("gui.slots");
        guiSlots = rawSlots != null ? new ArrayList<>(rawSlots) : new ArrayList<>();
        int maxSlotIndex = guiRows * 9;
        guiSlots.removeIf(slot -> slot < 0 || slot >= maxSlotIndex);
        if (guiSlots.isEmpty()) {
            for (int i = 0; i < guiMaxEntries; i++) {
                guiSlots.add(i);
            }
        }

        // Coordinates
        coordinatesEnabled = cfg.getBoolean("coordinates.enabled", true);
        coordinatesMessage = cfg.getString("coordinates.message",
                "<gray>You died at <white>%x%<gray>, <white>%y%<gray>, <white>%z%.");

        // Death messages
        deathMessageMode = cfg.getString("death-messages.mode", "VANILLA").toUpperCase();
        deathMessageCustomFormat = cfg.getString("death-messages.custom-format", "");

        // Teleport
        teleportEnabled = cfg.getBoolean("teleport.enabled", true);
        teleportFreeUses = Math.max(0, cfg.getInt("teleport.free-uses", 3));
        teleportCost = Math.max(0, cfg.getDouble("teleport.cost", 100.0));
        arrivalEffects = parseEffects(cfg.getMapList("teleport.arrival-effects"));
        arrivalSound = parseSound(cfg.getString("teleport.arrival-sound", "ENTITY_ENDER_DRAGON_GROWL"));
        arrivalSoundVolume = (float) cfg.getDouble("teleport.arrival-sound-volume", 1.0);
        arrivalSoundPitch = (float) cfg.getDouble("teleport.arrival-sound-pitch", 0.8);
        arrivalSoundRadius = cfg.getDouble("teleport.arrival-sound-radius", 20.0);

        // Bodyguards
        bodyguardsEnabled = cfg.getBoolean("bodyguards.enabled", true);
        bodyguardDuration = Math.max(1, cfg.getInt("bodyguards.duration", 60));
        bodyguardCount = Math.max(1, Math.min(10, cfg.getInt("bodyguards.count", 2)));
        String mobTypeName = cfg.getString("bodyguards.type", "IRON_GOLEM");
        try {
            bodyguardMobType = org.bukkit.entity.EntityType.valueOf(mobTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid bodyguard mob type '" + mobTypeName + "' in config — using IRON_GOLEM");
            bodyguardMobType = org.bukkit.entity.EntityType.IRON_GOLEM;
        }
        bodyguardUpdateIntervalTicks = Math.max(1, cfg.getInt("bodyguards.update-interval-ticks", 20));
        bodyguardNameTemplate = cfg.getString("bodyguards.name", "<green>%timer%s remaining");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.getMaterial(name.toUpperCase());
        if (m == null) {
            plugin.getLogger().warning("Invalid material '" + name + "' in config — using " + fallback.name());
            return fallback;
        }
        return m;
    }

    private Sound parseSound(String name) {
        if (name == null) return Sound.ENTITY_ENDER_DRAGON_GROWL;
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound '" + name + "' in config — using ENTITY_ENDER_DRAGON_GROWL");
            return Sound.ENTITY_ENDER_DRAGON_GROWL;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PotionEffect> parseEffects(List<?> raw) {
        List<PotionEffect> effects = new ArrayList<>();
        if (raw == null) return effects;
        for (Object obj : raw) {
            if (!(obj instanceof java.util.Map<?, ?> map)) continue;
            try {
                String typeName = (String) map.get("type");
                Object durObj = map.get("duration");
                int duration = (durObj instanceof Number n) ? n.intValue() : 100;
                Object ampObj = map.get("amplifier");
                int amplifier = (ampObj instanceof Number n) ? n.intValue() : 0;
                PotionEffectType type = PotionEffectType.getByName(typeName);
                if (type == null) {
                    plugin.getLogger().warning("Unknown potion effect type: " + typeName);
                    continue;
                }
                effects.add(new PotionEffect(type, duration, amplifier, true, false, false));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to parse potion effect entry", e);
            }
        }
        return effects;
    }

    // ─── World helpers ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a death chest should be spawned in the given world.
     */
    public boolean isWorldAllowed(String worldName) {
        boolean inList = worldList.contains(worldName);
        return worldWhitelistMode == inList;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public boolean isWorldWhitelistMode()           { return worldWhitelistMode; }
    public List<String> getWorldList()              { return worldList; }
    public boolean isRequirePermission()            { return requirePermission; }
    public String getRequiredPermission()           { return requiredPermission; }
    public Material getContainerType()              { return containerType; }
    public String getContainerTitle()               { return containerTitle; }
    public boolean isDropOnBreak()                  { return dropOnBreak; }
    public int getTimerDuration()                   { return timerDuration; }
    public boolean isInfiniteTimer()                { return timerDuration <= 0; }
    public Sound getExpirySound()                   { return expirySound; }
    public float getExpirySoundVolume()             { return expirySoundVolume; }
    public float getExpirySoundPitch()              { return expirySoundPitch; }
    public String getExpiryParticle()               { return expiryParticle; }
    public int getExpiryParticleCount()             { return expiryParticleCount; }
    public String getHologramBackend()              { return hologramBackend; }
    public double getHologramYOffset()              { return hologramYOffset; }
    public int getHologramUpdateIntervalTicks()     { return hologramUpdateIntervalTicks; }
    public List<String> getHologramLines()          { return hologramLines; }
    public String getHologramBillboard()            { return hologramBillboard; }
    public boolean isHologramSeeThrough()           { return hologramSeeThrough; }
    public boolean isHologramShadow()               { return hologramShadow; }
    public String getHologramBgColor()              { return hologramBgColor; }
    public float getHologramScaleX()                { return hologramScaleX; }
    public float getHologramScaleY()                { return hologramScaleY; }
    public float getHologramScaleZ()                { return hologramScaleZ; }
    public String getDatabaseBackend()              { return databaseBackend; }
    public int getMaxEntriesPerPlayer()             { return maxEntriesPerPlayer; }
    public String getGuiTitle()                     { return guiTitle; }
    public int getGuiMaxEntries()                   { return guiMaxEntries; }
    public Material getGuiEntryMaterial()           { return guiEntryMaterial; }
    public Material getGuiFillerMaterial()          { return guiFillerMaterial; }
    public boolean isCoordinatesEnabled()           { return coordinatesEnabled; }
    public String getCoordinatesMessage()           { return coordinatesMessage; }
    public String getDeathMessageMode()             { return deathMessageMode; }
    public String getDeathMessageCustomFormat()     { return deathMessageCustomFormat; }
    public boolean isTeleportEnabled()              { return teleportEnabled; }
    public int getTeleportFreeUses()                { return teleportFreeUses; }
    public double getTeleportCost()                 { return teleportCost; }
    public List<PotionEffect> getArrivalEffects()   { return arrivalEffects; }
    public Sound getArrivalSound()                  { return arrivalSound; }
    public float getArrivalSoundVolume()            { return arrivalSoundVolume; }
    public float getArrivalSoundPitch()             { return arrivalSoundPitch; }
    public double getArrivalSoundRadius()           { return arrivalSoundRadius; }
    public boolean isBodyguardsEnabled()            { return bodyguardsEnabled; }
    public int getBodyguardDuration()               { return bodyguardDuration; }
    public int getBodyguardCount()                  { return bodyguardCount; }
    public boolean isOpenByEveryone()               { return openByEveryone; }
    public int getGuiRows()                         { return guiRows; }
    public Material getGuiNoRecordMaterial()        { return guiNoRecordMaterial; }
    public List<Integer> getGuiSlots()              { return guiSlots; }
    public org.bukkit.entity.EntityType getBodyguardMobType() { return bodyguardMobType; }
    public int getBodyguardUpdateIntervalTicks()    { return bodyguardUpdateIntervalTicks; }
    public String getBodyguardNameTemplate()        { return bodyguardNameTemplate; }
}

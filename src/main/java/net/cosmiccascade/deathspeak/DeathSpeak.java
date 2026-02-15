package net.cosmiccascade.deathspeak;

//region Imports
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
//endregion

public final class DeathSpeak extends JavaPlugin {

    //region State: queue + runtime settings
    private final Queue<String> wordQueue = new LinkedList<>();
    private boolean isProcessing = false;

    // Config-driven settings
    private int radius = 8;
    private boolean substringKeywords = true;

    // Config-driven rules
    private final Set<Material> protectedBlocks = EnumSet.noneOf(Material.class);
    private final Map<String, Set<Material>> keywordMap = new HashMap<>();
    //endregion

    //region Lifecycle
    @Override
    public void onEnable() {
        // Create config on first run (copies from src/main/resources/config.yml)
        saveDefaultConfig();
        reloadConfig();

        // Load rules and settings from config.yml
        loadSettingsFromConfig();

        getLogger().info("DeathSpeak has awakened.");

        // Register /deathspeak command
        registerCommands();
    }

    @Override
    public void onDisable() {
        getLogger().info("DeathSpeak has fallen silent.");
    }
    //endregion

    //region Commands
    private void registerCommands() {
        var command = this.getCommand("deathspeak");

        if (command == null) {
            getLogger().severe("Command 'deathspeak' not found in plugin.yml!");
            return;
        }

        command.setExecutor((sender, cmd, label, args) -> {
            if (args.length == 0) {
                sender.sendMessage("Usage: /deathspeak <word|reload>");
                return true;
            }

            // /deathspeak reload -> reload config rules without restarting server
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadSettingsFromConfig();
                sender.sendMessage("DeathSpeak config reloaded.");
                return true;
            }

            // Everything else is treated as the spoken word
            String word = args[0].toLowerCase();
            wordQueue.add(word);

            broadcast("\"" + word + "\" has been spoken...");

            // Kick the processor (queue ensures we run one effect at a time)
            processQueue();

            return true;
        });
    }
    //endregion

    //region Queue processing
    /**
     * Runs one queued word at a time. This prevents overlapping world edits.
     */
    private void processQueue() {
        if (isProcessing) return;

        String word = wordQueue.poll();
        if (word == null) return;

        isProcessing = true;

        broadcast("[DeathSpeak] \"" + word + "\" takes effect instantly.");

        // Apply the actual deletion effect
        applyEffect(word);

        isProcessing = false;

        // Process next queued word (if any)
        processQueue();
    }
    //endregion

    //region Messaging
    /**
     * Paper prefers Adventure Components over legacy chat color codes.
     */
    private void broadcast(String message) {
        Bukkit.getServer().broadcast(Component.text(message));
    }
    //endregion

    //region Effect: deletion logic
    private void applyEffect(String word) {

        Set<Material> targets = resolveTargets(word);

        if (targets.isEmpty()) {
            broadcast("[DeathSpeak] No targets found for: " + word);
            return;
        }

        int totalRemoved = 0;

        // Effect applies around EVERY player, in their current world/dimension
        for (var player : Bukkit.getOnlinePlayers()) {

            var world = player.getWorld();
            var center = player.getLocation();

            int removedHere = 0;

            // 16x16 centered-ish square when radius = 8
            for (int dx = -(radius - 1); dx <= radius; dx++) {
                for (int dz = -(radius - 1); dz <= radius; dz++) {

                    // Full height scan (as requested)
                    for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {

                        Block block = world.getBlockAt(
                                center.getBlockX() + dx,
                                y,
                                center.getBlockZ() + dz
                        );

                        Material type = block.getType();

                        // Skip air variants (AIR/CAVE_AIR/VOID_AIR)
                        if (type.isAir()) continue;

                        // Never delete protected blocks (spawners/portals/etc.)
                        if (protectedBlocks.contains(type)) continue;

                        // Protect all tile entity blocks (chests, furnaces, etc.)
                        if (block.getState() instanceof org.bukkit.block.TileState) continue;

                        // Must be one of our target materials
                        if (!targets.contains(type)) continue;

                        // Delete block and allow physics (fluids update)
                        block.setType(Material.AIR, true);
                        removedHere++;
                    }
                }
            }

            if (removedHere > 0) {
                broadcast("[DeathSpeak] " + player.getName() + " lost " + removedHere + " blocks.");
            }

            totalRemoved += removedHere;
        }

        broadcast("[DeathSpeak] Total removed: " + totalRemoved);
    }
    //endregion

    //region Target selection (word -> materials)
    /**
     * Target selection priority:
     * 1) Exact keyword match from config.yml (e.g., "wood", "slab", "end")
     * 2) Exact material name match (e.g., "netherrack", "end_stone", "stone")
     * 3) Substring keyword match ("friend" contains "end") if enabled in config
     * 4) Otherwise: no effect (predictable + safe)
     */
    private Set<Material> resolveTargets(String word) {

        String w = word.toLowerCase();

        // 1) exact keyword match
        Set<Material> exact = keywordMap.get(w);
        if (exact != null) return EnumSet.copyOf(exact);

        // 2) exact block name match ("end_stone" or "end stone")
        String normalized = w.replace("minecraft:", "").replace(" ", "_");
        Material exactMat = Material.matchMaterial(normalized);
        if (exactMat != null && exactMat.isBlock() && !protectedBlocks.contains(exactMat)) {
            return EnumSet.of(exactMat);
        }

        // 3) substring keyword match ("friend" contains "end")
        if (substringKeywords) {
            for (Map.Entry<String, Set<Material>> entry : keywordMap.entrySet()) {
                if (w.contains(entry.getKey())) {
                    return EnumSet.copyOf(entry.getValue());
                }
            }
        }

        // 4) otherwise none
        return EnumSet.noneOf(Material.class);
    }
    //endregion

    //region Config loading
    /**
     * Loads radius/substring behavior, protected blocks, and keyword rules from config.yml.
     * Config is the single source of truth (no hardcoded keyword maps).
     */
    private void loadSettingsFromConfig() {

        // radius + substring behavior
        this.radius = Math.max(1, getConfig().getInt("radius", 8));
        this.substringKeywords = getConfig().getBoolean("substring-keywords", true);

        // protected blocks
        protectedBlocks.clear();
        for (String s : getConfig().getStringList("protected-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) protectedBlocks.add(m);
            else getLogger().warning("Unknown protected block in config: " + s);
        }

        // keywords
        keywordMap.clear();
        ConfigurationSection keywords = getConfig().getConfigurationSection("keywords");
        if (keywords == null) {
            getLogger().warning("No 'keywords' section found in config.yml!");
            return;
        }

        for (String key : keywords.getKeys(false)) {
            String target = String.valueOf(keywords.get(key)).trim();

            Set<Material> mats = resolveTargetToMaterials(target);

            if (mats.isEmpty()) {
                getLogger().warning("Keyword '" + key + "' has invalid target: " + target);
                continue;
            }

            keywordMap.put(key.toLowerCase(), mats);
        }

        getLogger().info("Loaded " + keywordMap.size() + " keyword rules. Radius=" + radius);
    }

    private Set<Material> resolveTargetToMaterials(String target) {

        // 1) Try TAG first (PLANKS, WOODEN_SLABS, etc.)
        Set<Material> fromTag = resolveTag(target);
        if (!fromTag.isEmpty()) return fromTag;

        // 2) Otherwise, try MATERIAL (END_STONE, NETHERRACK, etc.)
        Material m = Material.matchMaterial(target);
        if (m != null) return EnumSet.of(m);

        return EnumSet.noneOf(Material.class);
    }

    /**
     * Minimal tag support; add more cases as you need them.
     */
    private Set<Material> resolveTag(String tagNameRaw) {

        String tagName = tagNameRaw.toUpperCase();

        return switch (tagName) {
            case "PLANKS" -> EnumSet.copyOf(Tag.PLANKS.getValues());
            case "WOODEN_SLABS" -> EnumSet.copyOf(Tag.WOODEN_SLABS.getValues());
            case "WOODEN_STAIRS" -> EnumSet.copyOf(Tag.WOODEN_STAIRS.getValues());
            case "LOGS" -> EnumSet.copyOf(Tag.LOGS.getValues());
            case "LEAVES" -> EnumSet.copyOf(Tag.LEAVES.getValues());
            default -> EnumSet.noneOf(Material.class);
        };
    }
    //endregion
}

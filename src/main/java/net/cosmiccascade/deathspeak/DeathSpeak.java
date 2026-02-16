package net.cosmiccascade.deathspeak;

//region Imports
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;


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

    //region HTTP ingress settings
    private boolean httpEnabled = true;
    private String httpHost = "127.0.0.1";
    private int httpPort = 8765;
    private String httpToken = "";
    private HttpServer httpServer;
    //endregion


    public void queueWord(String phrase, String source) {
        String word = (phrase == null ? "" : phrase).toLowerCase().trim();
        if (word.isBlank()) return;

        wordQueue.add(word);
        getLogger().info("[DeathSpeak] " + source + " spoke: \"" + word + "\"");
        processQueue();
    }


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

        startHttpServer();

    }

    @Override
    public void onDisable() {
        getLogger().info("DeathSpeak has fallen silent.");
        stopHttpServer();
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
            String phrase = args[0]; // don't force lowercase here; queueWord handles it
            queueWord(phrase, "Command");


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
            getLogger().info("[DeathSpeak] No targets found for: " + word);
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

            totalRemoved += removedHere;
        }

        getLogger().info("[DeathSpeak] Total removed: " + totalRemoved);
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

        String spoken = word.toLowerCase().trim();

        // 1) Exact keyword match from config
        Set<Material> exactKeyword = keywordMap.get(spoken);
        if (exactKeyword != null && !exactKeyword.isEmpty()) {
            return EnumSet.copyOf(exactKeyword);
        }

        // 2) Exact material match (lets people say "diamond_ore" or "end stone")
        // Normalize common formats: "minecraft:end_stone", "end stone", "end_stone"
        String normalized = spoken
                .replace("minecraft:", "")
                .replace("-", "_")
                .replace(" ", "_");

        Material exactMat = Material.matchMaterial(normalized);
        if (exactMat != null && exactMat.isBlock() && !protectedBlocks.contains(exactMat)) {
            return EnumSet.of(exactMat);
        }

        // 3) Substring keyword match (LONGEST keyword wins)
        if (substringKeywords) {
            String bestKey = null;

            for (String key : keywordMap.keySet()) {
                if (spoken.contains(key)) {
                    if (bestKey == null || key.length() > bestKey.length()) {
                        bestKey = key;
                    }
                }
            }

            if (bestKey != null) {
                Set<Material> mats = keywordMap.get(bestKey);
                if (mats != null && !mats.isEmpty()) {
                    return EnumSet.copyOf(mats);
                }
            }
        }


        // 4) No match -> no effect (predictable + safe)
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

        httpEnabled = getConfig().getBoolean("http.enabled", true);
        httpHost = getConfig().getString("http.host", "127.0.0.1");
        httpPort = getConfig().getInt("http.port", 8765);
        httpToken = getConfig().getString("http.token", "");


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

        String tagName = tagNameRaw.trim().toUpperCase();

        return switch (tagName) {

            // ===== WOOD GROUPS =====
            case "PLANKS" -> materialsMatching(name -> name.endsWith("_PLANKS"));

            // logs + woods + stripped variants + stems/hyphae
            case "LOGS" -> materialsMatching(name ->
                    name.endsWith("_LOG")
                            || name.endsWith("_WOOD")
                            || name.endsWith("_STEM")
                            || name.endsWith("_HYPHAE")
                            || (name.startsWith("STRIPPED_") && (
                            name.endsWith("_LOG") || name.endsWith("_WOOD")
                                    || name.endsWith("_STEM") || name.endsWith("_HYPHAE")
                    ))
            );

            case "LEAVES" -> materialsMatching(name -> name.endsWith("_LEAVES"));

            // ===== UNIVERSAL SHAPES (ALL types) =====
            case "SLABS" -> materialsMatching(name -> name.endsWith("_SLAB"));
            case "STAIRS" -> materialsMatching(name -> name.endsWith("_STAIRS"));
            case "WALLS" -> materialsMatching(name -> name.endsWith("_WALL"));
            case "FENCES" -> materialsMatching(name -> name.endsWith("_FENCE"));
            case "FENCE_GATES" -> materialsMatching(name -> name.endsWith("_FENCE_GATE"));
            case "DOORS" -> materialsMatching(name -> name.endsWith("_DOOR"));
            case "TRAPDOORS" -> materialsMatching(name -> name.endsWith("_TRAPDOOR"));
            case "BUTTONS" -> materialsMatching(name -> name.endsWith("_BUTTON"));
            case "PRESSURE_PLATES" -> materialsMatching(name -> name.endsWith("_PRESSURE_PLATE"));

            // ===== TERRAIN / COMMON =====
            case "STONE_LIKE" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                addIfExists(s,
                        "STONE", "COBBLESTONE", "MOSSY_COBBLESTONE",
                        "GRANITE", "DIORITE", "ANDESITE",
                        "DEEPSLATE", "COBBLED_DEEPSLATE",
                        "TUFF", "CALCITE",
                        "BLACKSTONE", "BASALT", "SMOOTH_BASALT",
                        "SANDSTONE", "RED_SANDSTONE"
                );
                yield s;
            }

            case "DIRT_LIKE" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                addIfExists(s,
                        "DIRT", "COARSE_DIRT", "ROOTED_DIRT",
                        "GRASS_BLOCK", "PODZOL", "MYCELIUM",
                        "MUD", "MUDDY_MANGROVE_ROOTS"
                );
                yield s;
            }

            case "SAND_LIKE" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                addIfExists(s, "SAND", "RED_SAND");
                yield s;
            }

            case "GRAVEL" -> EnumSet.of(Material.GRAVEL);
            case "CLAY" -> EnumSet.of(Material.CLAY);

            case "SNOW" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                addIfExists(s, "SNOW", "SNOW_BLOCK");
                yield s;
            }

            case "ICE_LIKE" -> materialsMatching(name ->
                    name.equals("ICE") || name.endsWith("_ICE")
            );

            // ===== ORES =====
            // Includes DEEPSLATE_*_ORE and nether ores because they end with _ORE
            case "ORES" -> {
                Set<Material> ores = materialsMatching(name -> name.endsWith("_ORE"));

                // Optional: treat Ancient Debris as an ore (comment out if you don't want it under "ore")
                ores.add(Material.ANCIENT_DEBRIS);

                yield ores;
            }

            // Specific ore groups (normal + deepslate)
            case "COAL_ORES" -> materialsMatching(name -> name.endsWith("COAL_ORE"));
            case "COPPER_ORES" -> materialsMatching(name -> name.endsWith("COPPER_ORE"));
            case "IRON_ORES" -> materialsMatching(name -> name.endsWith("IRON_ORE"));
            case "GOLD_ORES" -> materialsMatching(name -> name.endsWith("GOLD_ORE"));
            case "REDSTONE_ORES" -> materialsMatching(name -> name.endsWith("REDSTONE_ORE"));
            case "LAPIS_ORES" -> materialsMatching(name -> name.endsWith("LAPIS_ORE"));
            case "DIAMOND_ORES" -> materialsMatching(name -> name.endsWith("DIAMOND_ORE"));
            case "EMERALD_ORES" -> materialsMatching(name -> name.endsWith("EMERALD_ORE"));

            // Single blocks some people call out
            case "NETHER_QUARTZ_ORE" -> EnumSet.of(Material.NETHER_QUARTZ_ORE);
            case "ANCIENT_DEBRIS" -> EnumSet.of(Material.ANCIENT_DEBRIS);

            // ===== NETHER / END =====
            case "NETHERRACK" -> EnumSet.of(Material.NETHERRACK);

            case "SOUL_BLOCKS" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                addIfExists(s, "SOUL_SAND", "SOUL_SOIL");
                yield s;
            }

            case "END_STONE" -> EnumSet.of(Material.END_STONE);

            // ===== BUILDING MATERIALS =====
            case "GLASS" -> materialsMatching(name ->
                    name.equals("GLASS") || name.endsWith("_STAINED_GLASS")
            );

            case "GLASS_PANES" -> materialsMatching(name ->
                    name.equals("GLASS_PANE") || name.endsWith("_STAINED_GLASS_PANE")
            );

            // Broad, human-friendly “bricks”
            case "BRICKS" -> materialsMatching(name ->
                    name.contains("BRICK")
                            && !name.contains("BRIGHT") // just in case
            );

            case "CONCRETE" -> materialsMatching(name -> name.endsWith("_CONCRETE"));
            case "TERRACOTTA" -> materialsMatching(name -> name.equals("TERRACOTTA") || name.endsWith("_TERRACOTTA"));
            case "WOOL" -> materialsMatching(name -> name.endsWith("_WOOL"));

            // ===== LIQUIDS =====
            case "WATER" -> EnumSet.of(Material.WATER);
            case "LAVA" -> EnumSet.of(Material.LAVA);

            // ===== SPECIAL =====
            case "BEDROCK" -> EnumSet.of(Material.BEDROCK);

            default -> EnumSet.noneOf(Material.class);
        };
    }

    /**
     * Scans all Materials and selects block materials by Material.name() pattern.
     * This makes categories future-proof across Minecraft/Paper updates.
     */
    private Set<Material> materialsMatching(java.util.function.Predicate<String> namePredicate) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (Material mat : Material.values()) {
            if (!mat.isBlock()) continue;
            String name = mat.name(); // uppercase
            if (namePredicate.test(name)) {
                result.add(mat);
            }
        }
        return result;
    }

    /**
     * Adds Material constants by string name if they exist in this server version.
     * Prevents hard crashes if blocks differ between versions.
     */
    private void addIfExists(Set<Material> set, String... materialNames) {
        for (String n : materialNames) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) {
                set.add(m);
            }
        }
    }
    //endregion

    //region HTTP server
    private void startHttpServer() {
        if (!httpEnabled) {
            getLogger().info("HTTP ingress disabled (http.enabled=false).");
            return;
        }

        if (httpToken == null || httpToken.isBlank() || "CHANGE_ME".equalsIgnoreCase(httpToken)) {
            getLogger().warning("HTTP token is blank/unsafe. Set http.token in config.yml!");
            return;
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpHost, httpPort), 0);
            httpServer.createContext("/speak", this::handleSpeak);
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
            httpServer.start();

            getLogger().info("HTTP ingress listening on http://" + httpHost + ":" + httpPort + "/speak");
        } catch (IOException e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            getLogger().info("HTTP ingress stopped.");
        }
    }

    private void handleSpeak(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                reply(exchange, 405, "method_not_allowed");
                return;
            }

            String tokenHeader = exchange.getRequestHeaders().getFirst("X-DeathSpeak-Token");
            if (tokenHeader == null || !tokenHeader.equals(httpToken)) {
                reply(exchange, 401, "unauthorized");
                return;
            }

            String body = readAll(exchange.getRequestBody()).trim();
            if (body.isBlank()) {
                reply(exchange, 400, "empty");
                return;
            }

            // Jump to the main server thread before touching Bukkit/world
            Bukkit.getScheduler().runTask(this, () -> {
                broadcast("[DeathSpeak] (HTTP) \"" + body + "\" has been spoken...");
                queueWord(body, "HTTP");
            });

            reply(exchange, 200, "ok");
        } catch (Exception e) {
            getLogger().warning("HTTP /speak error: " + e.getMessage());
            reply(exchange, 500, "error");
        } finally {
            exchange.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange exchange, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
//endregion

}

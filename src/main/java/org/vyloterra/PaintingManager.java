package org.vyloterra;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.vyloterra.util.ArtEffects;
import org.vyloterra.util.ArtMath;
import org.vyloterra.util.ColorUtil;
import org.vyloterra.util.DataManager;
import org.vyloterra.util.Lang;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PaintingManager {

    private final ArtMapPlugin plugin;

    // --- CONFIG CACHE ---
    private int brushMedium, brushLarge;
    private double paintDistance;
    private boolean allowPipette, allowEraser, allowReset;
    private boolean enableParticles, enableHandSwing;
    private int autosaveInterval;

    // Survival & Worlds
    private boolean consumeDyes;
    private int consumptionChance;
    private List<String> allowedWorlds;
    private double cooldownPipetteTime;
    private double cooldownResetTime;

    // --- SESSION DATA ---
    private final Map<UUID, Long> lastInteractionTime = new ConcurrentHashMap<>();
    private final Map<UUID, ItemFrame> activeFrames = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> lastPixelPositions = new HashMap<>();
    private final Map<UUID, Integer> brushLevels = new HashMap<>();
    private final Set<Integer> dirtyMaps = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Random random = new Random();

    // --- COOLDOWNS ---
    private final Map<UUID, Long> cooldownPipette = new HashMap<>();
    private final Map<UUID, Long> cooldownReset = new HashMap<>();

    // --- UNDO SYSTÉM (HISTORIE) ---
    private final Map<Integer, Deque<byte[][]>> undoHistory = new ConcurrentHashMap<>();
    private int maxUndoSteps;

    private BukkitTask saveTask;

    public PaintingManager(ArtMapPlugin plugin) {
        this.plugin = plugin;
        reloadValues();
        startPaintingLoop();
    }

    public void reloadValues() {
        FileConfiguration config = plugin.getConfig();

        this.brushMedium = config.getInt("brush-sizes.medium", 2);
        this.brushLarge = config.getInt("brush-sizes.large", 5);
        this.paintDistance = config.getDouble("gameplay.paint-distance", 5.0);
        this.allowPipette = config.getBoolean("gameplay.allow-pipette", true);
        this.allowEraser = config.getBoolean("gameplay.allow-eraser", true);
        this.allowReset = config.getBoolean("gameplay.allow-reset", true);
        this.enableParticles = config.getBoolean("visuals.enable-particles", true);
        this.enableHandSwing = config.getBoolean("visuals.enable-hand-swing", true);
        this.autosaveInterval = config.getInt("performance.autosave-interval", 30);
        this.allowedWorlds = config.getStringList("allowed-worlds");
        this.cooldownPipetteTime = config.getDouble("cooldowns.pipette", 1.0);
        this.cooldownResetTime = config.getDouble("cooldowns.reset", 10.0);
        this.consumeDyes = config.getBoolean("survival.consume-dyes", true);
        this.consumptionChance = config.getInt("survival.consumption-chance", 20);
        this.maxUndoSteps = config.getInt("performance.undo-steps", 50);
        if (this.consumptionChance < 1) this.consumptionChance = 1;

        startSaveTask();
    }

    private void startSaveTask() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
        long period = autosaveInterval * 20L;
        saveTask = new BukkitRunnable() {
            @Override
            public void run() { saveDirtyMaps(); }
        }.runTaskTimer(plugin, period, period);
    }

    // Volá se při vypnutí pluginu
    public void forceSaveAll() {
        if (dirtyMaps.isEmpty()) return;
        Iterator<Integer> it = dirtyMaps.iterator();
        while (it.hasNext()) {
            Integer mapId = it.next();
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(mapId);
            if (view != null) {
                for (org.bukkit.map.MapRenderer r : view.getRenderers()) {
                    if (r instanceof ArtRenderer artRenderer) {
                        DataManager.saveMapSync(mapId, artRenderer.getPixelsSnapshot(), plugin.getDataFolder());
                        break;
                    }
                }
            }
            it.remove();
        }
    }

    private void createUndoSnapshot(Player player, ItemFrame frame) {
        MapView view = getOrConvertMapView(frame, player, false);
        if (view == null) return;
        ArtRenderer renderer = getOrCreateRenderer(view);

        undoHistory.putIfAbsent(view.getId(), new ArrayDeque<>());
        Deque<byte[][]> history = undoHistory.get(view.getId());

        // ZMĚNA 3: Použití proměnné místo konstanty
        if (history.size() >= maxUndoSteps) {
            history.removeFirst();
        }

        history.addLast(renderer.getPixelsSnapshot());
    }

    public boolean performUndo(Player player, int mapId) {
        if (undoHistory.containsKey(mapId)) {
            Deque<byte[][]> history = undoHistory.get(mapId);
            if (history != null && !history.isEmpty()) {
                @SuppressWarnings("deprecation")
                MapView view = Bukkit.getMap(mapId);
                if (view != null) {
                    ArtRenderer renderer = getOrCreateRenderer(view);
                    byte[][] oldPixels = history.removeLast();
                    renderer.loadPixels(oldPixels);
                    dirtyMaps.add(mapId);
                    player.sendMap(view);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWorldAllowed(String worldName) {
        if (allowedWorlds == null || allowedWorlds.isEmpty()) return true;
        return allowedWorlds.contains(worldName);
    }

    // Voláno z ProtocolLib
    public void updatePaintingState(Player player, ItemFrame frame) {
        if (!isWorldAllowed(player.getWorld().getName()) && !player.hasPermission("artmap.bypass.worlds")) {
            if (!activeFrames.containsKey(player.getUniqueId())) {
                Lang.sendActionBar(player, "world-disabled", null);
            }
            return;
        }

        if (!activeFrames.containsKey(player.getUniqueId())) {
            createUndoSnapshot(player, frame);
        }

        lastInteractionTime.put(player.getUniqueId(), System.currentTimeMillis());
        activeFrames.put(player.getUniqueId(), frame);
    }

    // Hlavní smyčka malování
    private void startPaintingLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = lastInteractionTime.entrySet().iterator();

                while (it.hasNext()) {
                    Map.Entry<UUID, Long> entry = it.next();
                    UUID uuid = entry.getKey();

                    if (now - entry.getValue() > 150) {
                        it.remove();
                        activeFrames.remove(uuid);
                        lastPixelPositions.remove(uuid);
                        continue;
                    }

                    Player player = Bukkit.getPlayer(uuid);
                    ItemFrame frame = activeFrames.get(uuid);

                    if (player != null && frame != null && frame.isValid()) {
                        paintTick(player, frame);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // Voláno z ArtListener při shiftování
    public void handleBrushChange(Player player) {
        lastPixelPositions.remove(player.getUniqueId());

        int currentLevel = brushLevels.getOrDefault(player.getUniqueId(), 0);
        int nextLevel = getNextAllowedLevel(player, currentLevel);

        brushLevels.put(player.getUniqueId(), nextLevel);

        String sizeNameKey = switch (nextLevel) {
            case 0 -> "brush-names.small";
            case 1 -> "brush-names.medium";
            default -> "brush-names.large";
        };

        Lang.sendActionBar(player, "brush-size", Lang.getRaw(sizeNameKey));
        if (enableParticles) {
            ArtEffects.playBrushChangeEffect(player, nextLevel);
        } else {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        }
    }

    private int getNextAllowedLevel(Player player, int current) {
        for (int i = 1; i <= 3; i++) {
            int target = (current + i) % 3;
            if (target == 0) return 0;
            if (target == 1 && player.hasPermission("artmap.brush.medium")) return 1;
            if (target == 2 && player.hasPermission("artmap.brush.large")) return 2;
        }
        return 0;
    }

    // Voláno z ArtListener při odchodu
    public void endSession(Player player) {
        activeFrames.remove(player.getUniqueId());
        lastInteractionTime.remove(player.getUniqueId());
        brushLevels.remove(player.getUniqueId());
        lastPixelPositions.remove(player.getUniqueId());
        cooldownPipette.remove(player.getUniqueId());
        cooldownReset.remove(player.getUniqueId());
    }

    private boolean checkCooldown(Player player, Map<UUID, Long> cooldownMap, double seconds) {
        if (player.hasPermission("artmap.bypass.cooldown") || player.hasPermission("artmap.admin")) return true;

        long now = System.currentTimeMillis();
        long lastUse = cooldownMap.getOrDefault(player.getUniqueId(), 0L);
        long cooldownMillis = (long) (seconds * 1000);

        if (now - lastUse < cooldownMillis) {
            double remaining = (cooldownMillis - (now - lastUse)) / 1000.0;
            Lang.sendActionBar(player, "cooldown-wait", String.format("%.1f", remaining));
            return false;
        }

        cooldownMap.put(player.getUniqueId(), now);
        return true;
    }

    // HLAVNÍ LOGIKA KRESLENÍ
    private void paintTick(Player player, ItemFrame frame) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        // Info click (pokud hráč nemá nic v rukou)
        if (hand.getType().isAir() && offHand.getType().isAir()) {
            ItemStack mapItem = frame.getItem();
            if (mapItem.hasItemMeta() && mapItem.getItemMeta().hasDisplayName()) {
                String title = LegacyComponentSerializer.legacyAmpersand().serialize(mapItem.getItemMeta().displayName());
                String author = "";
                if (mapItem.getItemMeta().hasLore()) {
                    List<String> lore = mapItem.getItemMeta().getLore();
                    if (lore != null && !lore.isEmpty()) {
                        author = " " + lore.get(0);
                    }
                }
                Lang.sendActionBar(player, "prefix", "&e" + title + "&r" + author);
            }
            return;
        }

        // Kapátko
        if (hand.getType() == Material.FEATHER) {
            if (allowPipette) {
                if (checkCooldown(player, cooldownPipette, cooldownPipetteTime)) {
                    handlePipette(player, frame);
                }
            } else {
                Lang.sendActionBar(player, "tool-disabled", null);
            }
            return;
        }

        // Reset plátna
        if (hand.getType() == Material.WET_SPONGE) {
            if (!allowReset) {
                Lang.sendActionBar(player, "tool-disabled", null);
                return;
            }
            if (!checkCooldown(player, cooldownReset, cooldownResetTime)) return;

            RayTraceResult result = frame.getBoundingBox().rayTrace(player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), paintDistance);
            if (result == null) return;

            MapView view = getOrConvertMapView(frame, player, true);
            if (view == null) return;

            createUndoSnapshot(player, frame);
            ArtRenderer renderer = getOrCreateRenderer(view);
            renderer.clear((byte) 34); // Bílá

            player.getWorld().playSound(result.getHitPosition().toLocation(player.getWorld()), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.2f);
            if (enableParticles) {
                player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, result.getHitPosition().toLocation(player.getWorld()), 20, 0.3, 0.3, 0.3, 0.1);
            }
            if (enableHandSwing) player.swingMainHand();

            dirtyMaps.add(view.getId());
            player.sendMap(view);
            return;
        }

        // Určení barviva
        ItemStack dyeItem = ColorUtil.isValidDye(hand.getType()) ? hand :
                (ColorUtil.isValidDye(offHand.getType()) ? offHand : null);

        boolean isEraser = false;
        if (hand.getType() == Material.SPONGE) {
            if (!allowEraser) {
                Lang.sendActionBar(player, "tool-disabled", null);
                return;
            }
            dyeItem = new ItemStack(Material.WHITE_DYE);
            isEraser = true;
        }

        if (dyeItem == null) return;

        // RayTrace na plátno
        RayTraceResult result = frame.getBoundingBox().rayTrace(
                player.getEyeLocation().toVector(),
                player.getEyeLocation().getDirection(),
                paintDistance
        );

        if (result == null) return;

        MapView view = getOrConvertMapView(frame, player, true);
        if (view == null) return;

        ArtRenderer renderer = getOrCreateRenderer(view);
        int[] coords = ArtMath.getCanvasCoordinates(
                result.getHitPosition(),
                frame.getBoundingBox().getCenter(),
                frame.getFacing()
        );

        if (coords != null) {
            // Výpočet barvy a stínování
            byte baseColor = ColorUtil.getMapColor(dyeItem.getType());
            Material secondary = (dyeItem == hand) ? offHand.getType() : hand.getType();
            byte color = ColorUtil.getShadedColor(baseColor, secondary);

            int level = brushLevels.getOrDefault(player.getUniqueId(), 0);
            int radius = switch (level) {
                case 1 -> brushMedium;
                case 2 -> brushLarge;
                default -> 0;
            };

            // Kreslení (Line nebo Point)
            int[] last = lastPixelPositions.get(player.getUniqueId());

            if (last != null && last[0] == view.getId()) {
                double distance = Math.sqrt(Math.pow(coords[0] - last[1], 2) + Math.pow(coords[1] - last[2], 2));
                if (distance > 25) {
                    ArtMath.drawPencil(renderer, coords[0], coords[1], radius, color);
                } else {
                    ArtMath.drawLine(renderer, last[1], last[2], coords[0], coords[1], radius, color);
                }
            } else {
                ArtMath.drawPencil(renderer, coords[0], coords[1], radius, color);
            }

            lastPixelPositions.put(player.getUniqueId(), new int[]{view.getId(), coords[0], coords[1]});

            // --- AUTO-REFILL LOGIKA (ZDE BLYA OPRAVA) ---
            if (player.getGameMode() != GameMode.CREATIVE && consumeDyes) {
                if (!isEraser || hand.getType() == Material.SPONGE) {
                    if (random.nextInt(consumptionChance) == 0) {
                        ItemStack toConsume = (hand.getType() == Material.SPONGE) ? hand : dyeItem;
                        Material neededType = toConsume.getType();

                        // DŮLEŽITÉ: Uložíme si, jestli jde o hlavní ruku, PŘEDTÍM než se item změní na AIR
                        boolean isMainHand = (toConsume == hand);

                        toConsume.subtract(1);

                        // Pokud item došel (je AIR), zkusíme najít náhradu
                        if (toConsume.getAmount() <= 0) {
                            toConsume.setType(Material.AIR); // Pro jistotu

                            for (int i = 0; i < player.getInventory().getSize(); i++) {
                                ItemStack is = player.getInventory().getItem(i);

                                if (is != null && is.getType() == neededType) {
                                    ItemStack replacement = is.clone();

                                    // Smažeme nalezený stack z inventáře
                                    player.getInventory().setItem(i, null);

                                    // Dáme ho do SPRÁVNÉ ruky podle uložené proměnné
                                    if (isMainHand) {
                                        player.getInventory().setItemInMainHand(replacement);
                                    } else {
                                        player.getInventory().setItemInOffHand(replacement);
                                    }

                                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
                                    break; // Hotovo
                                }
                            }
                        }
                    }
                }
            }

            if (enableParticles) {
                if (isEraser) {
                    player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, result.getHitPosition().toLocation(player.getWorld()), 1, 0.05, 0.05, 0.05, 0.01);
                } else {
                    ArtEffects.playPaintEffect(result.getHitPosition().toLocation(player.getWorld()), dyeItem.getType(), level);
                }
            }

            if (enableHandSwing) player.swingMainHand();

            dirtyMaps.add(view.getId());
            player.sendMap(view);
        }
    }

    private void handlePipette(Player player, ItemFrame frame) {
        RayTraceResult result = frame.getBoundingBox().rayTrace(
                player.getEyeLocation().toVector(),
                player.getEyeLocation().getDirection(),
                paintDistance
        );
        if (result == null) return;

        int[] coords = ArtMath.getCanvasCoordinates(
                result.getHitPosition(),
                frame.getBoundingBox().getCenter(),
                frame.getFacing()
        );

        if (coords == null) return;

        MapView view = getOrConvertMapView(frame, player, false);
        if (view == null) return;

        ArtRenderer renderer = getOrCreateRenderer(view);
        byte[][] pixels = renderer.getPixelsSnapshot();
        byte colorId = pixels[coords[0]][coords[1]];

        Material foundDye = ColorUtil.getDyeFromColor(colorId);

        String soundName = plugin.getConfig().getString("sounds.pipette", "ENTITY_CHICKEN_EGG");
        Sound soundPipette;
        try { soundPipette = Sound.valueOf(soundName); } catch (Exception e) { soundPipette = Sound.ENTITY_CHICKEN_EGG; }

        if (foundDye != null) {
            if (player.getInventory().getItemInOffHand().getType() == foundDye) return;

            int slot = player.getInventory().first(foundDye);

            if (player.getGameMode() == GameMode.CREATIVE) {
                player.getInventory().setItemInOffHand(new ItemStack(foundDye));
                player.playSound(player.getLocation(), soundPipette, 1f, 1.5f);
                Lang.sendActionBar(player, "pipette-picked", foundDye.name());
            } else if (slot != -1) {
                ItemStack dyeStack = player.getInventory().getItem(slot);
                ItemStack offHandItem = player.getInventory().getItemInOffHand();
                player.getInventory().setItem(slot, offHandItem);
                player.getInventory().setItemInOffHand(dyeStack);
                player.playSound(player.getLocation(), soundPipette, 1f, 1.5f);
                Lang.sendActionBar(player, "pipette-inv", null);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                Lang.sendActionBar(player, "pipette-fail", null);
            }
        } else {
            Lang.sendActionBar(player, "canvas-empty", null);
        }
    }

    public void saveDirtyMaps() {
        if (dirtyMaps.isEmpty()) return;
        Iterator<Integer> it = dirtyMaps.iterator();
        while (it.hasNext()) {
            Integer mapId = it.next();
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(mapId);
            if (view != null) {
                for (org.bukkit.map.MapRenderer r : view.getRenderers()) {
                    if (r instanceof ArtRenderer artRenderer) {
                        DataManager.saveMapAsync(mapId, artRenderer.getPixelsSnapshot(), plugin.getDataFolder());
                        break;
                    }
                }
            }
            it.remove();
        }
    }

    private ArtRenderer getOrCreateRenderer(MapView view) {
        for (org.bukkit.map.MapRenderer r : view.getRenderers()) {
            if (r instanceof ArtRenderer) return (ArtRenderer) r;
        }
        view.getRenderers().forEach(view::removeRenderer);
        ArtRenderer nr = new ArtRenderer(view.getId(), plugin.getDataFolder());
        view.addRenderer(nr);
        return nr;
    }

    private MapView getOrConvertMapView(ItemFrame frame, Player player, boolean createIfMissing) {
        ItemStack item = frame.getItem();
        if (item.getType() == Material.FILLED_MAP && item.hasItemMeta() && item.getItemMeta() instanceof MapMeta meta && meta.hasMapView()) return meta.getMapView();
        if (createIfMissing && item.getType() == Material.MAP) {
            MapView view = Bukkit.createMap(player.getWorld());
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new ArtRenderer(view.getId(), plugin.getDataFolder()));
            ItemStack newMap = new ItemStack(Material.FILLED_MAP);
            MapMeta mapMeta = (MapMeta) newMap.getItemMeta();
            mapMeta.setMapView(view);
            newMap.setItemMeta(mapMeta);
            frame.setItem(newMap);
            return view;
        }
        return null;
    }
}
package org.vyloterra;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PaintingManager {

    // --- ENUM PRO REŽIMY MALOVÁNÍ (Upraveno pro lokalizaci) ---
    public enum PaintMode {
        FREEHAND,
        SPRAY,
        LINE,
        SQUARE,
        CIRCLE;

        // Metoda nyní dynamicky načítá text z configu
        public String getLabel() {
            // Klíč bude vypadat např.: "messages.modes.freehand"
            String key = "messages.modes." + this.name().toLowerCase();

            // Načte text přes Lang třídu (defaultní hodnota je název enumu)
            return Lang.getString(key, this.name());
        }
    }

    private final ArtMapPlugin plugin;


    // --- CONFIG CACHE ---
    private int brushMedium, brushLarge;
    private double paintDistance;
    private boolean allowPipette, allowEraser, allowReset;
    private boolean enableParticles, enableHandSwing;
    private boolean allowFill, allowGlow;
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
    private final NamespacedKey keyCreated;


    // --- NOVÁ DATA PRO TVARY ---
    private final Map<UUID, PaintMode> playerModes = new HashMap<>();
    private final Map<UUID, int[]> shapeStartPoints = new HashMap<>(); // Ukládá Bod A [x, y]

    // --- COOLDOWNS ---
    private final Map<UUID, Long> cooldownPipette = new HashMap<>();
    private final Map<UUID, Long> cooldownReset = new HashMap<>();
    private final Map<UUID, Long> lastRegionCheck = new HashMap<>();
    // --- UNDO SYSTÉM ---
    private final Map<Integer, Deque<byte[][]>> undoHistory = new ConcurrentHashMap<>();
    private int maxUndoSteps;

    private BukkitTask saveTask;

    public PaintingManager(ArtMapPlugin plugin) {
        this.plugin = plugin;
        this.keyCreated = new NamespacedKey(plugin, "artmap_created");

        reloadValues();
        startPaintingLoop();
    }

    public Map<UUID, ItemFrame> getActiveFrames() {
        return activeFrames;
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
        this.allowFill = config.getBoolean("gameplay.allow-fill", true);
        this.allowGlow = config.getBoolean("gameplay.allow-glow", true);
        this.maxUndoSteps = config.getInt("performance.undo-steps", 50);
        if (this.consumptionChance < 1) this.consumptionChance = 1;

        startSaveTask();
    }

    // --- PŘEPÍNÁNÍ REŽIMŮ ---
    public void cyclePaintMode(Player player) {
        PaintMode current = playerModes.getOrDefault(player.getUniqueId(), PaintMode.FREEHAND);
        PaintMode next = PaintMode.values()[(current.ordinal() + 1) % PaintMode.values().length];

        playerModes.put(player.getUniqueId(), next);
        shapeStartPoints.remove(player.getUniqueId()); // Reset bodu při změně

        Lang.sendActionBar(player, "prefix", "&eRežim: &f" + next.getLabel());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
    }

    // --- HLAVNÍ UPDATE (Pravé kliknutí) ---
    public void updatePaintingState(Player player, ItemFrame frame) {
        if (!isWorldAllowed(player.getWorld().getName()) && !player.hasPermission("artmap.bypass.worlds")) {
            if (!activeFrames.containsKey(player.getUniqueId())) Lang.sendActionBar(player, "world-disabled", null);
            return;
        }

        if (!canPaintAtLocation(player, frame.getLocation())) {
            if (!activeFrames.containsKey(player.getUniqueId())) Lang.sendActionBar(player, "region-disabled", null);
            return;
        }

        ensureCreationTime(frame);

        ItemStack mapItem = frame.getItem();
        if (mapItem.hasItemMeta()) {
            ItemMeta meta = mapItem.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "last_editor"), PersistentDataType.STRING, player.getName());
            mapItem.setItemMeta(meta);
            frame.setItem(mapItem);
        }

        ItemStack hand = player.getInventory().getItemInMainHand();

        // GLOW INK SAC
        if (hand.getType() == Material.GLOW_INK_SAC) {
            if (allowGlow && !frame.isGlowing()) {
                frame.setGlowing(true);
                player.playSound(frame.getLocation(), Sound.ITEM_GLOW_INK_SAC_USE, 1.0f, 1.0f);
                if (player.getGameMode() != GameMode.CREATIVE) hand.subtract(1);
            }
            return;
        }

        // KYBLÍK
        if (hand.getType() == Material.WATER_BUCKET) {
            if (!allowFill) {
                Lang.sendActionBar(player, "tool-disabled", null);
                return;
            }
            handleBucketFill(player, frame);
            return;
        }

        // START MALOVÁNÍ
        PaintMode mode = playerModes.getOrDefault(player.getUniqueId(), PaintMode.FREEHAND);

        if (mode == PaintMode.FREEHAND || mode == PaintMode.SPRAY) {
            // Freehand/Sprej řeší smyčka paintTick
            if (!activeFrames.containsKey(player.getUniqueId())) {
                createUndoSnapshot(player, frame);
            }
            lastInteractionTime.put(player.getUniqueId(), System.currentTimeMillis());
            activeFrames.put(player.getUniqueId(), frame);
        } else {
            // Tvary (Čára, Čtverec...) řešíme kliknutím
            handleShapeClick(player, frame, mode);
        }
    }

    // --- Logika pro tvary (2 kliknutí) ---
    private void handleShapeClick(Player player, ItemFrame frame, PaintMode mode) {
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

        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack dyeItem = ColorUtil.isValidDye(hand.getType()) ? hand : (ColorUtil.isValidDye(offHand.getType()) ? offHand : null);

        if (dyeItem == null && hand.getType() != Material.SPONGE) return;

        if (!shapeStartPoints.containsKey(player.getUniqueId())) {
            // 1. Kliknutí: Bod A
            shapeStartPoints.put(player.getUniqueId(), coords);
            Lang.sendActionBar(player, "prefix", "&aBod A nastaven. Klikni pro bod B.");
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 2.0f);
        } else {
            // 2. Kliknutí: Vykreslení
            int[] start = shapeStartPoints.remove(player.getUniqueId());

            MapView view = getOrConvertMapView(frame, player, true);
            if (view == null) return;

            createUndoSnapshot(player, frame);
            ArtRenderer renderer = getOrCreateRenderer(view);

            byte color;
            if (hand.getType() == Material.SPONGE) {
                color = 0;
            } else {
                byte baseColor = ColorUtil.getMapColor(dyeItem.getType());
                Material secondary = (dyeItem == hand) ? offHand.getType() : hand.getType();
                color = ColorUtil.getShadedColor(baseColor, secondary);
            }

            int radius = getBrushRadius(player);

            switch (mode) {
                case LINE: ArtMath.drawLine(renderer, start[0], start[1], coords[0], coords[1], radius, color); break;
                case SQUARE: ArtMath.drawRectangle(renderer, start[0], start[1], coords[0], coords[1], radius, color); break;
                case CIRCLE: ArtMath.drawCircle(renderer, start[0], start[1], coords[0], coords[1], radius, color); break;
            }

            consumeDye(player, hand, dyeItem);

            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
            player.sendMap(view);
            dirtyMaps.add(view.getId());

            Lang.sendActionBar(player, "prefix", "&aTvar vykreslen!");
        }
    }

    private void handleBucketFill(Player player, ItemFrame frame) {
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (ColorUtil.isValidDye(offHand.getType())) {
            RayTraceResult result = frame.getBoundingBox().rayTrace(
                    player.getEyeLocation().toVector(),
                    player.getEyeLocation().getDirection(),
                    paintDistance
            );

            if (result != null) {
                int[] coords = ArtMath.getCanvasCoordinates(result.getHitPosition(), frame.getBoundingBox().getCenter(), frame.getFacing());
                if (coords != null) {
                    MapView view = getOrConvertMapView(frame, player, true);
                    if (view != null) {
                        createUndoSnapshot(player, frame);
                        ArtRenderer renderer = getOrCreateRenderer(view);
                        byte fillColor = ColorUtil.getMapColor(offHand.getType());

                        ArtMath.runFloodFill(renderer, coords[0], coords[1], fillColor);

                        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1.0f, 1.0f);
                        player.sendMap(view);
                    }
                }
            }
        } else {
            Lang.sendActionBar(player, "prefix", "&cPro vyplnění drž barvivo v druhé ruce!");
        }
    }

    // --- HLAVNÍ SMYČKA ---
    private void startPaintingLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = lastInteractionTime.entrySet().iterator();

                while (it.hasNext()) {
                    Map.Entry<UUID, Long> entry = it.next();
                    UUID uuid = entry.getKey();

                    // Timeout po 250ms neaktivity
                    if (now - entry.getValue() > 250) {
                        it.remove();
                        removePainterData(uuid);
                        continue;
                    }

                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        it.remove();
                        removePainterData(uuid);
                        continue;
                    }

                    ItemFrame frame = activeFrames.get(uuid);
                    if (frame == null || !frame.isValid()) {
                        it.remove();
                        removePainterData(uuid);
                        continue;
                    }

                    PaintMode mode = playerModes.getOrDefault(uuid, PaintMode.FREEHAND);

                    // Pokud je ve Freehand nebo Spray módu -> Kreslí
                    if (mode == PaintMode.FREEHAND || mode == PaintMode.SPRAY) {
                        paintTick(player, frame);
                    } else {
                        // Pokud je v módu tvarů -> Ukazuje náhled
                        if (shapeStartPoints.containsKey(uuid)) {
                            showShapePreview(player, frame);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void removePainterData(UUID uuid) {
        activeFrames.remove(uuid);
        lastPixelPositions.remove(uuid);
    }

    private void showShapePreview(Player player, ItemFrame frame) {
        RayTraceResult result = frame.getBoundingBox().rayTrace(
                player.getEyeLocation().toVector(),
                player.getEyeLocation().getDirection(),
                paintDistance
        );

        if (result != null) {
            Location hitLoc = result.getHitPosition().toLocation(player.getWorld());
            hitLoc.add(frame.getFacing().getDirection().multiply(0.05));
            player.spawnParticle(Particle.DUST, hitLoc, 1, 0.0, 0.0, 0.0, new Particle.DustOptions(Color.RED, 0.5f));
        }
    }

    // --- KRESLENÍ (1 TICK) ---
    // KRESLENÍ JEDEN TICK (Volná ruka a Sprej)
    private void paintTick(Player player, ItemFrame frame) {
        // 1. Ochrana: Kontrola regionu (WorldGuard) jednou za sekundu
        // Zabrání hráči vejít do cizího regionu se stisknutým tlačítkem a malovat dál.
        long now = System.currentTimeMillis();
        if (now - lastRegionCheck.getOrDefault(player.getUniqueId(), 0L) > 1000) {
            RayTraceResult checkResult = frame.getBoundingBox().rayTrace(
                    player.getEyeLocation().toVector(),
                    player.getEyeLocation().getDirection(),
                    paintDistance
            );

            if (checkResult != null) {
                Location hitLoc = checkResult.getHitPosition().toLocation(player.getWorld());
                if (!canPaintAtLocation(player, hitLoc)) {
                    endSession(player); // Ukončit malování
                    Lang.sendActionBar(player, "region-disabled", "&cZde nemůžeš malovat!");
                    return;
                }
            }
            lastRegionCheck.put(player.getUniqueId(), now);
        }

        PaintMode mode = playerModes.getOrDefault(player.getUniqueId(), PaintMode.FREEHAND);

        // Zde povolíme i SPRAY
        if (mode != PaintMode.FREEHAND && mode != PaintMode.SPRAY) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        // 2. Info o obraze (pokud má prázdné ruce)
        if (hand.getType().isAir() && offHand.getType().isAir()) {
            displayMapInfo(player, frame);
            return;
        }

        // 3. Nástroje (Kapátko, Reset)
        if (hand.getType() == Material.FEATHER) {
            if (allowPipette && checkCooldown(player, cooldownPipette, cooldownPipetteTime)) handlePipette(player, frame);
            return;
        }
        if (hand.getType() == Material.WET_SPONGE) {
            if (allowReset && checkCooldown(player, cooldownReset, cooldownResetTime)) handleReset(player, frame);
            return;
        }

        // 4. Detekce Gumy a Barviva
        ItemStack dyeItem = ColorUtil.isValidDye(hand.getType()) ? hand :
                (ColorUtil.isValidDye(offHand.getType()) ? offHand : null);

        boolean isEraser = false;
        if (hand.getType() == Material.SPONGE) {
            if (!allowEraser) {
                Lang.sendActionBar(player, "tool-disabled", null);
                return;
            }
            dyeItem = new ItemStack(Material.WHITE_DYE); // Dummy item pro logiku
            isEraser = true;
        }

        if (dyeItem == null) return; // Nemá barvu -> nic

        // 5. RayTrace (Kam se dívá?)
        RayTraceResult result = frame.getBoundingBox().rayTrace(
                player.getEyeLocation().toVector(),
                player.getEyeLocation().getDirection(),
                paintDistance
        );

        if (result == null) return;

        // 6. Příprava Rendereru
        MapView view = getOrConvertMapView(frame, player, true);
        if (view == null) return;

        ArtRenderer renderer = getOrCreateRenderer(view);
        int[] coords = ArtMath.getCanvasCoordinates(
                result.getHitPosition(),
                frame.getBoundingBox().getCenter(),
                frame.getFacing()
        );

        if (coords != null) {
            // --- VÝPOČET BARVY A EFEKTŮ ---
            byte primaryColor;
            byte secondaryColor = 0;
            boolean isMixing = false; // Režim ditheringu (šachovnice)

            if (isEraser) {
                primaryColor = 0; // Průhledná
            } else {
                byte baseColor = ColorUtil.getMapColor(dyeItem.getType());
                ItemStack otherHandItem = (dyeItem == hand) ? offHand : hand;

                // Pokud v druhé ruce drží JINÉ BARVIVO -> Zapínáme Míchání
                if (ColorUtil.isValidDye(otherHandItem.getType()) && otherHandItem.getType() != dyeItem.getType()) {
                    primaryColor = baseColor;
                    secondaryColor = ColorUtil.getMapColor(otherHandItem.getType());
                    isMixing = true;
                }
                // Jinak klasické stínování (Uhlí, Pírko, Flint)
                else {
                    primaryColor = ColorUtil.getShadedColor(baseColor, otherHandItem.getType());
                }
            }

            int radius = getBrushRadius(player);

            // --- VYKRESLOVÁNÍ ---

            if (mode == PaintMode.SPRAY) {
                // SPREJ:
                int density = (radius + 1) * 3;
                ArtMath.drawSpray(renderer, coords[0], coords[1], radius + 2, primaryColor, density);
                lastPixelPositions.remove(player.getUniqueId()); // U spreje neinterpolujeme

            } else {
                // VOLNÁ RUKA (Tužka / Míchání):
                int[] last = lastPixelPositions.get(player.getUniqueId());

                if (last != null && last[0] == view.getId()) {
                    double distance = Math.sqrt(Math.pow(coords[0] - last[1], 2) + Math.pow(coords[1] - last[2], 2));

                    // Interpolace (spojování čar, aby nebyly mezery při rychlém pohybu)
                    if (distance > 0 && distance < 40) {
                        if (isMixing) {
                            // Míchání: pouze body (čára by zkazila šachovnici)
                            ArtMath.drawMix(renderer, coords[0], coords[1], radius, primaryColor, secondaryColor);
                        } else {
                            // Klasická čára
                            ArtMath.drawLine(renderer, last[1], last[2], coords[0], coords[1], radius, primaryColor);
                        }
                    } else {
                        // Skok nebo stání na místě
                        if (isMixing) ArtMath.drawMix(renderer, coords[0], coords[1], radius, primaryColor, secondaryColor);
                        else ArtMath.drawPencil(renderer, coords[0], coords[1], radius, primaryColor);
                    }
                } else {
                    // První kliknutí
                    if (isMixing) ArtMath.drawMix(renderer, coords[0], coords[1], radius, primaryColor, secondaryColor);
                    else ArtMath.drawPencil(renderer, coords[0], coords[1], radius, primaryColor);
                }

                lastPixelPositions.put(player.getUniqueId(), new int[]{view.getId(), coords[0], coords[1]});
            }

            // 7. Spotřeba a Efekty
            if (!isEraser || hand.getType() == Material.SPONGE) {
                consumeDye(player, hand, dyeItem);
            }

            // OPTIMALIZACE ČÁSTIC: Zobrazit jen každý 4. tick (cca každých 200ms)
            // Tím se zabrání FPS dropům u klientů.
            if (enableParticles && (System.currentTimeMillis() / 50) % 4 == 0) {
                Location loc = result.getHitPosition().toLocation(player.getWorld());
                if (isEraser) {
                    player.getWorld().spawnParticle(Particle.CLOUD, loc, 1, 0.05, 0.05, 0.05, 0.01);
                } else if (mode == PaintMode.SPRAY) {
                    player.getWorld().spawnParticle(Particle.ITEM, loc, 1, 0.2, 0.2, 0.2, 0.05, new ItemStack(dyeItem.getType()));
                } else {
                    ArtEffects.playPaintEffect(loc, dyeItem.getType(), radius);
                }
            }

            if (enableHandSwing) player.swingMainHand();

            dirtyMaps.add(view.getId());
            player.sendMap(view);
        }
    }

    private void consumeDye(Player player, ItemStack hand, ItemStack dyeItem) {
        if (player.getGameMode() == GameMode.CREATIVE || !consumeDyes) return;
        if (random.nextInt(consumptionChance) == 0) {
            ItemStack toConsume = (hand.getType() == Material.SPONGE) ? hand : dyeItem;
            Material neededType = toConsume.getType();
            boolean isMainHand = (toConsume == hand);
            toConsume.subtract(1);
            if (toConsume.getAmount() <= 0) {
                toConsume.setType(Material.AIR);
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack is = player.getInventory().getItem(i);
                    if (is != null && is.getType() == neededType) {
                        ItemStack replacement = is.clone();
                        player.getInventory().setItem(i, null);
                        if (isMainHand) player.getInventory().setItemInMainHand(replacement);
                        else player.getInventory().setItemInOffHand(replacement);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
                        break;
                    }
                }
            }
        }
    }

    private void handleReset(Player player, ItemFrame frame) {
        RayTraceResult result = frame.getBoundingBox().rayTrace(player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), paintDistance);
        if (result == null) return;
        MapView view = getOrConvertMapView(frame, player, true);
        if (view == null) return;
        createUndoSnapshot(player, frame);
        ArtRenderer renderer = getOrCreateRenderer(view);
        renderer.clear((byte) 34); // Bílá
        player.getWorld().playSound(result.getHitPosition().toLocation(player.getWorld()), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.2f);
        if (enableParticles) player.getWorld().spawnParticle(Particle.SPLASH, result.getHitPosition().toLocation(player.getWorld()), 20, 0.3, 0.3, 0.3, 0.1);
        if (enableHandSwing) player.swingMainHand();
        dirtyMaps.add(view.getId());
        player.sendMap(view);
    }

    private void handlePipette(Player player, ItemFrame frame) {
        RayTraceResult result = frame.getBoundingBox().rayTrace(player.getEyeLocation().toVector(), player.getEyeLocation().getDirection(), paintDistance);
        if (result == null) return;
        int[] coords = ArtMath.getCanvasCoordinates(result.getHitPosition(), frame.getBoundingBox().getCenter(), frame.getFacing());
        if (coords == null) return;
        MapView view = getOrConvertMapView(frame, player, false);
        if (view == null) return;
        ArtRenderer renderer = getOrCreateRenderer(view);
        byte colorId = renderer.getPixel(coords[0], coords[1]);
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

    private void displayMapInfo(Player player, ItemFrame frame) {
        ItemStack mapItem = frame.getItem();

        // 1. Rychlý check, zda má smysl pokračovat
        if (!mapItem.hasItemMeta()) return;

        // 2. Uložíme si meta do proměnné (voláme jen jednou!)
        ItemMeta meta = mapItem.getItemMeta();

        // 3. Kontrola DisplayName
        if (meta.hasDisplayName()) {
            // Serializace Componentu na String s & kódy
            String title = LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName());
            String author = "";

            // 4. Bezpečné získání Lore
            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null && !lore.isEmpty()) {
                    // Přidání autora (pokud tam je)
                    author = " " + lore.get(0);
                }
            }

            // Odeslání
            Lang.sendActionBar(player, "prefix", "&e" + title + "&r" + author);
        }
    }

// --- UKLÁDÁNÍ A UNDO ---

    private void ensureCreationTime(ItemFrame frame) {
        ItemStack item = frame.getItem();
        // Rychlá kontrola typu, abychom zbytečně nevolali getItemMeta()
        if (item.getType() != Material.FILLED_MAP && item.getType() != Material.MAP) {
            return;
        }

        // Získáme meta jen jednou
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Kontrola, zda už klíč existuje
        if (!meta.getPersistentDataContainer().has(keyCreated, PersistentDataType.LONG)) {
            // Nastavíme čas
            meta.getPersistentDataContainer().set(keyCreated, PersistentDataType.LONG, System.currentTimeMillis());
            item.setItemMeta(meta);
            frame.setItem(item); // Aktualizujeme frame
        }
    }

    private void startSaveTask() {
        if (saveTask != null && !saveTask.isCancelled()) {
            saveTask.cancel();
        }

        // Validace intervalu (prevence chyby, pokud je v configu 0 nebo méně)
        long interval = Math.max(autosaveInterval, 60) * 20L; // Minimum 60 sekund

        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveDirtyMaps();
            }
        }.runTaskTimer(plugin, interval, interval);
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
                        // OPRAVA: Přidán 'null' jako callback, pokud ho DataManager vyžaduje
                        // DŮLEŽITÉ: getPixelsSnapshot() musí vracet KOPII pole (clone)
                        DataManager.saveMapAsync(mapId, artRenderer.getPixelsSnapshot(), plugin.getDataFolder(), null);
                        break;
                    }
                }
            }
            // Odebereme ze seznamu "špinavých" map, protože se už ukládá
            it.remove();
        }
    }

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
                        // Zde používáme synchronní uložení (blokuje server, ale zajistí data při vypnutí)
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
        if (history.size() >= maxUndoSteps) history.removeFirst();
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

    private boolean canPaintAtLocation(Player player, org.bukkit.Location location) {
        if (player.hasPermission("artmap.admin.bypass-regions")) return true;
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                com.sk89q.worldedit.util.Location weLocation = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location);
                com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
                com.sk89q.worldguard.protection.regions.RegionQuery query = wg.getPlatform().getRegionContainer().createQuery();
                com.sk89q.worldguard.LocalPlayer localPlayer = com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);
                return query.testState(weLocation, localPlayer, com.sk89q.worldguard.protection.flags.Flags.BUILD);
            } catch (NoClassDefFoundError | Exception e) { return true; }
        }
        return true;
    }

    private boolean isWorldAllowed(String worldName) {
        if (allowedWorlds == null || allowedWorlds.isEmpty()) return true;
        return allowedWorlds.contains(worldName);
    }

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
        if (enableParticles) ArtEffects.playBrushChangeEffect(player, nextLevel);
        else player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
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

    private int getBrushRadius(Player player) {
        int level = brushLevels.getOrDefault(player.getUniqueId(), 0);
        return switch (level) {
            case 1 -> brushMedium;
            case 2 -> brushLarge;
            default -> 0;
        };
    }

    public void endSession(Player player) {
        playerModes.remove(player.getUniqueId());
        shapeStartPoints.remove(player.getUniqueId());
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
}

package org.vyloterra.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vyloterra.ArtMapPlugin;
import org.vyloterra.ArtRenderer;
import org.vyloterra.PaintingManager;
import org.vyloterra.util.DataManager;
import org.vyloterra.util.Lang;


import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ArtCommand implements CommandExecutor, TabCompleter {

    private final ArtMapPlugin plugin;
    private final PaintingManager paintingManager;
    private final NamespacedKey keyLocked;
    private final NamespacedKey keyCreated;

    public ArtCommand(ArtMapPlugin plugin, PaintingManager paintingManager) {
        this.plugin = plugin;
        this.paintingManager = paintingManager;
        this.keyLocked = new NamespacedKey(plugin, "artmap_locked");
        this.keyCreated = new NamespacedKey(plugin, "artmap_created");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            for (String line : Lang.getList("help")) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // --- RELOAD ---
        if (sub.equals("reload")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            plugin.reloadValues();
            Lang.send(sender, "prefix", "&aKonfigurace byla znovu načtena!");
            return true;
        }

        // --- COPY ---
        if (sub.equals("copy")) {
            if (!(sender instanceof Player player)) {
                Lang.send(sender, "only-players");
                return true;
            }
            if (!player.hasPermission("artmap.paint")) {
                Lang.send(player, "no-permission");
                return true;
            }

            // 1. Kontrola, zda má hráč v inventáři prázdnou mapu (Material.MAP)
            // V Creative módu to nevyžadujeme.
            if (player.getGameMode() != GameMode.CREATIVE) {
                if (!player.getInventory().contains(Material.MAP)) {
                    // Pokud nemáš v configu zprávu "need-empty-map", použije se tento fallback
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cK vytvoření kopie potřebuješ &ePrázdnou mapu&c v inventáři!"));
                    return true;
                }
            }

            ItemFrame frame = getTargetItemFrame(player);
            if (frame != null && frame.getItem().getType() == Material.FILLED_MAP) {

                // 2. Odebrání mapy (cena za kopii)
                if (player.getGameMode() != GameMode.CREATIVE) {
                    player.getInventory().removeItem(new ItemStack(Material.MAP, 1));
                }

                // 3. Vytvoření kopie
                ItemStack mapItem = frame.getItem().clone();
                mapItem.setAmount(1);

                // 4. Aktualizace času na KOPII
                // Originál zůstane beze změny, kopie dostane aktuální čas jako čas vytvoření.
                ItemMeta meta = mapItem.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(keyCreated, PersistentDataType.LONG, System.currentTimeMillis());
                    mapItem.setItemMeta(meta);
                }

                HashMap<Integer, ItemStack> left = player.getInventory().addItem(mapItem);
                if (!left.isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), mapItem);
                }
                Lang.send(player, "copy-success");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            } else {
                Lang.send(player, "copy-fail");
            }
            return true;
        }

        // --- UNDO ---
        if (sub.equals("undo")) {
            if (!(sender instanceof Player player)) {
                Lang.send(sender, "only-players");
                return true;
            }
            if (!player.hasPermission("artmap.paint")) {
                Lang.send(player, "no-permission");
                return true;
            }

            ItemFrame frame = getTargetItemFrame(player);
            if (frame != null && frame.getItem().getType() == Material.FILLED_MAP) {
                if (frame.getItem().getItemMeta() instanceof MapMeta meta && meta.hasMapView()) {
                    int mapId = meta.getMapView().getId();
                    boolean success = paintingManager.performUndo(player, mapId);

                    if (success) {
                        Lang.send(player, "undo-success");
                        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);
                    } else {
                        Lang.send(player, "undo-fail");
                    }
                }
            } else {
                Lang.send(player, "look-at-frame");
            }
            return true;
        }

        // --- NAME / SAVE ---
        if (sub.equals("name") || sub.equals("save")) {
            if (!(sender instanceof Player player)) return true;
            if (!player.hasPermission("artmap.paint")) {
                Lang.send(player, "no-permission");
                return true;
            }
            if (args.length < 2) {
                Lang.send(player, "help.0");
                return true;
            }
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            handleNameAndLock(player, name);
            return true;
        }

        // --- DELETE ---
        if (sub.equals("delete") || sub.equals("remove")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            if (args.length == 2 && args[1].matches("\\d+")) {
                handleDeleteById(sender, Integer.parseInt(args[1]));
                return true;
            }
            if (sender instanceof Player player) {
                handleDeleteLookingAt(player);
            } else {
                Lang.send(sender, "delete-console");
            }
            return true;
        }

// --- INSPECT ---
        if (sub.equals("inspect")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            if (!(sender instanceof Player player)) {
                Lang.send(sender, "only-players");
                return true;
            }

            ItemFrame frame = getTargetItemFrame(player);
            if (frame == null || frame.getItem().getType() != Material.FILLED_MAP) {
                Lang.send(player, "look-at-frame");
                return true;
            }

            ItemStack item = frame.getItem();
            ItemMeta meta = item.getItemMeta();

            String author = "Neznámý";
            String lastEditor = "Nikdo";
            String date = "Neznámé";

            // Tento klíč musíš definovat lokálně, pokud není nahoře ve třídě
            NamespacedKey keyEditor = new NamespacedKey(plugin, "last_editor");

            if (meta != null) {
                if (meta.getPersistentDataContainer().has(keyEditor, PersistentDataType.STRING)) {
                    lastEditor = meta.getPersistentDataContainer().get(keyEditor, PersistentDataType.STRING);
                }

                if (meta.getPersistentDataContainer().has(keyCreated, PersistentDataType.LONG)) {
                    long time = meta.getPersistentDataContainer().get(keyCreated, PersistentDataType.LONG);
                    date = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(time));
                }

                if (meta.hasLore() && !meta.getLore().isEmpty()) {
                    String rawLore = LegacyComponentSerializer.legacyAmpersand().serialize(
                            LegacyComponentSerializer.legacyAmpersand().deserialize(meta.getLore().get(0))
                    );
                    if (rawLore.contains(": ")) {
                        author = rawLore.substring(rawLore.lastIndexOf(": ") + 2);
                    } else {
                        author = rawLore;
                    }
                }
            }

            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&8&m------------------------"));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&6&l INSPEKCE OBRAZU (ID: " + ((MapMeta)meta).getMapView().getId() + ")"));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&7 Původní autor: &f" + author));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&7 Poslední úprava: &e" + lastEditor));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&7 Datum vzniku: &b" + date));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&8&m------------------------"));
            return true;
        }

        // --- PURGE ---
        if (sub.equals("purge")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            handlePurge(sender, args);
            return true;
        }

        return true;
    }

    private void handleNameAndLock(Player player, String title) {
        ItemFrame frame = getTargetItemFrame(player);
        if (frame == null) {
            Lang.send(player, "look-at-frame");
            return;
        }

        ItemStack item = frame.getItem();
        if (item.getType() != Material.FILLED_MAP) {
            Lang.send(player, "not-a-map");
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        if (meta.getPersistentDataContainer().has(keyLocked, PersistentDataType.BYTE)) {
            Lang.send(player, "already-locked");
            return;
        }

        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&6" + title));

        // --- ČAS ZALOŽENÍ ---
        String formatPattern = plugin.getDateFormat();
        LocalDateTime dateTime;

        // Načteme původní čas založení z NBT (pokud existuje)
        // Tím zajistíme, že originál si drží starý čas (čas, kdy hráč začal malovat).
        if (meta.getPersistentDataContainer().has(keyCreated, PersistentDataType.LONG)) {
            long createdMillis = meta.getPersistentDataContainer().get(keyCreated, PersistentDataType.LONG);
            dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdMillis), ZoneId.systemDefault());
        } else {
            // Pokud mapa vznikla před tímto updatem nebo NBT chybí, použijeme aktuální čas
            dateTime = LocalDateTime.now();
        }

        String formattedDate;
        try {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern(formatPattern);
            formattedDate = dtf.format(dateTime);
        } catch (IllegalArgumentException | NullPointerException e) {
            formattedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            plugin.getLogger().warning("Chyba ve formátu data v config.yml! Používám výchozí.");
        }

        List<Component> loreComponents = new ArrayList<>();
        List<String> loreLines = Lang.getList("map-lore");

        for (String line : loreLines) {
            String formatted = line
                    .replace("{author}", player.getName())
                    .replace("{date}", formattedDate);
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
        }
        meta.lore(loreComponents);
        // -----------------------------

        meta.getPersistentDataContainer().set(keyLocked, PersistentDataType.BYTE, (byte) 1);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        item.setItemMeta(meta);
        frame.setItem(item);

        Lang.send(player, "saved", title);

        String soundName = plugin.getConfig().getString("sounds.created", "UI_CARTOGRAPHY_TABLE_TAKE_RESULT");
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(soundName), 1f, 1f);
        } catch (Exception ignored) {}

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6[ArtMap] &fHráč &e" + player.getName() + " &fprávě dokončil dílo: &a" + title
            ));
        }
    }

    private void handleDeleteLookingAt(Player player) {
        ItemFrame frame = getTargetItemFrame(player);
        if (frame == null) {
            Lang.send(player, "look-at-frame");
            return;
        }

        ItemStack item = frame.getItem();
        if (item.getType() == Material.FILLED_MAP && item.getItemMeta() instanceof MapMeta mapMeta) {
            if (mapMeta.hasMapView()) {
                int mapId = mapMeta.getMapView().getId();
                DataManager.deleteMap(mapId, plugin.getDataFolder());
                MapView view = mapMeta.getMapView();
                if (view != null) {
                    view.getRenderers().forEach(r -> {
                        if (r instanceof ArtRenderer) view.removeRenderer(r);
                    });
                }
                Lang.send(player, "deleted", String.valueOf(mapId));
            }
        }
        frame.setItem(new ItemStack(Material.AIR));
        String soundName = plugin.getConfig().getString("sounds.deleted", "BLOCK_BAMBOO_WOOD_BREAK");
        try { player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(soundName), 1f, 0.8f); } catch (Exception ignored) {}
    }

    private void handleDeleteById(CommandSender sender, int mapId) {
        DataManager.deleteMap(mapId, plugin.getDataFolder());
        @SuppressWarnings("deprecation")
        MapView view = Bukkit.getMap(mapId);
        if (view != null) {
            view.getRenderers().forEach(r -> { if (r instanceof ArtRenderer) view.removeRenderer(r); });
        }
        Lang.send(sender, "deleted", String.valueOf(mapId));
    }

    private void handlePurge(CommandSender sender, String[] args) {
        if (args.length != 2) { Lang.send(sender, "invalid-number"); return; }
        try {
            int days = Integer.parseInt(args[1]);
            Lang.send(sender, "purge-start");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int count = DataManager.purgeMaps(days, plugin.getDataFolder());
                Bukkit.getScheduler().runTask(plugin, () -> Lang.send(sender, "purge-done", String.valueOf(count)));
            });
        } catch (NumberFormatException e) { Lang.send(sender, "invalid-number"); }
    }

    private ItemFrame getTargetItemFrame(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                5.0,
                entity -> entity instanceof ItemFrame
        );
        if (result != null && result.getHitEntity() instanceof ItemFrame frame) {
            return frame;
        }
        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("name", "delete", "purge", "undo", "copy", "reload", "inspect")
                    .filter(s -> s.startsWith(args[0]))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}

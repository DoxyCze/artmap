package org.vyloterra.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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
import org.vyloterra.ArtListener;
import org.vyloterra.ArtMapPlugin;
import org.vyloterra.ArtRenderer;
import org.vyloterra.util.DataManager;
import org.vyloterra.util.Lang;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArtCommand implements CommandExecutor, TabCompleter {

    private final ArtMapPlugin plugin;
    private final ArtListener listener; // Potřebujeme pro Undo logiku
    private final NamespacedKey keyLocked;

    // Konstruktor nyní vyžaduje i Listener
    public ArtCommand(ArtMapPlugin plugin, ArtListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.keyLocked = new NamespacedKey(plugin, "artmap_locked");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Pokud hráč napíše jen /artmap, zobrazíme nápovědu
        if (args.length == 0) {
            for (String line : Lang.getList("help")) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // --- RELOAD (Admin) ---
        if (sub.equals("reload")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            plugin.reloadConfig();
            listener.reloadValues(); // Toto načte nové hodnoty do listeneru
            Lang.send(sender, "prefix", "&aKonfigurace byla znovu načtena!");
            return true;
        }

        // --- COPY (KOPÍROVÁNÍ) ---
        if (sub.equals("copy")) {
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
                ItemStack mapItem = frame.getItem().clone();
                mapItem.setAmount(1);

                // Přidání do inventáře (nebo drop na zem, pokud je plný)
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

        // --- UNDO (KROK ZPĚT) ---
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

                    // Zavoláme metodu performUndo v ArtListeneru
                    boolean success = listener.performUndo(player, mapId);

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

        // --- NAME / SAVE (PODEPSÁNÍ A ZAMČENÍ) ---
        if (sub.equals("name") || sub.equals("save")) {
            if (!(sender instanceof Player player)) {
                Lang.send(sender, "only-players");
                return true;
            }
            if (!player.hasPermission("artmap.paint")) {
                Lang.send(player, "no-permission");
                return true;
            }
            if (args.length < 2) {
                // Poskládáme zbytek argumentů jako název
                Lang.send(player, "help.0"); // Fallback nápověda
                return true;
            }

            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            handleNameAndLock(player, name);
            return true;
        }

        // --- DELETE (MAZÁNÍ) ---
        if (sub.equals("delete") || sub.equals("remove")) {
            if (!sender.hasPermission("artmap.admin")) {
                Lang.send(sender, "no-permission");
                return true;
            }
            // Mazání podle ID
            if (args.length == 2 && args[1].matches("\\d+")) {
                handleDeleteById(sender, Integer.parseInt(args[1]));
                return true;
            }
            // Mazání pohledem
            if (sender instanceof Player player) {
                handleDeleteLookingAt(player);
            } else {
                Lang.send(sender, "delete-console");
            }
            return true;
        }

        // --- PURGE (HROMADNÉ ČIŠTĚNÍ) ---
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

    // --- POMOCNÉ METODY ---

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
        if (meta.getPersistentDataContainer().has(keyLocked, PersistentDataType.BYTE)) {
            Lang.send(player, "already-locked");
            return;
        }

        // Nastavení názvu
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&6" + title));

        // Nastavení Lore (Autor, Datum) z configu
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
        List<Component> loreComponents = new ArrayList<>();
        List<String> loreLines = Lang.getList("map-lore");

        for (String line : loreLines) {
            String formatted = line
                    .replace("{author}", player.getName())
                    .replace("{date}", sdf.format(new Date()));
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
        }
        meta.lore(loreComponents);

        // Zamčení a Glow efekt
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

        // --- NOVÉ: Oznámení celému serveru (Broadcast) ---
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

                // Smazat data
                DataManager.deleteMap(mapId, plugin.getDataFolder());

                // Vyčistit renderer z paměti
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
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(soundName), 1f, 0.8f);
        } catch (Exception ignored) {}
    }

    private void handleDeleteById(CommandSender sender, int mapId) {
        DataManager.deleteMap(mapId, plugin.getDataFolder());

        @SuppressWarnings("deprecation")
        MapView view = Bukkit.getMap(mapId);
        if (view != null) {
            view.getRenderers().forEach(r -> {
                if (r instanceof ArtRenderer) view.removeRenderer(r);
            });
        }
        Lang.send(sender, "deleted", String.valueOf(mapId));
    }

    private void handlePurge(CommandSender sender, String[] args) {
        if (args.length != 2) {
            Lang.send(sender, "invalid-number");
            return;
        }
        try {
            int days = Integer.parseInt(args[1]);
            Lang.send(sender, "purge-start");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int count = DataManager.purgeMaps(days, plugin.getDataFolder());
                Bukkit.getScheduler().runTask(plugin, () -> Lang.send(sender, "purge-done", String.valueOf(count)));
            });
        } catch (NumberFormatException e) {
            Lang.send(sender, "invalid-number");
        }
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
        // Našeptávání příkazů
        if (args.length == 1) {
            return Stream.of("name", "delete", "purge", "undo", "copy", "reload")
                    .filter(s -> s.startsWith(args[0]))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
package org.vyloterra;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.vyloterra.util.ColorUtil;
import org.vyloterra.util.DataManager;
import org.vyloterra.util.Lang;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ArtListener implements Listener {

    private final ArtMapPlugin plugin;
    private final PaintingManager paintingManager;
    private final NamespacedKey keyLocked;
    private final NamespacedKey keyCanvas;

    // Anti-spam pro přepínání režimů (aby to neproblikávalo moc rychle)
    private final Map<UUID, Long> modeCooldowns = new HashMap<>();

    public ArtListener(ArtMapPlugin plugin, PaintingManager paintingManager) {
        this.plugin = plugin;
        this.paintingManager = paintingManager;
        this.keyLocked = new NamespacedKey(plugin, "artmap_locked");
        this.keyCanvas = new NamespacedKey(plugin, "is_canvas");
    }

    // --- LEVÉ KLIKNUTÍ (ÚTOK) = PŘEPÍNÁNÍ REŽIMŮ / OCHRANA ---
    @EventHandler
    public void onFrameBreak(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack item = frame.getItem();

        // 1. PŘEPÍNÁNÍ REŽIMŮ
        if (item.getType() == Material.FILLED_MAP || item.getType() == Material.MAP) {
            ItemStack hand = player.getInventory().getItemInMainHand();

            // Pokud hráč drží nástroj pro malování
            if (ColorUtil.isValidDye(hand.getType()) || hand.getType() == Material.SPONGE || hand.getType() == Material.FEATHER) {
                event.setCancelled(true); // Vždy zrušit poškození rámečku

                // Kontrola Anti-Spamu (min. 200 ms mezi kliknutími)
                long now = System.currentTimeMillis();
                if (now - modeCooldowns.getOrDefault(player.getUniqueId(), 0L) < 200) {
                    return;
                }
                modeCooldowns.put(player.getUniqueId(), now);

                paintingManager.cyclePaintMode(player);
                return;
            }
        }

        // 2. OCHRANA ZAMČENÝCH DĚL
        if (isLocked(frame)) {
            // Pokud hráč nemá admin práva, zakážeme zničení
            if (!player.hasPermission("artmap.admin")) {
                event.setCancelled(true);
                Lang.sendActionBar(player, "no-permission", "&cTento obraz je zamčený autorem!");
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.0f);
            } else {
                // Adminovi pošleme upozornění
                player.sendMessage("§c[ArtMap Admin] §7Ničíš zamčený obraz.");
            }
        }
    }

    // --- PRAVÉ KLIKNUTÍ (INTERAKCE) = MALOVÁNÍ ---
    @EventHandler
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack itemInFrame = frame.getItem();

        // Pokud hráč už maluje, zrušíme interakci
        if (paintingManager.getActiveFrames().containsValue(frame)) {
            event.setCancelled(true);
            return;
        }

        if (itemInFrame.getType() == Material.FILLED_MAP || itemInFrame.getType() == Material.MAP) {

            // 1. KONTROLA ZAMČENÍ
            if (isLocked(frame)) {
                // Admin může otáčet/upravovat (s varováním), ostatní nic
                if (!player.hasPermission("artmap.admin")) {
                    event.setCancelled(true);
                    Lang.sendActionBar(player, "already-locked", "&cObraz je podepsaný a nelze jej upravovat.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.0f);
                    return;
                } else if (player.getGameMode() == GameMode.CREATIVE) {
                    // Admin v creativu může otáčet, ale ne malovat pravým klikem (aby si to nezničil)
                    // Pokud chce admin malovat na zamčený obraz, musí si ho odemknout příkazem (bezpečnější)
                    return;
                }
            }

            // 2. KONTROLA PLÁTNA
            if (!isCanvas(itemInFrame)) {
                // Není to plátno -> chová se jako obyčejná mapa
                // Volitelné: Zvuk, pokud se snaží malovat barvivem na obyčejnou mapu
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (ColorUtil.isValidDye(hand.getType())) {
                    Lang.sendActionBar(player, "not-canvas", "&7Toto není malířské plátno.");
                }
                return;
            }

            // Vše OK -> Start malování
            event.setCancelled(true);
            paintingManager.updatePaintingState(player, frame);
        }
    }

    // --- OSTATNÍ EVENTY ---

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();

        if (!ColorUtil.isValidDye(player.getInventory().getItemInMainHand().getType()) &&
                !ColorUtil.isValidDye(player.getInventory().getItemInOffHand().getType())) {
            return;
        }
        paintingManager.handleBrushChange(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        paintingManager.endSession(event.getPlayer());
        modeCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        MapView view = event.getMap();
        if (DataManager.hasSavedData(view.getId(), plugin.getDataFolder())) {
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new ArtRenderer(view.getId(), plugin.getDataFolder()));
        }
    }

    // --- POMOCNÉ METODY ---

    private boolean isLocked(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (item == null || item.getType() == Material.AIR) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyLocked, PersistentDataType.BYTE);
    }

    private boolean isCanvas(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyCanvas, PersistentDataType.BYTE);
    }
}

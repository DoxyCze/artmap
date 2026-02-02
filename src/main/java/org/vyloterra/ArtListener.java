package org.vyloterra;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapView;
import org.vyloterra.util.ColorUtil;
import org.vyloterra.util.DataManager;

public class ArtListener implements Listener {

    private final ArtMapPlugin plugin;
    // --- TENTO ŘÁDEK TI CHYBĚL ---
    private final PaintingManager paintingManager;

    // Konstruktor
    public ArtListener(ArtMapPlugin plugin, PaintingManager paintingManager) {
        this.plugin = plugin;
        this.paintingManager = paintingManager; // Teď už to půjde, protože proměnná existuje
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        // Reagujeme jen na začátek plížení (zmáčknutí Shiftu)
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();

        // Rychlá optimalizace: Pokud hráč nedrží v ruce žádné barvivo,
        // nemá smysl volat manažera a řešit změnu štětce.
        if (!ColorUtil.isValidDye(player.getInventory().getItemInMainHand().getType()) &&
                !ColorUtil.isValidDye(player.getInventory().getItemInOffHand().getType())) {
            return;
        }

        // Předáme požadavek manažerovi, který přepne velikost štětce
        paintingManager.handleBrushChange(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Hráč se odpojil -> řekneme manažerovi, ať po něm uklidí
        // (smaže session, uloží cooldowny, vyčistí paměť)
        paintingManager.endSession(event.getPlayer());
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        // Tato událost nastává, když server načte mapu (např. při startu nebo načtení chunku).
        // Zkontrolujeme, zda pro tuto mapu existují uložená data (obrázek).
        MapView view = event.getMap();
        if (DataManager.hasSavedData(view.getId(), plugin.getDataFolder())) {
            // Pokud ano, odstraníme výchozí Minecraft renderer a nasadíme náš ArtRenderer,
            // který načte obrázek ze souboru.
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new ArtRenderer(view.getId(), plugin.getDataFolder()));
        }
    }
}
package org.vyloterra;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.vyloterra.util.DataManager;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ArtRenderer extends MapRenderer {

    // Hlavní paměť pixelů (128x128)
    private final byte[][] pixels = new byte[128][128];
    private final Object lock = new Object();

    // Optimalizace: Sledujeme jen změněné pixely pomocí "packed integer" (x << 7 | y)
    // To šetří výkon serveru, protože neposíláme celou mapu každých 50ms.
    private final Set<Integer> dirtyPixels = new HashSet<>();

    private volatile boolean needsUpdate = true;
    private boolean fullRenderNeeded = true; // Pro první vykreslení nebo po Undo/Clear

    public ArtRenderer(int mapId, File dataFolder) {
        // Načteme data z disku při startu
        byte[][] loaded = DataManager.loadMap(mapId, dataFolder);
        if (loaded != null) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(loaded[x], 0, this.pixels[x], 0, 128);
            }
        } else {
            // Pokud mapa neexistuje, vyplníme ji prázdnou barvou
            for (byte[] row : pixels) Arrays.fill(row, (byte) 0);
        }
    }

    /**
     * Metoda pro kreslení jednoho bodu (volá ji ArtMath).
     */
    public void draw(int x, int y, byte color) {
        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
            synchronized (lock) {
                if (pixels[x][y] != color) { // Měníme jen pokud je barva jiná
                    pixels[x][y] = color;
                    // Uložíme souřadnice změněného bodu do jednoho čísla
                    dirtyPixels.add((x << 7) | y);
                    needsUpdate = true;
                }
            }
        }
    }

    /**
     * Rychlé smazání celé mapy (Mokrá houba).
     */
    public void clear(byte color) {
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                Arrays.fill(pixels[x], color);
            }
            // Vynutíme kompletní překreslení
            fullRenderNeeded = true;
            needsUpdate = true;
            dirtyPixels.clear(); // Dirty pixely už nejsou potřeba, překreslíme vše
        }
    }

    /**
     * Načtení celého pole pixelů (Pro funkci UNDO).
     */
    public void loadPixels(byte[][] newPixels) {
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(newPixels[x], 0, pixels[x], 0, 128);
            }
            // Vynutíme kompletní překreslení
            fullRenderNeeded = true;
            needsUpdate = true;
            dirtyPixels.clear();
        }
    }

    /**
     * Vytvoří kopii aktuálních dat (Pro ukládání a Undo buffer).
     */
    public byte[][] getPixelsSnapshot() {
        byte[][] copy = new byte[128][128];
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(pixels[x], 0, copy[x], 0, 128);
            }
        }
        return copy;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (!needsUpdate) return;

        synchronized (lock) {
            // 1. Full Render (Náročnější, ale nutný při velkých změnách)
            if (fullRenderNeeded) {
                for (int x = 0; x < 128; x++) {
                    for (int y = 0; y < 128; y++) {
                        canvas.setPixel(x, y, pixels[x][y]);
                    }
                }
                fullRenderNeeded = false;
            }
            // 2. Dirty Update (Velmi rychlý, jen změněné body)
            else {
                for (int packed : dirtyPixels) {
                    int x = packed >> 7;      // Získáme X zpět
                    int y = packed & 0x7F;    // Získáme Y zpět
                    canvas.setPixel(x, y, pixels[x][y]);
                }
            }

            dirtyPixels.clear();
            needsUpdate = false;
        }
    }
}
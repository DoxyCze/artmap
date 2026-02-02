package org.vyloterra;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.vyloterra.util.DataManager;

import java.io.File;
import java.util.Arrays;

public class ArtRenderer extends MapRenderer {

    // Hlavní paměť pixelů (128x128)
    private final byte[][] pixels = new byte[128][128];
    private final Object lock = new Object();

    // OPTIMALIZACE: Místo HashSet používáme boolean pole.
    // Index je (x * 128 + y). Je to mnohem rychlejší a nevytváří to odpad pro Garbage Collector.
    private final boolean[] dirtyPixels = new boolean[128 * 128];

    // Pomocné proměnné pro ohraničení změny (volitelné, pro ještě vyšší rychlost vykreslování)
    private int minDirtyX = 128, maxDirtyX = -1, minDirtyY = 128, maxDirtyY = -1;

    private volatile boolean needsUpdate = true;
    private boolean fullRenderNeeded = true;

    public ArtRenderer(int mapId, File dataFolder) {
        // Načteme data z disku při startu
        byte[][] loaded = DataManager.loadMap(mapId, dataFolder);
        if (loaded != null) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(loaded[x], 0, this.pixels[x], 0, 128);
            }
        } else {
            for (byte[] row : pixels) Arrays.fill(row, (byte) 0);
        }
    }

    /**
     * Metoda pro kreslení jednoho bodu.
     */
    public void draw(int x, int y, byte color) {
        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
            synchronized (lock) {
                if (pixels[x][y] != color) {
                    pixels[x][y] = color;
                    // Zde jen přepneme boolean na true - žádné vytváření objektů!
                    dirtyPixels[(x << 7) | y] = true;
                    needsUpdate = true;
                }
            }
        }
    }

    /**
     * Rychlé smazání celé mapy.
     */
    public void clear(byte color) {
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                Arrays.fill(pixels[x], color);
            }
            fullRenderNeeded = true;
            needsUpdate = true;
            // Reset dirty pole
            Arrays.fill(dirtyPixels, false);
        }
    }

    public void loadPixels(byte[][] newPixels) {
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(newPixels[x], 0, pixels[x], 0, 128);
            }
            fullRenderNeeded = true;
            needsUpdate = true;
            Arrays.fill(dirtyPixels, false);
        }
    }

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
            if (fullRenderNeeded) {
                for (int x = 0; x < 128; x++) {
                    for (int y = 0; y < 128; y++) {
                        canvas.setPixel(x, y, pixels[x][y]);
                    }
                }
                fullRenderNeeded = false;
            } else {
                // Projdeme pole booleanů - je to extrémně rychlé
                for (int i = 0; i < dirtyPixels.length; i++) {
                    if (dirtyPixels[i]) {
                        int x = i >> 7;   // Bitový posun (děleno 128)
                        int y = i & 0x7F; // Bitový AND (zbytek po dělení 128)
                        canvas.setPixel(x, y, pixels[x][y]);
                        dirtyPixels[i] = false; // Resetujeme příznak
                    }
                }
            }
            needsUpdate = false;
        }
    }
}
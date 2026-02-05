package org.vyloterra;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.vyloterra.util.DataManager;

import java.io.File;
import java.util.Arrays;

public class ArtRenderer extends MapRenderer {

    private final byte[][] pixels = new byte[128][128];
    private final Object lock = new Object();
    private final boolean[] dirtyPixels = new boolean[128 * 128];

    private volatile boolean needsUpdate = true;
    private boolean fullRenderNeeded = true;

    public ArtRenderer(int mapId, File dataFolder) {
        byte[][] loaded = DataManager.loadMap(mapId, dataFolder);
        if (loaded != null) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(loaded[x], 0, pixels[x], 0, 128);
            }
        }
    }

    // --- METODY PRO KYBLÍK A ŠTĚTEC ---
    public byte getPixel(int x, int y) {
        if (x < 0 || x >= 128 || y < 0 || y >= 128) return 0;
        synchronized (lock) {
            return pixels[x][y];
        }
    }

    public void drawPixel(int x, int y, byte color) {
        if (x < 0 || x >= 128 || y < 0 || y >= 128) return;
        synchronized (lock) {
            if (pixels[x][y] != color) {
                pixels[x][y] = color;
                dirtyPixels[x * 128 + y] = true;
                needsUpdate = true;
            }
        }
    }

    public void draw(int x, int y, byte color) {
        drawPixel(x, y, color);
    }

    // --- METODY PRO UNDO A MAZÁNÍ (OPRAVENO) ---

    /**
     * Načte celé pole pixelů (používá se pro UNDO).
     */
    public void loadPixels(byte[][] data) {
        if (data.length != 128) return;
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                System.arraycopy(data[x], 0, pixels[x], 0, 128);
            }
            fullRenderNeeded = true;
            needsUpdate = true;
        }
    }

    /**
     * Vymaže plátno konkrétní barvou.
     */
    public void clear(byte color) {
        synchronized (lock) {
            for (int x = 0; x < 128; x++) {
                Arrays.fill(pixels[x], color);
            }
            fullRenderNeeded = true;
            needsUpdate = true;
            Arrays.fill(dirtyPixels, false);
        }
    }

    /**
     * Vymaže plátno (průhledná/černá).
     */
    public void clear() {
        clear((byte) 0);
    }

    // V ArtRenderer.java
    public byte[][] getPixelsSnapshot() {
        // Vytvoří hlubokou kopii pole (Deep Copy)
        byte[][] copy = new byte[128][128];
        for (int i = 0; i < 128; i++) {
            System.arraycopy(this.pixels[i], 0, copy[i], 0, 128);
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
                for (int i = 0; i < dirtyPixels.length; i++) {
                    if (dirtyPixels[i]) {
                        int x = i >> 7;
                        int y = i & 127;
                        canvas.setPixel(x, y, pixels[x][y]);
                        dirtyPixels[i] = false;
                    }
                }
            }
            needsUpdate = false;
        }
    }
}

package org.vyloterra.util;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.vyloterra.ArtRenderer;

import java.util.LinkedList;
import java.util.Queue;

public class ArtMath {

    public static final int MAP_SIZE = 128;

    public static int[] getCanvasCoordinates(Vector hitPos, Vector framePos, BlockFace face) {
        Vector relative = hitPos.clone().subtract(framePos);

        // Y je v MC zdola nahoru, na mapě shora dolů -> 0.5 - Y
        double canvasY = 0.5 - relative.getY();
        double canvasX;

        // Zde NEOPTIMALIZOVAT sloučením case.
        // Protilehlé strany musí mít opačné znaménko, aby myš jezdila správně.
        switch (face) {
            case NORTH: canvasX = 0.5 - relative.getX(); break;
            case SOUTH: canvasX = 0.5 + relative.getX(); break;
            case WEST:  canvasX = 0.5 + relative.getZ(); break;
            case EAST:  canvasX = 0.5 - relative.getZ(); break;
            default: return null;
        }

        // Rychlejší přetypování místo Math.floor (bod 4)
        int x = (int) (canvasX * MAP_SIZE);
        int y = (int) (canvasY * MAP_SIZE);

        return new int[]{
                Math.max(0, Math.min(MAP_SIZE - 1, x)),
                Math.max(0, Math.min(MAP_SIZE - 1, y))
        };
    }

    // Do ArtMath.java
    public static void floodFill(ArtRenderer renderer, int x, int y, byte targetColor, byte replacementColor) {
        if (targetColor == replacementColor) return; // Stejná barva, nic nedělat
        if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE) return;

        // Použijeme frontu pro souřadnice (x, y zabalíme do int array)
        Queue<int[]> queue = new LinkedList<>();
        queue.add(new int[]{x, y});

        // Získáme aktuální pixely pro čtení (potřebujeme "read-only" přístup k aktuálnímu stavu)
        // Poznámka: Zde by bylo ideální mít v Renderer metod getPixel(x, y),
        // nebo si vyžádat snapshot, ale pro jednoduchost předpokládejme, že renderer umí číst.
        // ... Zde by následoval cyklus while(!queue.isEmpty()) ...
    }

    // Scanline algoritmus (bod 1)
    public static void drawPencil(ArtRenderer renderer, int centerX, int centerY, int radius, byte color) {
        if (radius <= 0) {
            renderer.draw(centerX, centerY, color);
            return;
        }

        int rSq = radius * radius;

        // Scanline: Místo testování každého bodu v čtverci, vypočítáme šířku řádku
        for (int y = -radius; y <= radius; y++) {
            // Pythagorova věta: x = odmocnina(r^2 - y^2)
            int width = (int) Math.sqrt(rSq - y * y);

            for (int x = -width; x <= width; x++) {
                renderer.draw(centerX + x, centerY + y, color);
            }
        }
    }

    public static void drawLine(ArtRenderer renderer, int x1, int y1, int x2, int y2, int radius, byte color) {
        // Early exit (bod 2)
        if (radius == 0) {
            drawLineThin(renderer, x1, y1, x2, y2, color);
            return;
        }

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            drawPencil(renderer, x1, y1, radius, color);

            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    // Pomocná metoda pro 1px čáru (velmi rychlá)
    private static void drawLineThin(ArtRenderer renderer, int x1, int y1, int x2, int y2, byte color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            renderer.draw(x1, y1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }
}
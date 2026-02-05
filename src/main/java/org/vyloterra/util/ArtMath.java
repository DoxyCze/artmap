package org.vyloterra.util;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.vyloterra.ArtRenderer;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

public class ArtMath {

    public static final int MAP_SIZE = 128;
    private static final Random random = new Random();

    /**
     * Převede souřadnice z kliknutí na ItemFrame na pixelové souřadnice (0-127).
     */
    public static int[] getCanvasCoordinates(Vector hitPos, Vector framePos, BlockFace face) {
        Vector relative = hitPos.clone().subtract(framePos);

        // Y je v MC zdola nahoru, na mapě shora dolů -> 0.5 - Y
        double canvasY = 0.5 - relative.getY();
        double canvasX;

        // Protilehlé strany musí mít opačné znaménko
        switch (face) {
            case NORTH: canvasX = 0.5 - relative.getX(); break;
            case SOUTH: canvasX = 0.5 + relative.getX(); break;
            case WEST:  canvasX = 0.5 + relative.getZ(); break;
            case EAST:  canvasX = 0.5 - relative.getZ(); break;
            default: return null;
        }

        int x = (int) (canvasX * MAP_SIZE);
        int y = (int) (canvasY * MAP_SIZE);

        return new int[]{
                Math.max(0, Math.min(MAP_SIZE - 1, x)),
                Math.max(0, Math.min(MAP_SIZE - 1, y))
        };
    }

    // --- KRESLÍCÍ METODY ---

    /**
     * Vykreslí plný kruh/čtverec (štětec).
     */
    public static void drawPencil(ArtRenderer renderer, int cx, int cy, int radius, byte color) {
        if (radius <= 0) {
            renderer.draw(cx, cy, color);
            return;
        }

        int rSq = radius * radius;

        // Scanline optimalizace
        for (int y = -radius; y <= radius; y++) {
            int width = (int) Math.sqrt(rSq - y * y);
            for (int x = -width; x <= width; x++) {
                renderer.draw(cx + x, cy + y, color);
            }
        }
    }

    /**
     * NOVÉ: Vykreslí "míchaný" štětec (Dithering).
     * Střídá dvě barvy v šachovnicovém vzoru.
     */
    public static void drawMix(ArtRenderer renderer, int cx, int cy, int radius, byte color1, byte color2) {
        if (radius <= 0) {
            renderer.draw(cx, cy, color1);
            return;
        }

        int rSq = radius * radius;

        for (int y = -radius; y <= radius; y++) {
            int width = (int) Math.sqrt(rSq - y * y);
            for (int x = -width; x <= width; x++) {
                // Šachovnice: (x + y) % 2
                // Absolutní souřadnice zajistí, že vzor navazuje i při pohybu myši
                int absX = cx + x;
                int absY = cy + y;

                if ((absX + absY) % 2 == 0) {
                    renderer.draw(absX, absY, color1);
                } else {
                    renderer.draw(absX, absY, color2);
                }
            }
        }
    }

    /**
     * Efekt spreje (náhodné tečky v okruhu).
     * @param density Hustota teček (čím vyšší, tím více barvy)
     */
    public static void drawSpray(ArtRenderer renderer, int cx, int cy, int radius, byte color, int density) {
        if (radius <= 0) {
            renderer.draw(cx, cy, color);
            return;
        }

        int rSq = radius * radius;

        // Počet teček v jednom ticku
        int dots = density * 2;

        for (int i = 0; i < dots; i++) {
            // Náhodný bod v kruhu
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dy = random.nextInt(radius * 2 + 1) - radius;

            if (dx * dx + dy * dy <= rSq) {
                // Občas vynecháme pixel pro "vzdušný" efekt (50% šance)
                if (random.nextBoolean()) {
                    renderer.draw(cx + dx, cy + dy, color);
                }
            }
        }
    }

    /**
     * Vykreslí čáru (Bresenham).
     */
    public static void drawLine(ArtRenderer renderer, int x1, int y1, int x2, int y2, int radius, byte color) {
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

    /**
     * Vykreslí obdélník (obrys).
     */
    public static void drawRectangle(ArtRenderer renderer, int x1, int y1, int x2, int y2, int radius, byte color) {
        drawLine(renderer, x1, y1, x2, y1, radius, color); // Horní
        drawLine(renderer, x1, y2, x2, y2, radius, color); // Spodní
        drawLine(renderer, x1, y1, x1, y2, radius, color); // Levá
        drawLine(renderer, x2, y1, x2, y2, radius, color); // Pravá
    }

    /**
     * Vykreslí kružnici (obrys).
     */
    public static void drawCircle(ArtRenderer renderer, int x1, int y1, int x2, int y2, int brushRadius, byte color) {
        int r = Math.abs(x2 - x1) / 2;
        int cx = (x1 + x2) / 2;
        int cy = (y1 + y2) / 2;
        int x = 0;
        int y = r;
        int d = 3 - 2 * r;

        while (y >= x) {
            drawPencil(renderer, cx + x, cy + y, brushRadius, color);
            drawPencil(renderer, cx - x, cy + y, brushRadius, color);
            drawPencil(renderer, cx + x, cy - y, brushRadius, color);
            drawPencil(renderer, cx - x, cy - y, brushRadius, color);
            drawPencil(renderer, cx + y, cy + x, brushRadius, color);
            drawPencil(renderer, cx - y, cy + x, brushRadius, color);
            drawPencil(renderer, cx + y, cy - x, brushRadius, color);
            drawPencil(renderer, cx - y, cy - x, brushRadius, color);
            x++;
            if (d > 0) {
                y--;
                d = d + 4 * (x - y) + 10;
            } else {
                d = d + 4 * x + 6;
            }
        }
    }

    /**
     * OPTIMALIZOVANÝ Flood Fill (Kyblík).
     * Pracuje s kopií pole (snapshot), což je extrémně rychlé oproti volání rendereru.
     */
    public static void runFloodFill(ArtRenderer renderer, int startX, int startY, byte replacementColor) {
        // 1. Získáme snapshot (jedno zamčení vlákna)
        byte[][] pixels = renderer.getPixelsSnapshot();
        byte targetColor = pixels[startX][startY];

        if (targetColor == replacementColor) return;

        Queue<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startX, startY});

        boolean[][] visited = new boolean[MAP_SIZE][MAP_SIZE];
        visited[startX][startY] = true;

        boolean changed = false;
        int pixelsChanged = 0;
        int maxPixels = MAP_SIZE * MAP_SIZE; // Ochrana proti zacyklení

        // 2. Rychlý výpočet v paměti (bez zamykání rendereru)
        while (!queue.isEmpty() && pixelsChanged < maxPixels) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];

            // Zápis do lokálního pole
            pixels[x][y] = replacementColor;
            changed = true;
            pixelsChanged++;

            // Kontrola sousedů přímo v lokálním poli
            checkNeighborFast(pixels, x + 1, y, targetColor, visited, queue);
            checkNeighborFast(pixels, x - 1, y, targetColor, visited, queue);
            checkNeighborFast(pixels, x, y + 1, targetColor, visited, queue);
            checkNeighborFast(pixels, x, y - 1, targetColor, visited, queue);
        }

        // 3. Uložení zpět (jedno zamčení vlákna)
        if (changed) {
            renderer.loadPixels(pixels);
        }
    }

    // Rychlá kontrola souseda (pracuje s byte[][], ne s rendererem)
    private static void checkNeighborFast(byte[][] pixels, int x, int y, byte target, boolean[][] visited, Queue<int[]> queue) {
        if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE) return;
        if (visited[x][y]) return;

        if (pixels[x][y] == target) {
            visited[x][y] = true;
            queue.add(new int[]{x, y});
        }
    }
}

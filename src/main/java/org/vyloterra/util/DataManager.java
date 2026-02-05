package org.vyloterra.util;

import org.vyloterra.ArtMapPlugin;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DataManager {

    private static final String FILE_EXTENSION = ".dat";
    private static final int MAP_SIZE = 128;

    /**
     * Asynchronní uložení mapy (používá se během běhu serveru).
     * @param pixelsSnapshot Musí být KOPIE dat, nikoliv živé pole!
     * @param callback Volitelná akce po dokončení (true = úspěch, false = chyba). Může být null.
     */
    public static void saveMapAsync(int mapId, byte[][] pixelsSnapshot, File dataFolder, Consumer<Boolean> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                // Pokus o uložení
                saveMap(mapId, pixelsSnapshot, dataFolder);
                // Pokud nenastala chyba a máme callback, nahlásíme úspěch
                if (callback != null) callback.accept(true);
            } catch (Exception e) {
                // Pokud nastala chyba, vyhodíme ji, aby ji chytil .exceptionally()
                throw new RuntimeException(e);
            }
        }).exceptionally(ex -> {
            // Logování chyby do konzole
            ArtMapPlugin.getInstance().getLogger().log(Level.SEVERE,
                    "Chyba při asynchronním ukládání mapy " + mapId, ex);

            // Nahlášení neúspěchu callbacku
            if (callback != null) callback.accept(false);
            return null;
        });
    }

    /**
     * Synchronní uložení (používá se při vypínání serveru).
     */
    public static void saveMapSync(int mapId, byte[][] pixelsSnapshot, File dataFolder) {
        try {
            saveMap(mapId, pixelsSnapshot, dataFolder);
        } catch (IOException e) {
            ArtMapPlugin.getInstance().getLogger().log(Level.SEVERE, "Chyba při synchronním ukládání mapy " + mapId, e);
        }
    }

    /**
     * Interní metoda pro zápis dat do souboru (GZIP komprese).
     */
    private static void saveMap(int mapId, byte[][] pixels, File dataFolder) throws IOException {
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File file = new File(dataFolder, "map_" + mapId + FILE_EXTENSION);

        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(file)))) {
            for (int x = 0; x < MAP_SIZE; x++) {
                out.write(pixels[x]);
            }
        }
    }

    public static byte[][] loadMap(int mapId, File dataFolder) {
        File file = new File(dataFolder, "map_" + mapId + FILE_EXTENSION);
        if (!file.exists()) return null;

        byte[][] pixels = new byte[MAP_SIZE][MAP_SIZE];

        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            for (int x = 0; x < MAP_SIZE; x++) {
                in.readFully(pixels[x]);
            }
            return pixels;
        } catch (IOException e) {
            ArtMapPlugin.getInstance().getLogger().log(Level.SEVERE, "Chyba při načítání mapy " + mapId, e);
            return null;
        }
    }

    public static void deleteMap(int mapId, File dataFolder) {
        File file = new File(dataFolder, "map_" + mapId + FILE_EXTENSION);
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean hasSavedData(int mapId, File dataFolder) {
        return new File(dataFolder, "map_" + mapId + FILE_EXTENSION).exists();
    }

    public static int purgeMaps(int days, File dataFolder) {
        if (!dataFolder.exists()) return 0;

        long threshold = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(FILE_EXTENSION));

        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (file.lastModified() < threshold) {
                if (file.delete()) {
                    count++;
                }
            }
        }
        return count;
    }
}

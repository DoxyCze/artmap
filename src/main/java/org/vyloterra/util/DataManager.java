package org.vyloterra.util;

import org.vyloterra.ArtMapPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DataManager {

    private static final String FILE_EXTENSION = ".dat";
    private static final int MAP_SIZE = 128;

    /**
     * Asynchronní uložení mapy (používá se během běhu serveru).
     * @param pixelsSnapshot Musí být KOPIE dat, nikoliv živé pole!
     */
    public static void saveMapAsync(int mapId, byte[][] pixelsSnapshot, File dataFolder) {
        CompletableFuture.runAsync(() -> saveMap(mapId, pixelsSnapshot, dataFolder))
                .exceptionally(ex -> {
                    ArtMapPlugin.getInstance().getLogger().log(Level.SEVERE,
                            "Chyba při asynchronním ukládání mapy " + mapId, ex);
                    return null;
                });
    }

    /**
     * Synchronní uložení (používá se při vypínání serveru).
     */
    public static void saveMapSync(int mapId, byte[][] pixelsSnapshot, File dataFolder) {
        saveMap(mapId, pixelsSnapshot, dataFolder);
    }

    // Interní metoda pro bezpečné uložení
    private static void saveMap(int mapId, byte[][] pixels, File dataFolder) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) return;

        File finalFile = new File(dataFolder, "map_" + mapId + FILE_EXTENSION);
        File tempFile = new File(dataFolder, "map_" + mapId + ".tmp");

        try {
            // 1. Zápis do dočasného souboru (GZIP komprese)
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tempFile)))) {
                for (int x = 0; x < MAP_SIZE; x++) {
                    out.write(pixels[x]);
                }
            }

            // 2. Atomický přesun (přepíše starý soubor jen pokud je nový OK)
            // Toto zabrání poškození dat při pádu serveru během ukládání.
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            ArtMapPlugin.getInstance().getLogger().log(Level.SEVERE, "Chyba IO při ukládání mapy " + mapId, e);
        }
    }

    public static byte[][] loadMap(int mapId, File dataFolder) {
        File file = new File(dataFolder, "map_" + mapId + FILE_EXTENSION);
        if (!file.exists()) return null;

        // Aktualizace data úpravy (aby purge system věděl, že je mapa aktivní)
        // Ignorujeme výsledek, není kritické
        file.setLastModified(System.currentTimeMillis());

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
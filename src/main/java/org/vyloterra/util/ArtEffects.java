package org.vyloterra.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

public class ArtEffects {

    private static final Random random = new Random();

    // NOVÉ MAPOVÁNÍ: Barvivo -> Přesná RGB Barva
    private static final Map<Material, Color> DYE_TO_COLOR = new EnumMap<>(Material.class);

    static {
        // Standardní barvy
        DYE_TO_COLOR.put(Material.WHITE_DYE, Color.WHITE);
        DYE_TO_COLOR.put(Material.ORANGE_DYE, Color.ORANGE);
        DYE_TO_COLOR.put(Material.MAGENTA_DYE, Color.FUCHSIA);
        DYE_TO_COLOR.put(Material.LIGHT_BLUE_DYE, Color.AQUA);
        DYE_TO_COLOR.put(Material.YELLOW_DYE, Color.YELLOW);
        DYE_TO_COLOR.put(Material.LIME_DYE, Color.LIME);
        DYE_TO_COLOR.put(Material.PINK_DYE, Color.fromRGB(255, 192, 203)); // Pink
        DYE_TO_COLOR.put(Material.GRAY_DYE, Color.GRAY);
        DYE_TO_COLOR.put(Material.LIGHT_GRAY_DYE, Color.SILVER);
        DYE_TO_COLOR.put(Material.CYAN_DYE, Color.TEAL);
        DYE_TO_COLOR.put(Material.PURPLE_DYE, Color.PURPLE);
        DYE_TO_COLOR.put(Material.BLUE_DYE, Color.BLUE);
        DYE_TO_COLOR.put(Material.BROWN_DYE, Color.fromRGB(139, 69, 19)); // Brown
        DYE_TO_COLOR.put(Material.GREEN_DYE, Color.GREEN);
        DYE_TO_COLOR.put(Material.RED_DYE, Color.RED);
        DYE_TO_COLOR.put(Material.BLACK_DYE, Color.BLACK);

        // Alternativní materiály
        DYE_TO_COLOR.put(Material.INK_SAC, Color.BLACK);
        DYE_TO_COLOR.put(Material.BONE_MEAL, Color.WHITE);
        DYE_TO_COLOR.put(Material.COCOA_BEANS, Color.fromRGB(139, 69, 19));
        DYE_TO_COLOR.put(Material.LAPIS_LAZULI, Color.BLUE);
        DYE_TO_COLOR.put(Material.GLOW_INK_SAC, Color.AQUA); // Svítící jako azurová
    }

    /**
     * Hlavní efekt při kreslení - Jemný barevný prach.
     */
    public static void playPaintEffect(Location location, Material dye, int brushSize) {
        World world = location.getWorld();
        if (world == null) return;

        // Získáme barvu
        Color color = DYE_TO_COLOR.getOrDefault(dye, Color.WHITE);

        // --- NASTAVENÍ VELIKOSTI ČÁSTIC ---
        // Běžný Redstone dust má velikost 1.0.
        // My chceme cca 3x menší, takže zkusíme 0.35.
        float particleSize = 0.35f;

        // Vytvoříme možnosti pro prach (barva + velikost)
        Particle.DustOptions dustOptions = new Particle.DustOptions(color, particleSize);

        // --- 1. KONFIGURACE PODLE VELIKOSTI ŠTĚTCE ---
        int particleCount;
        double spread;
        float soundVolume;
        float soundPitchBase;

        switch (brushSize) {
            case 2: // Velký štětec
                particleCount = 5; // Více jemného prachu
                spread = 0.12;
                soundVolume = 0.15f;
                soundPitchBase = 0.8f;
                break;
            case 1: // Střední štětec
                particleCount = 3;
                spread = 0.06;
                soundVolume = 0.10f;
                soundPitchBase = 1.2f;
                break;
            default: // Malá tužka (0)
                particleCount = 2;
                spread = 0.02;
                soundVolume = 0.05f;
                soundPitchBase = 1.8f;
                break;
        }

        // --- 2. PARTICLES A: JEMNÝ PRACH (DUST) ---
        // Používáme Particle.DUST místo Particle.BLOCK
        world.spawnParticle(
                Particle.DUST,
                location,
                particleCount,
                spread, spread, spread,
                0, // Rychlost u DUST funguje jinak, necháme 0 pro "vznášení" na místě
                dustOptions // Předáme naše nastavení barvy a velikosti
        );

        // --- 3. PARTICLES B: EXTRA EFEKTY (Volitelné, pro "šťávu") ---

        // Pro velký štětec přidáme občas "odkapávající" efekt
        if (brushSize == 2 && random.nextInt(4) == 0) {
            // FALLING_DUST potřebuje BlockData, pro jednoduchost použijeme červený písek,
            // nebo bychom museli vrátit zpět mapování na bloky.
            // Pro teď to necháme jednodušší bez padajícího prachu,
            // samotný jemný DUST by měl stačit.
        }

        // Pro svítící inkoust přidáme malé jiskření
        if (dye == Material.GLOW_INK_SAC && random.nextInt(3) == 0) {
            world.spawnParticle(Particle.WAX_OFF, location, 1, 0.1, 0.1, 0.1, 0.1);
        }


        // --- 4. ZVUK ---
        if (random.nextInt(4) == 0) {
            float randomPitch = soundPitchBase + (random.nextFloat() * 0.4f - 0.2f);
            // Změníme zvuk na něco jemnějšího, "šustivého"
            Sound paintSound = Sound.BLOCK_SAND_BREAK;
            if (brushSize == 2) paintSound = Sound.BLOCK_SLIME_BLOCK_STEP; // Velký štětec zní mokře

            world.playSound(location, paintSound, soundVolume, randomPitch);
        }
    }

    public static void playBrushChangeEffect(Player player, int newSize) {
        float pitch = 0.6f + (newSize * 0.4f);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.4f, pitch);

        player.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER,
                player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5)),
                3, 0.1, 0.1, 0.1, 0
        );
    }

    public static void playErrorEffect(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
        player.getWorld().spawnParticle(Particle.SMOKE, player.getEyeLocation().add(0, -0.2, 0), 5, 0.1, 0.1, 0.1, 0.01);
    }
}
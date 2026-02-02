package org.vyloterra.util;

import org.bukkit.Material;
import java.util.EnumMap;
import java.util.Map;

public class ColorUtil {

    private static final Map<Material, Byte> COLOR_PALETTE = new EnumMap<>(Material.class);

    // Fallback barva (Pale Green)
    private static final byte DEFAULT_COLOR = 4;

    static {
        // Standardní barviva (hodnoty jsou BaseColor * 4 + 2 pro sytý odstín)
        COLOR_PALETTE.put(Material.WHITE_DYE, (byte) 34);
        COLOR_PALETTE.put(Material.ORANGE_DYE, (byte) 62);
        COLOR_PALETTE.put(Material.MAGENTA_DYE, (byte) 66);
        COLOR_PALETTE.put(Material.LIGHT_BLUE_DYE, (byte) 70);
        COLOR_PALETTE.put(Material.YELLOW_DYE, (byte) 74);
        COLOR_PALETTE.put(Material.LIME_DYE, (byte) 78);
        COLOR_PALETTE.put(Material.PINK_DYE, (byte) 82);
        COLOR_PALETTE.put(Material.GRAY_DYE, (byte) 86);
        COLOR_PALETTE.put(Material.LIGHT_GRAY_DYE, (byte) 90);
        COLOR_PALETTE.put(Material.CYAN_DYE, (byte) 94);
        COLOR_PALETTE.put(Material.PURPLE_DYE, (byte) 98);
        COLOR_PALETTE.put(Material.BLUE_DYE, (byte) 102);
        COLOR_PALETTE.put(Material.BROWN_DYE, (byte) 106);
        COLOR_PALETTE.put(Material.GREEN_DYE, (byte) 110);
        COLOR_PALETTE.put(Material.RED_DYE, (byte) 114);
        COLOR_PALETTE.put(Material.BLACK_DYE, (byte) 118);

        // Alternativní předměty fungující jako barviva
        COLOR_PALETTE.put(Material.INK_SAC, (byte) 118);      // Černá
        COLOR_PALETTE.put(Material.BONE_MEAL, (byte) 34);     // Bílá
        COLOR_PALETTE.put(Material.COCOA_BEANS, (byte) 106);  // Hnědá
        COLOR_PALETTE.put(Material.LAPIS_LAZULI, (byte) 102); // Modrá
        COLOR_PALETTE.put(Material.GLOW_INK_SAC, (byte) 94);  // Azurová (Cyan)
    }

    /**
     * Získá ID barvy na mapě pro daný materiál.
     */
    public static byte getMapColor(Material material) {
        return COLOR_PALETTE.getOrDefault(material, DEFAULT_COLOR);
    }

    /**
     * Ověří, zda je materiál použitelný jako barvivo.
     */
    public static boolean isValidDye(Material material) {
        return COLOR_PALETTE.containsKey(material);
    }

    /**
     * Získá materiál barviva podle barvy na mapě (Reverse Lookup).
     * Používá se pro funkci Kapátka (Pipette).
     *
     * @param colorId Byte barvy z mapy
     * @return Materiál barviva nebo null, pokud barva není v paletě.
     */
    public static Material getDyeFromColor(byte colorId) {
        if (colorId == 0) return null; // Průhledná/Prázdná

        // Minecraft barvy jsou uloženy ve skupinách po 4 (Stíny: 0, 1, 2, 3)
        // Musíme získat základní index barvy (děleno 4) pro porovnání.
        int targetBaseIndex = (colorId & 0xFF) / 4;

        for (Map.Entry<Material, Byte> entry : COLOR_PALETTE.entrySet()) {
            int paletteBaseIndex = (entry.getValue() & 0xFF) / 4;

            if (targetBaseIndex == paletteBaseIndex) {
                // Našli jsme shodu!
                // Preferujeme "DYE" verze před surovinami (např. BLACK_DYE před INK_SAC),
                // ale EnumMap vrací v přirozeném pořadí, což je obvykle v pořádku.
                return entry.getKey();
            }
        }
        return null;
    }
    // --- VLOŽIT NA KONEC TŘÍDY ---
    public static byte getShadedColor(byte baseColor, Material offHandItem) {
        // Pokud držíš uhlí -> ztmavit
        if (offHandItem == Material.COAL || offHandItem == Material.CHARCOAL) {
            return (byte) (baseColor - 1); // Darker
        }
        // Pokud držíš flint -> hodně ztmavit
        if (offHandItem == Material.FLINT) {
            return (byte) (baseColor - 2); // Darkest
        }
        return baseColor;
    }
}
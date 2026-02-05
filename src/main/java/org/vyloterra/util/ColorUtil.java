package org.vyloterra.util;

import org.bukkit.Material;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class ColorUtil {

    // Mapování Material -> Base Color ID (Minecraft Map Palette)
    // ID jsou vždy první barva ze sady 4 variant (Base color)
    private static final Map<Material, Byte> COLOR_PALETTE = new EnumMap<>(Material.class);
    private static final Map<Integer, Material> REVERSE_PALETTE = new HashMap<>();

    static {
        // Formát: Base ID (vždy násobek 4 + 2 pro základní jas)
        // Minecraft 1.16+ barvy
        register(Material.WHITE_DYE, 34);       // White
        register(Material.ORANGE_DYE, 62);      // Orange
        register(Material.MAGENTA_DYE, 66);     // Magenta
        register(Material.LIGHT_BLUE_DYE, 70);  // Light Blue
        register(Material.YELLOW_DYE, 74);      // Yellow
        register(Material.LIME_DYE, 78);        // Lime
        register(Material.PINK_DYE, 82);        // Pink
        register(Material.GRAY_DYE, 86);        // Gray
        register(Material.LIGHT_GRAY_DYE, 90);  // Light Gray
        register(Material.CYAN_DYE, 94);        // Cyan
        register(Material.PURPLE_DYE, 98);      // Purple
        register(Material.BLUE_DYE, 102);       // Blue
        register(Material.BROWN_DYE, 106);      // Brown
        register(Material.GREEN_DYE, 110);      // Green
        register(Material.RED_DYE, 114);        // Red
        register(Material.BLACK_DYE, 118);      // Black

        // Extra materiály (volitelné)
        register(Material.INK_SAC, 118);        // Black
        register(Material.LAPIS_LAZULI, 102);   // Blue
        register(Material.COCOA_BEANS, 106);    // Brown
        register(Material.BONE_MEAL, 34);       // White
    }

    private static void register(Material mat, int colorId) {
        COLOR_PALETTE.put(mat, (byte) colorId);
        // Pro reverzní lookup (kapátko) ukládáme base index
        REVERSE_PALETTE.put(colorId / 4, mat);
    }

    public static boolean isValidDye(Material material) {
        return COLOR_PALETTE.containsKey(material);
    }

    public static byte getMapColor(Material material) {
        return COLOR_PALETTE.getOrDefault(material, (byte) 0);
    }

    public static Material getDyeFromColor(byte colorId) {
        if (colorId == 0) return null;
        // Vydělíme 4, abychom dostali "rodinu" barev
        int baseIndex = (Byte.toUnsignedInt(colorId)) / 4;
        return REVERSE_PALETTE.get(baseIndex);
    }

    /**
     * Vylepšené stínování.
     * Minecraft barvy fungují takto:
     * Index 0: Nejtmavší
     * Index 1: Tmavší
     * Index 2: Základní (Normal)
     * Index 3: Nejsvětlejší (pouze pro mapy, neblokuje se)
     */
    public static byte getShadedColor(byte baseColor, Material offHandItem) {
        int baseInt = Byte.toUnsignedInt(baseColor);

        // Získáme startovní index rodiny barev (např. 114 pro červenou -> rodina začíná na 112)
        int colorFamilyStart = (baseInt / 4) * 4;

        // Výchozí je základní barva (offset 2)
        int offset = 2;

        if (offHandItem == Material.FEATHER) {
            offset = 3; // Zesvětlení (Highlight)
        } else if (offHandItem == Material.COAL || offHandItem == Material.CHARCOAL) {
            offset = 1; // Ztmavení (Shadow)
        } else if (offHandItem == Material.FLINT || offHandItem == Material.BLACK_DYE) {
            offset = 0; // Hluboký stín (Deep Shadow)
        }

        return (byte) (colorFamilyStart + offset);
    }
}

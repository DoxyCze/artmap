package org.vyloterra.util;

import org.bukkit.Material;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class ColorUtil {

    private static final Map<Material, Byte> COLOR_PALETTE = new EnumMap<>(Material.class);
    // Optimalizace: Reverzní mapa pro okamžité nalezení materiálu podle ID barvy (O(1))
    private static final Map<Integer, Material> REVERSE_PALETTE = new HashMap<>();

    // Fallback barva (Pale Green)
    private static final byte DEFAULT_COLOR = 4;

    static {
        // Standardní barviva
        register(Material.WHITE_DYE, 34);
        register(Material.ORANGE_DYE, 62);
        register(Material.MAGENTA_DYE, 66);
        register(Material.LIGHT_BLUE_DYE, 70);
        register(Material.YELLOW_DYE, 74);
        register(Material.LIME_DYE, 78);
        register(Material.PINK_DYE, 82);
        register(Material.GRAY_DYE, 86);
        register(Material.LIGHT_GRAY_DYE, 90);
        register(Material.CYAN_DYE, 94);
        register(Material.PURPLE_DYE, 98);
        register(Material.BLUE_DYE, 102);
        register(Material.BROWN_DYE, 106);
        register(Material.GREEN_DYE, 110);
        register(Material.RED_DYE, 114);
        register(Material.BLACK_DYE, 118);

        // Alternativní předměty
        register(Material.INK_SAC, 118);      // Černá
        register(Material.BONE_MEAL, 34);     // Bílá
        register(Material.COCOA_BEANS, 106);  // Hnědá
        register(Material.LAPIS_LAZULI, 102); // Modrá
        register(Material.GLOW_INK_SAC, 94);  // Azurová
    }

    /**
     * Pomocná metoda pro registraci do obou map najednou.
     */
    private static void register(Material mat, int colorId) {
        COLOR_PALETTE.put(mat, (byte) colorId);
        // Ukládáme si "Base Index" (id / 4), abychom našli barvu bez ohledu na stín
        REVERSE_PALETTE.put(colorId / 4, mat);
    }

    public static byte getMapColor(Material material) {
        return COLOR_PALETTE.getOrDefault(material, DEFAULT_COLOR);
    }

    public static boolean isValidDye(Material material) {
        return COLOR_PALETTE.containsKey(material);
    }

    /**
     * Rychlé získání materiálu podle barvy (pro Kapátko).
     */
    public static Material getDyeFromColor(byte colorId) {
        if (colorId == 0) return null; // Průhledná

        int baseIndex = (Byte.toUnsignedInt(colorId)) / 4;
        return REVERSE_PALETTE.get(baseIndex);
    }

    /**
     * Vypočítá stínovanou barvu a zajistí, aby nepřetekla do jiné barvy.
     */
    public static byte getShadedColor(byte baseColor, Material offHandItem) {
        int modifier = 0;

        // Tmavší (Coal/Charcoal) -> Index 1
        if (offHandItem == Material.COAL || offHandItem == Material.CHARCOAL) {
            modifier = 1;
        }
        // Nejtmavší (Flint) -> Index 0
        else if (offHandItem == Material.FLINT) {
            modifier = 2;
        }
        // Volitelné: Zesvětlení (Feather) -> Index 3 (Pokud bys chtěl podporovat i světlejší)
        else if (offHandItem == Material.FEATHER) {
            modifier = -1;
        }

        if (modifier == 0) return baseColor;

        int baseId = Byte.toUnsignedInt(baseColor);
        int newId = baseId - modifier;

        // BEZPEČNOSTNÍ KONTROLA:
        // Barvy v MC jsou po skupinách 4 (např. 32, 33, 34, 35).
        // Musíme ověřit, že nová barva je stále ve stejné skupině.
        // Pokud (old / 4) != (new / 4), znamená to, že jsme přetekli do jiné barvy.
        if ((baseId / 4) != (newId / 4)) {
            return baseColor; // Nelze provést stínování, vracíme původní
        }

        return (byte) newId;
    }
}
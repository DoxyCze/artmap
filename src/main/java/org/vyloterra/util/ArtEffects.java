package org.vyloterra.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

public class ArtEffects {

    private static final Random random = new Random();

    // Mapování barev pro DustOptions (zůstalo, kdybychom ho chtěli kombinovat)
    private static final Map<Material, Color> DYE_TO_COLOR = new EnumMap<>(Material.class);

    static {
        DYE_TO_COLOR.put(Material.WHITE_DYE, Color.WHITE);
        DYE_TO_COLOR.put(Material.ORANGE_DYE, Color.ORANGE);
        DYE_TO_COLOR.put(Material.MAGENTA_DYE, Color.FUCHSIA);
        DYE_TO_COLOR.put(Material.LIGHT_BLUE_DYE, Color.AQUA);
        DYE_TO_COLOR.put(Material.YELLOW_DYE, Color.YELLOW);
        DYE_TO_COLOR.put(Material.LIME_DYE, Color.LIME);
        DYE_TO_COLOR.put(Material.PINK_DYE, Color.fromRGB(255, 192, 203));
        DYE_TO_COLOR.put(Material.GRAY_DYE, Color.GRAY);
        DYE_TO_COLOR.put(Material.LIGHT_GRAY_DYE, Color.SILVER);
        DYE_TO_COLOR.put(Material.CYAN_DYE, Color.TEAL);
        DYE_TO_COLOR.put(Material.PURPLE_DYE, Color.PURPLE);
        DYE_TO_COLOR.put(Material.BLUE_DYE, Color.BLUE);
        DYE_TO_COLOR.put(Material.BROWN_DYE, Color.fromRGB(139, 69, 19));
        DYE_TO_COLOR.put(Material.GREEN_DYE, Color.GREEN);
        DYE_TO_COLOR.put(Material.RED_DYE, Color.RED);
        DYE_TO_COLOR.put(Material.BLACK_DYE, Color.BLACK);
    }

    public static void playPaintEffect(Location location, Material dye, int brushSize) {
        World world = location.getWorld();
        if (world == null) return;

        // Upravíme pozici mírně směrem k hráči, aby efekt nebyl "v plátně"
        Location effectLoc = location.clone().add(0, 0, 0);

        // --- 1. EFEKT: STŘÍKAJÍCÍ BARVA (Item Crack) ---
        // Toto vytvoří efekt, jako by se barvivo "rozbilo" nebo rozprsklo o plátno.
        // Vypadá to mnohem lépe než Redstone Dust.
        if (dye != null && dye != Material.AIR) {
            ItemStack itemType = new ItemStack(dye);
            // Spawneme 3-5 kousků barviva
            int count = 3 + brushSize;
            // Extra data (ItemStack) říkají, jak má částice vypadat
            world.spawnParticle(Particle.ITEM, effectLoc, count, 0.1, 0.1, 0.1, 0.05, itemType);
        }

        // --- 2. EFEKT: ELEGANTNÍ JISKŘIČKY (Wax Off / End Rod) ---
        // Přidá to "magický" nebo "umělecký" nádech
        if (random.nextInt(3) == 0) {
            // WAX_OFF vypadá jako malá bílá hvězdička/křížek - skvělé pro "tah štětcem"
            world.spawnParticle(Particle.WAX_OFF, effectLoc, 1, 0.1, 0.1, 0.1, 0.0);
        }

        // --- 3. GLOW INK SAC SPECIÁL ---
        if (dye == Material.GLOW_INK_SAC) {
            world.spawnParticle(Particle.GLOW, effectLoc, 2, 0.1, 0.1, 0.1, 0.05);
            world.spawnParticle(Particle.END_ROD, effectLoc, 1, 0.05, 0.05, 0.05, 0.01);
        }

        // --- 4. ZVUK ---
        // Zvuk přizpůsobíme velikosti štětce
        if (random.nextInt(5) == 0) { // Nechceme spamovat zvuk každým tikem
            float pitch = 1.0f + (random.nextFloat() * 0.5f); // Náhodná výška tónu

            // Pro větší štětec hlubší zvuk (mokřejší), pro malý vyšší
            if (brushSize >= 2) {
                world.playSound(location, Sound.BLOCK_HONEY_BLOCK_PLACE, 0.3f, pitch - 0.2f); // "Mokrý" zvuk
            } else {
                world.playSound(location, Sound.ITEM_BOOK_PAGE_TURN, 0.4f, pitch + 0.5f); // "Šustivý" zvuk tužky
            }
        }
    }

    public static void playBrushChangeEffect(Player player, int newSize) {
        float pitch = 0.6f + (newSize * 0.4f);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, pitch);

        // Efekt "Noty" nad hlavou při změně
        player.getWorld().spawnParticle(
                Particle.NOTE,
                player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5)),
                3, 0.2, 0.2, 0.2,
                newSize / 24.0 // Barva noty závisí na offsetu (hack v Minecraftu)
        );
    }
}

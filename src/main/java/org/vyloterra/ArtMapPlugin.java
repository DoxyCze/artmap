package org.vyloterra;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.vyloterra.commands.ArtCommand;
import java.util.List;

public class ArtMapPlugin extends JavaPlugin {

    private static ArtMapPlugin instance;
    private PaintingManager paintingManager;
    private ArtListener artListener;
    private ArtProtocol artProtocol;
    private String dateFormat;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config
        saveDefaultConfig();
        reloadValues();
        registerCanvasRecipe();

        // 2. Inicializace Manažera
        this.paintingManager = new PaintingManager(this);

        // 3. Registrace Listeneru
        this.artListener = new ArtListener(this, paintingManager);
        getServer().getPluginManager().registerEvents(artListener, this);

        // 4. ProtocolLib
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            this.artProtocol = new ArtProtocol(this, paintingManager);
            this.artProtocol.register();
            getLogger().info("ProtocolLib nalezen! Plynulé malování aktivováno.");
        } else {
            getLogger().severe("CHYBA: ProtocolLib nebyl nalezen!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Příkazy
        PluginCommand cmd = getCommand("artmap");
        if (cmd != null) {
            ArtCommand executor = new ArtCommand(this, paintingManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("ArtMap úspěšně načten!");
    }

    public void reloadValues() {
        reloadConfig();
        this.dateFormat = getConfig().getString("date-format", "dd.MM.yyyy HH:mm");
        if (paintingManager != null) {
            paintingManager.reloadValues();
        }
    }

    @Override
    public void onDisable() {
        if (paintingManager != null) {
            getLogger().info("Ukládám rozpracované obrazy...");
            paintingManager.forceSaveAll();
        }
        getLogger().info("ArtMap bezpečně ukončen.");
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public static ArtMapPlugin getInstance() {
        return instance;
    }

    private void registerCanvasRecipe() {
        if (!getConfig().getBoolean("canvas-recipe.enabled", true)) return;

        org.bukkit.inventory.ItemStack result = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ITEM_FRAME);
        org.bukkit.inventory.meta.ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Malířské plátno");
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "is_canvas"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            result.setItemMeta(meta);
        }

        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(this, "artmap_canvas");
        org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, result);

        List<String> pattern = getConfig().getStringList("canvas-recipe.pattern");
        if (pattern.size() != 3) {
            getLogger().warning("Chyba v configu: 'canvas-recipe.pattern' musí mít 3 řádky!");
            return;
        }
        recipe.shape(pattern.toArray(new String[0]));

        org.bukkit.configuration.ConfigurationSection ingredients = getConfig().getConfigurationSection("canvas-recipe.ingredients");
        if (ingredients != null) {
            for (String charKey : ingredients.getKeys(false)) {
                String itemID = ingredients.getString(charKey);
                org.bukkit.inventory.ItemStack ingItem = fetchExternalItem(itemID);
                if (ingItem != null) {
                    recipe.setIngredient(charKey.charAt(0), ingItem.getType());
                }
            }
        }

        getServer().removeRecipe(key);
        getServer().addRecipe(recipe);
        getLogger().info("Recept na Malířské plátno byl zaregistrován.");
    }

    private org.bukkit.inventory.ItemStack fetchExternalItem(String id) {
        if (id == null) return null;
        String[] parts = id.split(":", 2);
        if (parts.length < 2) return new org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(id.toUpperCase()));

        String prefix = parts[0].toLowerCase();
        String name = parts[1];

        try {
            // Pouze ItemsAdder podpora
            if (prefix.equals("itemsadder") && getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
                return dev.lone.itemsadder.api.CustomStack.getInstance(name).getItemStack();
            }
        } catch (Throwable t) {
            getLogger().warning("Nepodařilo se načíst item z pluginu " + prefix + ": " + name);
        }

        // Fallback na vanilla
        return new org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(name.toUpperCase()));
    }
}

package org.vyloterra;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.vyloterra.commands.ArtCommand;

public class ArtMapPlugin extends JavaPlugin {

    private static ArtMapPlugin instance;
    private PaintingManager paintingManager; // NOVÉ
    private ArtListener artListener;
    private ArtProtocol artProtocol;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config
        saveDefaultConfig();

        // 2. Inicializace Manažera (TOTO JE KLÍČOVÉ)
        // Musí být vytvořen před Listenerem a Commandy
        this.paintingManager = new PaintingManager(this);

        // 3. Registrace Listeneru
        // Předáváme mu instanci manažera
        this.artListener = new ArtListener(this, paintingManager);
        getServer().getPluginManager().registerEvents(artListener, this);

        // 4. ProtocolLib
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            // I Protocol potřebuje vědět o manažerovi
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
            // I Command potřebuje manažera pro UNDO a RELOAD
            ArtCommand executor = new ArtCommand(this, paintingManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("ArtMap úspěšně načten!");
    }

    @Override
    public void onDisable() {
        if (paintingManager != null) {
            getLogger().info("Ukládám rozpracované obrazy...");
            paintingManager.forceSaveAll(); // Ukládání řeší manažer
        }
        getLogger().info("ArtMap bezpečně ukončen.");
    }

    public static ArtMapPlugin getInstance() {
        return instance;
    }
}
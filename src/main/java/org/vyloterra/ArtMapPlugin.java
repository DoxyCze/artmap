package org.vyloterra;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.vyloterra.commands.ArtCommand;

public class ArtMapPlugin extends JavaPlugin {

    private static ArtMapPlugin instance;
    private ArtListener artListener;
    private ArtProtocol artProtocol;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config
        saveDefaultConfig();

        // 2. Registrace Listeneru (Logika hry, Undo, Malování)
        this.artListener = new ArtListener(this);
        getServer().getPluginManager().registerEvents(artListener, this);

        // 3. ProtocolLib (Packet Interception - Plynulé malování)
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            this.artProtocol = new ArtProtocol(this, artListener);
            this.artProtocol.register();
            getLogger().info("ProtocolLib nalezen! Plynulé malování aktivováno. (Anti-Break ochrana zapnuta)");
        } else {
            getLogger().severe("CHYBA: ProtocolLib nebyl nalezen! Plugin ArtMap nemůže fungovat správně.");
            getLogger().severe("Prosím stáhni a nainstaluj ProtocolLib.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Příkazy (Name, Delete, Undo, Copy...)
        PluginCommand cmd = getCommand("artmap");
        if (cmd != null) {
            // ZDE BYLA CHYBA: Musíme předat i 'artListener', aby fungovalo Undo!
            ArtCommand executor = new ArtCommand(this, artListener);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("ArtMap úspěšně načten! Malířská plátna připravena.");
    }

    @Override
    public void onDisable() {
        if (artListener != null) {
            // Vynutíme uložení všech rozpracovaných map
            getLogger().info("Ukládám rozpracované obrazy...");
            artListener.forceSaveAll();
        }
        getLogger().info("ArtMap bezpečně ukončen.");
    }

    public static ArtMapPlugin getInstance() {
        return instance;
    }
}
package org.vyloterra.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.vyloterra.ArtMapPlugin;

import java.util.List;

public class Lang {

    /**
     * Pošle zprávu hráči nebo do konzole (s barvami a prefixem).
     */
    public static void send(CommandSender sender, String key) {
        send(sender, key, null);
    }

    /**
     * Pošle zprávu s nahrazením {val} za konkrétní hodnotu.
     */
    public static void send(CommandSender sender, String key, String value) {
        String msg = get(key);
        if (value != null) {
            msg = msg.replace("{val}", value);
        }
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    /**
     * Pošle zprávu do Action Baru (nad inventář).
     */
    public static void sendActionBar(Player player, String key, String value) {
        String msg = getRaw(key); // Bez prefixu pro Action Bar
        if (value != null) {
            msg = msg.replace("{val}", value);
        }
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    /**
     * Získá surový string z configu a přidá prefix (pokud to není list).
     */
    public static String get(String key) {
        FileConfiguration config = ArtMapPlugin.getInstance().getConfig();
        String prefix = config.getString("messages.prefix", "&6[ArtMap] ");
        String text = config.getString("messages." + key, "&cChybí překlad: " + key);
        return prefix + text;
    }

    /**
     * Získá surový string BEZ prefixu (pro Action Bar, Lore atd.).
     */
    public static String getRaw(String key) {
        return ArtMapPlugin.getInstance().getConfig().getString("messages." + key, key);
    }

    /**
     * Získá list stringů (pro nápovědu nebo Lore).
     */
    public static List<String> getList(String key) {
        return ArtMapPlugin.getInstance().getConfig().getStringList("messages." + key);
    }
}
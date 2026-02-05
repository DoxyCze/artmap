package org.vyloterra.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.vyloterra.ArtMapPlugin;

import java.util.List;

public class Lang {


    public static void send(CommandSender sender, String key) {
        send(sender, key, null);
    }


    public static void send(CommandSender sender, String key, String value) {
        String msg = get(key);
        if (value != null) {
            msg = msg.replace("{val}", value);
        }
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }


    public static void sendActionBar(Player player, String key, String value) {
        String msg = getRaw(key); // Bez prefixu pro Action Bar
        if (value != null) {
            msg = msg.replace("{val}", value);
        }
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    public static String get(String key) {
        FileConfiguration config = ArtMapPlugin.getInstance().getConfig();
        String prefix = config.getString("messages.prefix", "&6[ArtMap] ");
        String text = config.getString("messages." + key, "&cChybí překlad: " + key);
        return prefix + text;
    }

    public static String getRaw(String key) {
        return ArtMapPlugin.getInstance().getConfig().getString("messages." + key, key);
    }

    public static List<String> getList(String key) {
        return ArtMapPlugin.getInstance().getConfig().getStringList("messages." + key);
    }

    public static String getString(String path, String def) {
        String message = org.vyloterra.ArtMapPlugin.getInstance().getConfig().getString(path, def);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(message != null ? message : def).content();
    }

}

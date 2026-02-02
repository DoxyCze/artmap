package org.vyloterra;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.vyloterra.util.ColorUtil;

public class ArtProtocol {

    private final ArtMapPlugin plugin;
    private final PaintingManager paintingManager; // ZMĚNA: Manažer
    private final NamespacedKey keyLocked;

    public ArtProtocol(ArtMapPlugin plugin, PaintingManager paintingManager) {
        this.plugin = plugin;
        this.paintingManager = paintingManager;
        this.keyLocked = new NamespacedKey(plugin, "artmap_locked");
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                ItemStack hand = player.getInventory().getItemInMainHand();
                ItemStack offHand = player.getInventory().getItemInOffHand();

                boolean hasDye = ColorUtil.isValidDye(hand.getType()) || ColorUtil.isValidDye(offHand.getType());
                boolean isTool = hand.getType() == Material.FEATHER ||
                        hand.getType() == Material.SPONGE ||
                        hand.getType() == Material.WET_SPONGE;

                if ((!hasDye && !isTool) || !player.hasPermission("artmap.paint")) {
                    return;
                }

                EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().readSafely(0);

                if (action == EnumWrappers.EntityUseAction.ATTACK) {
                    event.setCancelled(true);
                    return;
                }

                event.setCancelled(true);
                int entityId = event.getPacket().getIntegers().read(0);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Entity entity = ProtocolLibrary.getProtocolManager().getEntityFromID(player.getWorld(), entityId);

                    if (entity instanceof ItemFrame frame) {
                        org.bukkit.event.player.PlayerInteractEntityEvent checkEvent = new org.bukkit.event.player.PlayerInteractEntityEvent(player, frame);
                        Bukkit.getPluginManager().callEvent(checkEvent);
                        if (checkEvent.isCancelled()) return;

                        ItemStack item = frame.getItem();
                        if (item.getType() == Material.FILLED_MAP || item.getType() == Material.MAP) {

                            if (item.hasItemMeta()) {
                                ItemMeta meta = item.getItemMeta();
                                if (meta.getPersistentDataContainer().has(keyLocked, PersistentDataType.BYTE)) {
                                    return;
                                }
                            }

                            // ZMĚNA: Voláme manažera místo listeneru
                            paintingManager.updatePaintingState(player, frame);
                        }
                    }
                });
            }
        });
    }
}
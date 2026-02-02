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
    private final ArtListener listener;
    private final NamespacedKey keyLocked;

    public ArtProtocol(ArtMapPlugin plugin, ArtListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.keyLocked = new NamespacedKey(plugin, "artmap_locked");
    }

    public void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();

                // Získání itemů v rukou
                ItemStack hand = player.getInventory().getItemInMainHand();
                ItemStack offHand = player.getInventory().getItemInOffHand();

                // 1. Je to barvivo?
                boolean hasDye = ColorUtil.isValidDye(hand.getType()) || ColorUtil.isValidDye(offHand.getType());

                // 2. Je to nástroj? (TOTO TU CHYBĚLO)
                boolean isTool = hand.getType() == Material.FEATHER ||
                        hand.getType() == Material.SPONGE ||
                        hand.getType() == Material.WET_SPONGE;

                // Pokud nedrží ani barvu, ani nástroj, nebo nemá práva -> nic neděláme (defaultní chování MC)
                if ((!hasDye && !isTool) || !player.hasPermission("artmap.paint")) {
                    return;
                }

                EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().readSafely(0);

                // --- OCHRANA: LEVÉ KLIKNUTÍ (ATTACK) ---
                // Pokud hráč drží malířské potřeby a klikne LEVÝM, zakážeme to, aby nerozbil rám.
                if (action == EnumWrappers.EntityUseAction.ATTACK) {
                    event.setCancelled(true);
                    return;
                }

                // --- MALOVÁNÍ / POUŽITÍ NÁSTROJE (INTERACT) ---
                // 1. Zrušíme packet -> Rámeček se NEOTOČÍ
                event.setCancelled(true);

                // 2. Zjistíme ID entity
                int entityId = event.getPacket().getIntegers().read(0);

                // 3. Pošleme signál na hlavní vlákno (Listener)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Entity entity = ProtocolLibrary.getProtocolManager().getEntityFromID(player.getWorld(), entityId);

                    if (entity instanceof ItemFrame frame) {
                        // Vyvoláme falešný event, aby ostatní pluginy (WG, Residence) mohly zasáhnout
                        org.bukkit.event.player.PlayerInteractEntityEvent checkEvent = new org.bukkit.event.player.PlayerInteractEntityEvent(player, frame);
                        Bukkit.getPluginManager().callEvent(checkEvent);
                        if (checkEvent.isCancelled()) {
                            return; // Někdo to zakázal -> končíme
                        }
                        // ------------------
                        ItemStack item = frame.getItem();
                        // Musí tam být mapa
                        if (item.getType() == Material.FILLED_MAP || item.getType() == Material.MAP) {

                            // Kontrola zámku (Podepsané dílo)
                            if (item.hasItemMeta()) {
                                ItemMeta meta = item.getItemMeta();
                                if (meta.getPersistentDataContainer().has(keyLocked, PersistentDataType.BYTE)) {
                                    // Je zamčeno -> Nic neděláme (Listener to ignoruje)
                                    // Můžeš sem přidat zprávu "To je zamčené", ale bacha na spam.
                                    return;
                                }
                            }

                            // Předáme akci Listeneru, který vyřeší, jestli se má malovat, gumovat nebo kapátkovat
                            listener.updatePaintingState(player, frame);
                        }
                    }
                });
            }
        });
    }
}
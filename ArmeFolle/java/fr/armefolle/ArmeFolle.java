package fr.armefolle;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * /armefolle : marque l'objet tenu en main.
 * Un objet marque recoit un lore et n'est PAS drop a la mort (garde en inventaire).
 * A mettre dans : ArmeFolle/java/fr/armefolle/ArmeFolle.java
 */
public class ArmeFolle extends LoadedPlugin implements Listener {

    private NamespacedKey key;

    private static final Component LORE_LINE = Component.text("\uD83D\uDC80 Ne se perd pas a la mort")
            .color(NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false);

    @Override
    public void onEnable() {
        key = new NamespacedKey(getHost(), "armefolle");
        registerListener(this);

        registerCommand("armefolle", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Commande reservee aux joueurs.")
                        .color(NamedTextColor.RED));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType().isAir()) {
                player.sendMessage(Component.text("Tiens un objet en main d'abord !")
                        .color(NamedTextColor.RED));
                return true;
            }

            ItemMeta meta = item.getItemMeta();

            // Marque l'objet de facon fiable (invisible)
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

            // Ajoute le lore visible
            List<Component> lore = (meta.lore() == null) ? new ArrayList<>() : new ArrayList<>(meta.lore());
            if (!lore.contains(LORE_LINE)) {
                lore.add(LORE_LINE);
            }
            meta.lore(lore);

            item.setItemMeta(meta);

            player.sendMessage(Component.text("\uD83D\uDC80 Cet objet ne se perdra plus a la mort !")
                    .color(NamedTextColor.GREEN));
            return true;
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (isArmeFolle(item)) {
                e.getItemsToKeep().add(item); // garde l'objet apres le respawn
                it.remove();                  // et ne le drop pas
            }
        }
    }

    private boolean isArmeFolle(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(key, PersistentDataType.BYTE);
    }

    @Override
    public void onDisable() {
        getLogger().info("ArmeFolle desactive.");
    }
}

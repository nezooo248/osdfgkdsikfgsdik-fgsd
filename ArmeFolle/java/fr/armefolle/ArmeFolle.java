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
 * Un objet marqué reçoit un lore et n'est PAS drop à la mort (gardé en inventaire).
 * Seuls les OP peuvent utiliser la commande.
 */
public class ArmeFolle extends LoadedPlugin implements Listener {

    private NamespacedKey key;

    private static final Component LORE_LINE = Component.text("💀 Ne se perd pas à la mort")
            .color(NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false);

    @Override
    public void onEnable() {
        key = new NamespacedKey(getHost(), "armefolle");
        registerListener(this);

        registerCommand("armefolle", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Commande réservée aux joueurs.")
                        .color(NamedTextColor.RED));
                return true;
            }

            // Vérifie que le joueur est OP
            if (!player.isOp()) {
                player.sendMessage(Component.text("Vous devez être OP pour utiliser cette commande.")
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

            // Marque l'objet de façon invisible
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

            // Ajoute le lore s'il n'est pas déjà présent
            List<Component> lore = meta.lore() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(meta.lore());

            if (!lore.contains(LORE_LINE)) {
                lore.add(LORE_LINE);
            }

            meta.lore(lore);
            item.setItemMeta(meta);

            player.sendMessage(Component.text("💀 Cet objet ne se perdra plus à la mort !")
                    .color(NamedTextColor.GREEN));

            return true;
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> iterator = event.getDrops().iterator();

        while (iterator.hasNext()) {
            ItemStack item = iterator.next();

            if (isArmeFolle(item)) {
                event.getItemsToKeep().add(item); // Garde l'objet
                iterator.remove();                // Empêche le drop
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
        getLogger().info("ArmeFolle désactivé.");
    }
}

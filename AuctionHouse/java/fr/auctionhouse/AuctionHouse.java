package fr.auctionhouse;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hôtel des ventes (économie Vault / EssentialsX via réflexion, aucune dépendance à compiler).
 *   /ah                -> menu de toutes les annonces
 *   /ah sell <prix>    -> met l'objet en main en vente (ex: 100k, 1.5m, 100000)
 *   /ah list           -> tes annonces
 *   /ah cancel <n>     -> retire ton annonce n° n
 *   /ah <recherche>    -> menu filtré par correspondance
 *
 * Permissions : ah.use ; ah.sell.<nombre> = limite d'annonces (défaut 5, ah.sell.* = illimité).
 */
public class AuctionHouse extends LoadedPlugin implements Listener {

    private static final int DEFAULT_LIMIT = 5;
    private static final int PAGE_SIZE = 45; // slots 0..44, dernière rangée = navigation

    private final List<Listing> listings = new ArrayList<>();
    private VaultEco eco; // wrapper réflexif

    @Override
    public void onEnable() {
        registerListener(this);
        eco = VaultEco.hook();
        if (eco == null) {
            getLogger().warning("Vault/économie introuvable : les ventes seront bloquées tant qu'aucune éco n'est présente.");
        }
        registerCommand("ah", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande réservée aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("ah.use")) {
                player.sendMessage(msg("Vous n'avez pas la permission d'utiliser l'hôtel des ventes.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                openMenu(player, null, 0);
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "sell"   -> handleSell(player, args);
                case "list"   -> handleList(player);
                case "cancel" -> handleCancel(player, args);
                default       -> openMenu(player, String.join(" ", args).toLowerCase(), 0);
            }
            return true;
        });
        getLogger().info("AuctionHouse activé.");
    }

    private VaultEco economy() {
        if (eco == null) eco = VaultEco.hook(); // nouvelle tentative si Vault a chargé après nous
        return eco;
    }

    private String money(double amount) {
        VaultEco e = economy();
        return e != null ? e.format(amount) : String.valueOf((long) amount);
    }

    // ---------- Sous-commandes ----------

    private void handleSell(Player player, String[] args) {
        if (economy() == null) {
            player.sendMessage(msg("Économie indisponible (Vault/EssentialsX manquant).", NamedTextColor.RED));
            return;
        }
        int limit = getSellLimit(player);
        long current = listings.stream().filter(l -> l.seller.equals(player.getUniqueId())).count();
        if (current >= limit) {
            player.sendMessage(msg("Limite d'annonces atteinte (" + limit + ").", NamedTextColor.RED));
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType().isAir()) {
            player.sendMessage(msg("Tiens un objet en main d'abord !", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(msg("Usage : /ah sell <prix>  (ex: 100k, 1.5m, 100000)", NamedTextColor.RED));
            return;
        }
        double price;
        try {
            price = parsePrice(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(msg("Prix invalide. Exemples : 100, 100000, 100k, 1.5m", NamedTextColor.RED));
            return;
        }
        if (price <= 0) {
            player.sendMessage(msg("Le prix doit être supérieur à 0.", NamedTextColor.RED));
            return;
        }
        ItemStack sold = inHand.clone();
        player.getInventory().setItemInMainHand(null);
        listings.add(new Listing(player.getUniqueId(), player.getName(), sold, price));
        player.sendMessage(msg("Objet mis en vente pour " + money(price) + ".", NamedTextColor.GREEN));
    }

    private void handleList(Player player) {
        List<Listing> mine = myListings(player);
        if (mine.isEmpty()) {
            player.sendMessage(msg("Tu n'as aucune annonce active.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(msg("Tes annonces (" + mine.size() + "/" + getSellLimit(player) + ") :", NamedTextColor.GOLD));
        for (int i = 0; i < mine.size(); i++) {
            Listing l = mine.get(i);
            player.sendMessage(Component.text("  #" + (i + 1) + " ", NamedTextColor.GRAY)
                    .append(itemName(l.item).color(NamedTextColor.WHITE))
                    .append(Component.text(" x" + l.item.getAmount(), NamedTextColor.DARK_GRAY))
                    .append(Component.text("  —  " + money(l.price), NamedTextColor.GREEN)));
        }
        player.sendMessage(msg("Utilise /ah cancel <n> pour retirer une annonce.", NamedTextColor.DARK_GRAY));
    }

    private void handleCancel(Player player, String[] args) {
        List<Listing> mine = myListings(player);
        if (args.length < 2) {
            player.sendMessage(msg("Usage : /ah cancel <n>", NamedTextColor.RED));
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(msg("Numéro invalide.", NamedTextColor.RED));
            return;
        }
        if (idx < 1 || idx > mine.size()) {
            player.sendMessage(msg("Aucune annonce n°" + idx + ".", NamedTextColor.RED));
            return;
        }
        Listing l = mine.get(idx - 1);
        listings.remove(l);
        giveItem(player, l.item);
        player.sendMessage(msg("Annonce retirée, objet récupéré.", NamedTextColor.GREEN));
    }

    // ---------- Menu (GUI) ----------

    private void openMenu(Player player, String query, int page) {
        List<Listing> view = new ArrayList<>();
        for (Listing l : listings) {
            if (query == null || matches(l.item, query)) view.add(l);
        }
        int totalPages = Math.max(1, (int) Math.ceil(view.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        AuctionHolder holder = new AuctionHolder(query, page, view);
        String title = (query == null ? "Hôtel des ventes" : "Recherche : " + query)
                + "  (" + (page + 1) + "/" + totalPages + ")";
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(title));
        holder.inventory = inv;

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < view.size(); i++) {
            inv.setItem(i, displayItem(view.get(start + i), player));
        }
        // Flèches de navigation : bas-gauche (45) et bas-droite (53)
        if (page >

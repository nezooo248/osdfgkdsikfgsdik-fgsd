package fr.auctionhouse;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hôtel des ventes.
 *   /ah                -> ouvre le menu de toutes les annonces
 *   /ah sell <prix>    -> met l'objet en main en vente (prix en émeraudes)
 *   /ah list           -> liste tes annonces
 *   /ah cancel <n>     -> retire ton annonce n° n (objet rendu)
 *   /ah collect        -> récupère les émeraudes de tes ventes hors-ligne
 *   /ah <recherche>    -> cherche une correspondance et ouvre le menu filtré
 *
 * Permissions : ah.use (utiliser) ; ah.sell.<nombre> = limite d'annonces (défaut 5, ah.sell.* = illimité).
 */
public class AuctionHouse extends LoadedPlugin implements Listener {

    private static final int DEFAULT_LIMIT = 5;
    private static final int PAGE_SIZE = 45;               // slots 0..44, dernière rangée = navigation
    private static final Material CURRENCY = Material.EMERALD;

    private final List<Listing> listings = new ArrayList<>();
    private final Map<UUID, Integer> pending = new HashMap<>(); // gains à récupérer (vendeurs hors-ligne)

    @Override
    public void onEnable() {
        registerListener(this);
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
                case "sell"    -> handleSell(player, args);
                case "list"    -> handleList(player);
                case "cancel"  -> handleCancel(player, args);
                case "collect" -> handleCollect(player);
                default        -> openMenu(player, String.join(" ", args).toLowerCase(), 0); // recherche
            }
            return true;
        });
        getLogger().info("AuctionHouse activé.");
    }

    // ---------- Sous-commandes ----------

    private void handleSell(Player player, String[] args) {
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
            player.sendMessage(msg("Usage : /ah sell <prix>", NamedTextColor.RED));
            return;
        }
        int price;
        try {
            price = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(msg("Le prix doit être un nombre.", NamedTextColor.RED));
            return;
        }
        if (price < 1) {
            player.sendMessage(msg("Le prix doit être d'au moins 1 émeraude.", NamedTextColor.RED));
            return;
        }
        ItemStack sold = inHand.clone();
        player.getInventory().setItemInMainHand(null); // on retire l'objet du joueur
        listings.add(new Listing(player.getUniqueId(), player.getName(), sold, price));
        player.sendMessage(msg("Objet mis en vente pour " + price + " émeraude(s).", NamedTextColor.GREEN));
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
                    .append(Component.text("  —  " + l.price + " ém.", NamedTextColor.GREEN)));
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

    private void handleCollect(Player player) {
        int amount = pending.getOrDefault(player.getUniqueId(), 0);
        if (amount <= 0) {
            player.sendMessage(msg("Tu n'as rien à récupérer.", NamedTextColor.YELLOW));
            return;
        }
        pending.remove(player.getUniqueId());
        giveEmeralds(player, amount);
        player.sendMessage(msg("Tu as récupéré " + amount + " émeraude(s).", NamedTextColor.GREEN));
    }

    // ---------- Menu (GUI) ----------

    private void openMenu(Player player, String query, int page) {
        collectPending(player); // on livre les gains en attente à l'ouverture

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
        if (page > 0)               inv.setItem(45, navButton(Material.ARROW, "◀ Page précédente"));
        if (page < totalPages - 1)  inv.setItem(53, navButton(Material.ARROW, "Page suivante ▶"));
        inv.setItem(49, navButton(Material.BARRIER, "Fermer"));

        player.openInventory(inv);
    }

    private ItemStack displayItem(Listing l, Player viewer) {
        ItemStack display = l.item.clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(line("Vendeur : " + l.sellerName, NamedTextColor.GRAY));
        lore.add(line("Prix : " + l.price + " émeraude(s)", NamedTextColor.GREEN));
        lore.add(l.seller.equals(viewer.getUniqueId())
                ? line("Clic pour annuler et récupérer", NamedTextColor.YELLOW)
                : line("Clic pour acheter", NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionHolder holder)) return;
        event.setCancelled(true); // aucun objet ne bouge
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.inventory.equals(event.getClickedInventory())) return;

        int slot = event.getSlot();
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { openMenu(player, holder.query, holder.page - 1); return; }
        if (slot == 53) { openMenu(player, holder.query, holder.page + 1); return; }
        if (slot >= PAGE_SIZE) return;

        int index = holder.page * PAGE_SIZE + slot;
        if (index >= holder.view.size()) return;
        Listing l = holder.view.get(index);

        if (!listings.contains(l)) { // déjà vendue/annulée entre-temps
            player.sendMessage(msg("Cette annonce n'existe plus.", NamedTextColor.RED));
            openMenu(player, holder.query, holder.page);
            return;
        }
        if (l.seller.equals(player.getUniqueId())) { // annuler sa propre annonce
            listings.remove(l);
            giveItem(player, l.item);
            player.sendMessage(msg("Annonce annulée, objet récupéré.", NamedTextColor.GREEN));
            openMenu(player, holder.query, holder.page);
            return;
        }
        if (countEmeralds(player) < l.price) {
            player.sendMessage(msg("Il te faut " + l.price + " émeraude(s).", NamedTextColor.RED));
            return;
        }
        removeEmeralds(player, l.price);
        listings.remove(l);
        giveItem(player, l.item);
        payout(l.seller, l.price);
        player.sendMessage(msg("Objet acheté pour " + l.price + " émeraude(s) !", NamedTextColor.GREEN));

        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            seller.sendMessage(msg(player.getName() + " a acheté ton objet pour " + l.price + " ém.", NamedTextColor.GREEN));
        }
        openMenu(player, holder.query, holder.page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionHolder) {
            for (int raw : event.getRawSlots()) {
                if (raw < 54) { event.setCancelled(true); return; }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        collectPending(event.getPlayer());
    }

    // ---------- Utilitaires ----------

    private int getSellLimit(Player player) {
        int limit = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase();
            if (perm.startsWith("ah.sell.")) {
                String suffix = perm.substring("ah.sell.".length());
                if (suffix.equals("*")) return Integer.MAX_VALUE;
                try {
                    int n = Integer.parseInt(suffix);
                    if (n > limit) limit = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        return limit < 0 ? DEFAULT_LIMIT : limit;
    }

    private List<Listing> myListings(Player player) {
        List<Listing> mine = new ArrayList<>();
        for (Listing l : listings) {
            if (l.seller.equals(player.getUniqueId())) mine.add(l);
        }
        return mine;
    }

    private boolean matches(ItemStack item, String query) {
        if (item.getType().name().toLowerCase().replace('_', ' ').contains(query)) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase();
            return plain.contains(query);
        }
        return false;
    }

    private void payout(UUID sellerId, int amount) {
        Player seller = Bukkit.getPlayer(sellerId);
        if (seller != null && seller.isOnline()) {
            giveEmeralds(seller, amount);
        } else {
            pending.merge(sellerId, amount, Integer::sum);
        }
    }

    private void collectPending(Player player) {
        int amount = pending.getOrDefault(player.getUniqueId(), 0);
        if (amount > 0) {
            pending.remove(player.getUniqueId());
            giveEmeralds(player, amount);
            player.sendMessage(msg("Tu as reçu " + amount + " émeraude(s) de tes ventes.", NamedTextColor.GREEN));
        }
    }

    private int countEmeralds(Player p) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == CURRENCY) total += it.getAmount();
        }
        return total;
    }

    private void removeEmeralds(Player p, int amount) {
        int remaining = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == CURRENCY) {
                int take = Math.min(it.getAmount(), remaining);
                it.setAmount(it.getAmount() - take);
                remaining -= take;
                if (it.getAmount() <= 0) contents[i] = null;
            }
        }
        p.getInventory().setContents(contents);
    }

    private void giveEmeralds(Player p, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stack = Math.min(64, remaining);
            giveItem(p, new ItemStack(CURRENCY, stack));
            remaining -= stack;
        }
    }

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack leftover : p.getInventory().addItem(item.clone()).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }

    private Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.displayName();
        return Component.translatable(item.getType().translationKey());
    }

    private ItemStack navButton(Material mat, String label) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(label, NamedTextColor.YELLOW));
        it.setItemMeta(meta);
        return it;
    }

    private Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onDisable() {
        getLogger().info("AuctionHouse désactivé.");
    }

    // ---------- Types internes ----------

    private static final class Listing {
        final UUID seller;
        final String sellerName;
        final ItemStack item;
        final int price;
        Listing(UUID seller, String sellerName, ItemStack item, int price) {
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
        }
    }

    private static final class AuctionHolder implements InventoryHolder {
        final String query;
        final int page;
        final List<Listing> view;
        Inventory inventory;
        AuctionHolder(String query, int page, List<Listing> view) {
            this.query = query;
            this.page = page;
            this.view = view;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}

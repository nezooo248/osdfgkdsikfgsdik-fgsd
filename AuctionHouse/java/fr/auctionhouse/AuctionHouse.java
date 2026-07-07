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

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hotel des ventes (economie Vault / EssentialsX via reflexion, aucune dependance a compiler).
 *   /ah                -> menu de toutes les annonces
 *   /ah sell <prix>    -> met l'objet en main en vente (ex: 100k, 1.5m, 100000)
 *   /ah list           -> tes annonces
 *   /ah cancel <n>     -> retire ton annonce n. n
 *   /ah <recherche>    -> menu filtre par correspondance
 *
 * Permissions : ah.use ; ah.sell.<nombre> = limite d'annonces (defaut 5, ah.sell.* = illimite).
 */
public class AuctionHouse extends LoadedPlugin implements Listener {

    private static final int DEFAULT_LIMIT = 5;
    private static final int PAGE_SIZE = 45; // slots 0..44, derniere rangee = navigation

    private final List<Listing> listings = new ArrayList<>();
    private VaultEco eco; // wrapper reflexif
    private File dataFile; // fichier de sauvegarde des annonces

    @Override
    public void onEnable() {
        registerListener(this);
        setupDataFile();
        loadData();
        eco = VaultEco.hook();
        if (eco == null) {
            getLogger().warning("Vault/economie introuvable : les ventes seront bloquees tant qu'aucune eco n'est presente.");
        }
        registerCommand("ah", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("ah.use")) {
                player.sendMessage(msg("Vous n'avez pas la permission d'utiliser l'hotel des ventes.", NamedTextColor.RED));
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
        getLogger().info("AuctionHouse active.");
    }

    private VaultEco economy() {
        if (eco == null) eco = VaultEco.hook(); // nouvelle tentative si Vault a charge apres nous
        return eco;
    }

    private String money(double amount) {
        VaultEco e = economy();
        return e != null ? e.format(amount) : String.valueOf((long) amount);
    }

    // ---------- Sous-commandes ----------

    private void handleSell(Player player, String[] args) {
        if (economy() == null) {
            player.sendMessage(msg("Economie indisponible (Vault/EssentialsX manquant).", NamedTextColor.RED));
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
            player.sendMessage(msg("Le prix doit etre superieur a 0.", NamedTextColor.RED));
            return;
        }
        ItemStack sold = inHand.clone();
        player.getInventory().setItemInMainHand(null);
        listings.add(new Listing(player.getUniqueId(), player.getName(), sold, price));
        player.sendMessage(msg("Objet mis en vente pour " + money(price) + ".", NamedTextColor.GREEN));
        broadcastSale(player, sold, price);
        saveData();
    }

    /** Annonce serveur : "JOUEUR a mis en vente ITEM pour la somme de X" (rouge -> orange -> jaune, gras). */
    private void broadcastSale(Player seller, ItemStack item, double price) {
        Component announce = Component.text("[HDV] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(seller.getName(), NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" a mis en vente ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(itemName(item).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + item.getAmount(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" pour la somme de ", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(money(price), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" !", NamedTextColor.YELLOW, TextDecoration.BOLD));
        Bukkit.getServer().sendMessage(announce);
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
                    .append(Component.text("  -  " + money(l.price), NamedTextColor.GREEN)));
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
            player.sendMessage(msg("Numero invalide.", NamedTextColor.RED));
            return;
        }
        if (idx < 1 || idx > mine.size()) {
            player.sendMessage(msg("Aucune annonce n." + idx + ".", NamedTextColor.RED));
            return;
        }
        Listing l = mine.get(idx - 1);
        listings.remove(l);
        giveItem(player, l.item);
        player.sendMessage(msg("Annonce retiree, objet recupere.", NamedTextColor.GREEN));
        saveData();
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
        String title = (query == null ? "Hotel des ventes" : "Recherche : " + query)
                + "  (" + (page + 1) + "/" + totalPages + ")";
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(title));
        holder.inventory = inv;

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < view.size(); i++) {
            inv.setItem(i, displayItem(view.get(start + i), player));
        }
        // Fleches de navigation : bas-gauche (45) et bas-droite (53)
        if (page > 0)              inv.setItem(45, navButton(Material.ARROW, "<< Page precedente"));
        if (page < totalPages - 1) inv.setItem(53, navButton(Material.ARROW, "Page suivante >>"));
        inv.setItem(49, navButton(Material.BARRIER, "Fermer"));

        player.openInventory(inv);
    }

    private ItemStack displayItem(Listing l, Player viewer) {
        ItemStack display = l.item.clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(line("Vendeur : " + l.sellerName, NamedTextColor.GRAY));
        lore.add(line("Prix : " + money(l.price), NamedTextColor.GREEN));
        lore.add(l.seller.equals(viewer.getUniqueId())
                ? line("Clic pour annuler et recuperer", NamedTextColor.YELLOW)
                : line("Clic pour acheter", NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Le clic doit avoir lieu dans l'inventaire du haut (la GUI), pas dans celui du joueur.
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= holder.inventory.getSize()) return;
        int slot = raw; // dans l'inventaire du haut, raw slot == slot
        if (slot == 49) { player.closeInventory(); return; }
        if (slot == 45) { openMenu(player, holder.query, holder.page - 1); return; }
        if (slot == 53) { openMenu(player, holder.query, holder.page + 1); return; }
        if (slot >= PAGE_SIZE) return;

        int index = holder.page * PAGE_SIZE + slot;
        if (index >= holder.view.size()) return;
        Listing l = holder.view.get(index);

        if (!listings.contains(l)) {
            player.sendMessage(msg("Cette annonce n'existe plus.", NamedTextColor.RED));
            openMenu(player, holder.query, holder.page);
            return;
        }
        if (l.seller.equals(player.getUniqueId())) {
            listings.remove(l);
            giveItem(player, l.item);
            saveData();
            player.sendMessage(msg("Annonce annulee, objet recupere.", NamedTextColor.GREEN));
            openMenu(player, holder.query, holder.page);
            return;
        }
        VaultEco e = economy();
        if (e == null) {
            player.sendMessage(msg("Economie indisponible.", NamedTextColor.RED));
            return;
        }
        if (!e.has(player, l.price)) {
            player.sendMessage(msg("Il te faut " + money(l.price) + ".", NamedTextColor.RED));
            return;
        }
        // Au lieu d'acheter directement, on demande confirmation.
        openConfirm(player, l, holder.query, holder.page);
    }

    /** Effectue reellement l'achat (appele apres confirmation). */
    private void doPurchase(Player player, Listing l, String query, int page) {
        if (!listings.contains(l)) {
            player.sendMessage(msg("Cette annonce n'existe plus.", NamedTextColor.RED));
            openMenu(player, query, page);
            return;
        }
        VaultEco e = economy();
        if (e == null) {
            player.sendMessage(msg("Economie indisponible.", NamedTextColor.RED));
            return;
        }
        if (!e.has(player, l.price)) {
            player.sendMessage(msg("Il te faut " + money(l.price) + ".", NamedTextColor.RED));
            openMenu(player, query, page);
            return;
        }
        e.withdraw(player, l.price);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        e.deposit(seller, l.price);
        listings.remove(l);
        giveItem(player, l.item);
        saveData();
        player.sendMessage(msg("Objet achete pour " + money(l.price) + " !", NamedTextColor.GREEN));

        Player onlineSeller = Bukkit.getPlayer(l.seller);
        if (onlineSeller != null) {
            onlineSeller.sendMessage(msg(player.getName() + " a achete ton objet pour " + money(l.price) + ".", NamedTextColor.GREEN));
        }
        openMenu(player, query, page);
    }

    // ---------- Menu de confirmation ----------

    private void openConfirm(Player player, Listing l, String query, int page) {
        ConfirmHolder holder = new ConfirmHolder(l, query, page);
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Confirmer l'achat ?"));
        holder.inventory = inv;

        // Objet a acheter au centre
        ItemStack preview = l.item.clone();
        ItemMeta pm = preview.getItemMeta();
        List<Component> lore = pm.lore() == null ? new ArrayList<>() : new ArrayList<>(pm.lore());
        lore.add(Component.empty());
        lore.add(line("Vendeur : " + l.sellerName, NamedTextColor.GRAY));
        lore.add(line("Prix : " + money(l.price), NamedTextColor.GREEN));
        pm.lore(lore);
        preview.setItemMeta(pm);
        inv.setItem(13, preview);

        // Confirmer (vert) a gauche, annuler (rouge) a droite
        ItemStack yes = new ItemStack(Material.LIME_WOOL);
        ItemMeta ym = yes.getItemMeta();
        ym.displayName(line("CONFIRMER l'achat (" + money(l.price) + ")", NamedTextColor.GREEN));
        yes.setItemMeta(ym);
        for (int s : new int[]{10, 11}) inv.setItem(s, yes);

        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta nm = no.getItemMeta();
        nm.displayName(line("ANNULER", NamedTextColor.RED));
        no.setItemMeta(nm);
        for (int s : new int[]{15, 16}) inv.setItem(s, no);

        player.openInventory(inv);
    }

    @EventHandler
    public void onConfirmClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfirmHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= holder.inventory.getSize()) return;

        if (raw == 10 || raw == 11) { // CONFIRMER
            doPurchase(player, holder.listing, holder.query, holder.page);
        } else if (raw == 15 || raw == 16) { // ANNULER
            player.sendMessage(msg("Achat annule.", NamedTextColor.YELLOW));
            openMenu(player, holder.query, holder.page);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionHolder
                || event.getInventory().getHolder() instanceof ConfirmHolder) {
            event.setCancelled(true);
        }
    }

    // ---------- Auto-completion ----------

    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent event) {
        String buffer = event.getBuffer();
        if (buffer.startsWith("/")) buffer = buffer.substring(1);
        if (!buffer.toLowerCase().startsWith("ah")) return;

        String[] parts = buffer.split(" ", -1);
        if (parts.length == 2) {
            String partial = parts[1].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String s : List.of("sell", "list", "cancel")) {
                if (s.startsWith(partial)) matches.add(s);
            }
            if (!matches.isEmpty()) event.setCompletions(matches);
        } else if (parts.length == 3 && parts[1].equalsIgnoreCase("sell")) {
            event.setCompletions(List.of("100", "1000", "10k", "100k", "1m"));
        } else if (parts.length == 3 && parts[1].equalsIgnoreCase("cancel")
                && event.getSender() instanceof Player p) {
            int n = myListings(p).size();
            List<String> nums = new ArrayList<>();
            for (int i = 1; i <= n; i++) nums.add(String.valueOf(i));
            if (!nums.isEmpty()) event.setCompletions(nums);
        }
    }

    // ---------- Utilitaires ----------

    private double parsePrice(String s) throws NumberFormatException {
        s = s.trim().toLowerCase().replace(",", ".");
        if (s.isEmpty()) throw new NumberFormatException("vide");
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> { mult = 1_000d;         s = s.substring(0, s.length() - 1); }
            case 'm' -> { mult = 1_000_000d;     s = s.substring(0, s.length() - 1); }
            case 'b' -> { mult = 1_000_000_000d; s = s.substring(0, s.length() - 1); }
        }
        return Double.parseDouble(s) * mult;
    }

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
        for (Listing l : new ArrayList<>(listings)) {
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

    private void giveItem(Player p, ItemStack item) {
        boolean dropped = false;
        for (ItemStack leftover : p.getInventory().addItem(item.clone()).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
            dropped = true;
        }
        if (dropped) {
            p.sendMessage(msg("Inventaire plein : l'objet est tombe au sol a tes pieds.", NamedTextColor.YELLOW));
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

    // ---------- Persistance (sauvegarde sur disque) ----------

    private void setupDataFile() {
        File folder;
        try {
            // getHost() renvoie le plugin hote : on utilise son dossier de donnees.
            folder = getHost().getDataFolder();
        } catch (Throwable t) {
            folder = new File("plugins" + File.separator + "AuctionHouse");
        }
        if (!folder.exists()) folder.mkdirs();
        dataFile = new File(folder, "listings.yml");
    }

    private void loadData() {
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = cfg.getConfigurationSection("listings");
        if (sec == null) return;
        int loaded = 0;
        for (String key : sec.getKeys(false)) {
            String base = "listings." + key;
            try {
                UUID seller = UUID.fromString(cfg.getString(base + ".seller"));
                String name = cfg.getString(base + ".sellerName", "?");
                double price = cfg.getDouble(base + ".price");
                ItemStack item = cfg.getItemStack(base + ".item");
                if (item != null) {
                    listings.add(new Listing(seller, name, item, price));
                    loaded++;
                }
            } catch (Exception ignored) {}
        }
        getLogger().info("AuctionHouse : " + loaded + " annonce(s) chargee(s).");
    }

    private void saveData() {
        if (dataFile == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        int i = 0;
        for (Listing l : listings) {
            String base = "listings." + i;
            cfg.set(base + ".seller", l.seller.toString());
            cfg.set(base + ".sellerName", l.sellerName);
            cfg.set(base + ".price", l.price);
            cfg.set(base + ".item", l.item);
            i++;
        }
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("AuctionHouse : echec de sauvegarde des annonces : " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        saveData();
        getLogger().info("AuctionHouse desactive (annonces sauvegardees).");
    }

    // ---------- Types internes ----------

    private static final class Listing {
        final UUID seller;
        final String sellerName;
        final ItemStack item;
        final double price;
        Listing(UUID seller, String sellerName, ItemStack item, double price) {
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

    private static final class ConfirmHolder implements InventoryHolder {
        final Listing listing;
        final String query;
        final int page;
        Inventory inventory;
        ConfirmHolder(Listing listing, String query, int page) {
            this.listing = listing;
            this.query = query;
            this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    /**
     * Acces a l'economie Vault entierement par reflexion : aucun import de Vault,
     * donc rien a ajouter au classpath de compilation. Vault reste requis au runtime.
     */
    private static final class VaultEco {
        private final Object provider;
        private final Method mFormat, mHas, mWithdraw, mDeposit;

        private VaultEco(Object provider, Method mFormat, Method mHas, Method mWithdraw, Method mDeposit) {
            this.provider = provider;
            this.mFormat = mFormat;
            this.mHas = mHas;
            this.mWithdraw = mWithdraw;
            this.mDeposit = mDeposit;
        }

        static VaultEco hook() {
            try {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                ServicesManager sm = Bukkit.getServicesManager();
                Object rsp = sm.getClass()
                        .getMethod("getRegistration", Class.class)
                        .invoke(sm, economyClass);
                if (rsp == null) return null;
                Object provider = rsp.getClass().getMethod("getProvider").invoke(rsp);
                if (provider == null) return null;

                Method format   = economyClass.getMethod("format", double.class);
                Method has       = economyClass.getMethod("has", OfflinePlayer.class, double.class);
                Method withdraw  = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                Method deposit   = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                return new VaultEco(provider, format, has, withdraw, deposit);
            } catch (Throwable t) {
                return null;
            }
        }

        String format(double amount) {
            try { return (String) mFormat.invoke(provider, amount); }
            catch (Throwable t) { return String.valueOf((long) amount); }
        }

        boolean has(OfflinePlayer p, double amount) {
            try { return (Boolean) mHas.invoke(provider, p, amount); }
            catch (Throwable t) { return false; }
        }

        void withdraw(OfflinePlayer p, double amount) {
            try { mWithdraw.invoke(provider, p, amount); } catch (Throwable ignored) {}
        }

        void deposit(OfflinePlayer p, double amount) {
            try { mDeposit.invoke(provider, p, amount); } catch (Throwable ignored) {}
        }
    }
}

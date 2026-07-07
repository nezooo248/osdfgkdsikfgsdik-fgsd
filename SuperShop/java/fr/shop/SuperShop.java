package fr.shop;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SuperShop v2 : gros menus (double coffre), menu de transaction par objet,
 * /vendre (coffre de vente), tous les blocs du jeu, spawners, economie Vault.
 * A mettre dans : SuperShop/java/fr/shop/SuperShop.java
 *
 * /shop     ouvre le shop
 * /vendre   ouvre un coffre : depose tes objets, ferme -> tu es paye
 * /sell     vend l'objet en main | /sell all vend tout le vendable
 */
public class SuperShop extends LoadedPlugin implements Listener {

    private static final double SELL_RATIO = 0.5;   // vente = 50% de l'achat
    private static final int MAX_AMOUNT = 64;

    private final Map<Material, Double> buyPrices = new EnumMap<>(Material.class);
    private final Map<String, List<Material>> categories = new LinkedHashMap<>();
    private final Map<String, String> categoryNames = new LinkedHashMap<>();
    private final Map<String, Material> categoryIcons = new LinkedHashMap<>();
    private final Set<Material> blacklist = new HashSet<>();

    private double defaultSpawnerPrice = 75000;

    private EconomyHook eco;
    private NamespacedKey keyPrice;
    private NamespacedKey keyTag;

    @Override
    public void onEnable() {
        keyPrice = new NamespacedKey(getHost(), "shop_price");
        keyTag = new NamespacedKey(getHost(), "shop_tag");

        eco = new EconomyHook();
        if (!eco.setup(getServer())) {
            getLogger().warning("Vault + une economie (EssentialsX) requis ! Le shop ne gerera pas l'argent.");
        }

        buildBlacklist();
        buildPrices();
        registerListener(this);

        registerCommand("shop", (sender, cmd, label, args) -> {
            if (sender instanceof Player p) openMain(p);
            else sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
            return true;
        });
        registerCommand("boutique", (sender, cmd, label, args) -> {
            if (sender instanceof Player p) openMain(p);
            else sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
            return true;
        });
        registerCommand("vendre", (sender, cmd, label, args) -> {
            if (sender instanceof Player p) openSellChest(p);
            else sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
            return true;
        });
        registerCommand("sell", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED)); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("all")) sellAll(p);
            else sellHand(p);
            return true;
        });
    }

    // ======================= PRIX =======================

    private void register(String cat, Material m, double price) {
        buyPrices.put(m, price);
        categories.computeIfAbsent(cat, k -> new ArrayList<>()).add(m);
    }

    private void registerBlock(String cat, Material block, Material item, int count) {
        Double ip = buyPrices.get(item);
        if (ip != null) register(cat, block, ip * count);
    }

    private void buildBlacklist() {
        String[] names = {
            "AIR", "CAVE_AIR", "VOID_AIR", "BEDROCK", "BARRIER", "LIGHT",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK",
            "BUDDING_AMETHYST", "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME",
            "SPAWNER", "PETRIFIED_OAK_SLAB", "FARMLAND", "DIRT_PATH",
            "INFESTED_STONE", "INFESTED_COBBLESTONE", "INFESTED_STONE_BRICKS",
            "INFESTED_MOSSY_STONE_BRICKS", "INFESTED_CRACKED_STONE_BRICKS",
            "INFESTED_CHISELED_STONE_BRICKS", "INFESTED_DEEPSLATE"
        };
        for (String n : names) {
            try { blacklist.add(Material.valueOf(n)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void buildPrices() {
        categoryNames.put("minerais", "Minerais & Blocs");   categoryIcons.put("minerais", Material.DIAMOND);
        categoryNames.put("bois", "Bois");                    categoryIcons.put("bois", Material.OAK_LOG);
        categoryNames.put("nourriture", "Nourriture");        categoryIcons.put("nourriture", Material.BREAD);
        categoryNames.put("mobs", "Loot de mobs");            categoryIcons.put("mobs", Material.ROTTEN_FLESH);
        categoryNames.put("redstone", "Redstone");            categoryIcons.put("redstone", Material.REDSTONE);
        categoryNames.put("divers", "Divers");                categoryIcons.put("divers", Material.ENDER_PEARL);
        categoryNames.put("potions", "Potions");              categoryIcons.put("potions", Material.POTION);
        categoryNames.put("tousblocs", "TOUS les blocs");     categoryIcons.put("tousblocs", Material.GRASS_BLOCK);
        categoryNames.put("spawners", "Spawners");            categoryIcons.put("spawners", Material.SPAWNER);

        // --- Minerais / gemmes / lingots (prix eleves) ---
        register("minerais", Material.COAL, 30);
        register("minerais", Material.CHARCOAL, 30);
        register("minerais", Material.RAW_COPPER, 25);
        register("minerais", Material.COPPER_INGOT, 30);
        register("minerais", Material.RAW_IRON, 60);
        register("minerais", Material.IRON_INGOT, 75);
        register("minerais", Material.RAW_GOLD, 140);
        register("minerais", Material.GOLD_INGOT, 150);
        register("minerais", Material.REDSTONE, 35);
        register("minerais", Material.LAPIS_LAZULI, 30);
        register("minerais", Material.QUARTZ, 30);
        register("minerais", Material.DIAMOND, 1500);
        register("minerais", Material.EMERALD, 400);
        register("minerais", Material.AMETHYST_SHARD, 90);
        register("minerais", Material.NETHERITE_SCRAP, 3000);
        register("minerais", Material.NETHERITE_INGOT, 12000);

        registerBlock("minerais", Material.COAL_BLOCK, Material.COAL, 9);
        registerBlock("minerais", Material.RAW_COPPER_BLOCK, Material.RAW_COPPER, 9);
        registerBlock("minerais", Material.COPPER_BLOCK, Material.COPPER_INGOT, 9);
        registerBlock("minerais", Material.RAW_IRON_BLOCK, Material.RAW_IRON, 9);
        registerBlock("minerais", Material.IRON_BLOCK, Material.IRON_INGOT, 9);
        registerBlock("minerais", Material.RAW_GOLD_BLOCK, Material.RAW_GOLD, 9);
        registerBlock("minerais", Material.GOLD_BLOCK, Material.GOLD_INGOT, 9);
        registerBlock("minerais", Material.REDSTONE_BLOCK, Material.REDSTONE, 9);
        registerBlock("minerais", Material.LAPIS_BLOCK, Material.LAPIS_LAZULI, 9);
        registerBlock("minerais", Material.QUARTZ_BLOCK, Material.QUARTZ, 4);
        registerBlock("minerais", Material.DIAMOND_BLOCK, Material.DIAMOND, 9);
        registerBlock("minerais", Material.EMERALD_BLOCK, Material.EMERALD, 9);
        registerBlock("minerais", Material.AMETHYST_BLOCK, Material.AMETHYST_SHARD, 4);
        registerBlock("minerais", Material.NETHERITE_BLOCK, Material.NETHERITE_INGOT, 9);

        // --- Bois ---
        Material[][] woods = {
            {Material.OAK_LOG, Material.OAK_PLANKS}, {Material.SPRUCE_LOG, Material.SPRUCE_PLANKS},
            {Material.BIRCH_LOG, Material.BIRCH_PLANKS}, {Material.JUNGLE_LOG, Material.JUNGLE_PLANKS},
            {Material.ACACIA_LOG, Material.ACACIA_PLANKS}, {Material.DARK_OAK_LOG, Material.DARK_OAK_PLANKS},
            {Material.MANGROVE_LOG, Material.MANGROVE_PLANKS}, {Material.CHERRY_LOG, Material.CHERRY_PLANKS},
        };
        for (Material[] w : woods) { register("bois", w[0], 24); register("bois", w[1], 6); }
        register("bois", Material.CRIMSON_STEM, 24);
        register("bois", Material.WARPED_STEM, 24);
        register("bois", Material.BAMBOO, 4);

        // --- Nourriture ---
        register("nourriture", Material.WHEAT, 8);
        registerBlock("nourriture", Material.HAY_BLOCK, Material.WHEAT, 9);
        register("nourriture", Material.BREAD, 20);
        register("nourriture", Material.APPLE, 30);
        register("nourriture", Material.GOLDEN_APPLE, 1200);
        register("nourriture", Material.CARROT, 8);
        register("nourriture", Material.POTATO, 8);
        register("nourriture", Material.BEETROOT, 8);
        register("nourriture", Material.BEEF, 18);
        register("nourriture", Material.COOKED_BEEF, 28);
        register("nourriture", Material.PORKCHOP, 18);
        register("nourriture", Material.COOKED_PORKCHOP, 28);
        register("nourriture", Material.CHICKEN, 14);
        register("nourriture", Material.COOKED_CHICKEN, 22);
        register("nourriture", Material.MUTTON, 14);
        register("nourriture", Material.SALMON, 14);
        register("nourriture", Material.COD, 12);
        register("nourriture", Material.SUGAR_CANE, 6);
        register("nourriture", Material.EGG, 8);
        register("nourriture", Material.MELON_SLICE, 4);
        register("nourriture", Material.PUMPKIN, 16);
        register("nourriture", Material.SWEET_BERRIES, 8);
        register("nourriture", Material.HONEY_BOTTLE, 30);

        // --- Loot de mobs ---
        register("mobs", Material.ROTTEN_FLESH, 5);
        register("mobs", Material.BONE, 12);
        registerBlock("mobs", Material.BONE_BLOCK, Material.BONE, 9);
        register("mobs", Material.STRING, 10);
        register("mobs", Material.SPIDER_EYE, 12);
        register("mobs", Material.GUNPOWDER, 30);
        register("mobs", Material.SLIME_BALL, 16);
        registerBlock("mobs", Material.SLIME_BLOCK, Material.SLIME_BALL, 9);
        register("mobs", Material.ENDER_PEARL, 90);
        register("mobs", Material.BLAZE_ROD, 60);
        register("mobs", Material.GHAST_TEAR, 220);
        register("mobs", Material.MAGMA_CREAM, 30);
        register("mobs", Material.PHANTOM_MEMBRANE, 45);
        register("mobs", Material.LEATHER, 12);
        register("mobs", Material.FEATHER, 6);
        register("mobs", Material.INK_SAC, 8);
        register("mobs", Material.PRISMARINE_SHARD, 18);
        register("mobs", Material.PRISMARINE_CRYSTALS, 30);
        register("mobs", Material.NETHER_STAR, 45000);
        register("mobs", Material.SHULKER_SHELL, 600);

        // --- Redstone ---
        register("redstone", Material.REDSTONE_TORCH, 45);
        register("redstone", Material.REPEATER, 130);
        register("redstone", Material.COMPARATOR, 190);
        register("redstone", Material.PISTON, 300);
        register("redstone", Material.STICKY_PISTON, 380);
        register("redstone", Material.HOPPER, 450);
        register("redstone", Material.DISPENSER, 300);
        register("redstone", Material.DROPPER, 220);
        register("redstone", Material.OBSERVER, 260);
        register("redstone", Material.LEVER, 30);
        register("redstone", Material.STONE_BUTTON, 15);
        register("redstone", Material.TARGET, 90);

        // --- Divers ---
        register("divers", Material.TORCH, 8);
        register("divers", Material.STICK, 3);
        register("divers", Material.FLINT, 9);
        register("divers", Material.PAPER, 9);
        register("divers", Material.BOOK, 45);
        register("divers", Material.NAME_TAG, 800);
        register("divers", Material.EXPERIENCE_BOTTLE, 150);
        register("divers", Material.ENDER_PEARL, 90);
        register("divers", Material.ENDER_EYE, 180);
        register("divers", Material.BLAZE_POWDER, 35);
        register("divers", Material.GLOWSTONE_DUST, 15);
        register("divers", Material.BOOKSHELF, 180);
        register("divers", Material.ITEM_FRAME, 60);
        register("divers", Material.ARMOR_STAND, 90);
        register("divers", Material.LANTERN, 45);
        register("divers", Material.CHAIN, 60);

        // --- TOUS LES BLOCS (auto) ---
        List<Material> allBlocks = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isBlock() || !m.isItem()) continue;
            if (blacklist.contains(m)) continue;
            if (m.name().endsWith("_SPAWN_EGG")) continue;
            if (m.isAir()) continue;
            if (!buyPrices.containsKey(m)) {
                buyPrices.put(m, fallbackPrice(m));
            }
            allBlocks.add(m);
        }
        allBlocks.sort(Comparator.comparing(Enum::name));
        categories.put("tousblocs", allBlocks);
    }

    /** Prix estime pour un bloc non defini a la main (modifiable ensuite). */
    private double fallbackPrice(Material m) {
        String n = m.name();
        if (n.contains("ORE")) return 350;
        if (n.endsWith("_SLAB")) return 12;
        if (n.endsWith("_STAIRS")) return 32;
        if (n.endsWith("_WALL")) return 22;
        if (n.contains("GLASS")) return 20;
        if (n.contains("WOOL") || n.contains("CARPET")) return 18;
        if (n.contains("CONCRETE")) return 35;
        if (n.contains("TERRACOTTA")) return 28;
        if (n.contains("LOG") || n.contains("WOOD") || n.contains("STEM") || n.contains("PLANKS")) return 18;
        if (n.contains("BRICK")) return 24;
        return 25;
    }

    private double spawnerPrice(EntityType et) {
        switch (et) {
            case BLAZE: return 130000;
            case WITHER_SKELETON: return 300000;
            case ENDERMAN: return 150000;
            case IRON_GOLEM: return 180000;
            case GUARDIAN: case ELDER_GUARDIAN: return 350000;
            case ZOMBIE: case SKELETON: case SPIDER: case CREEPER: return 90000;
            default: return defaultSpawnerPrice;
        }
    }

    private double priceOf(Material m) { return buyPrices.getOrDefault(m, 0.0); }

    // ======================= GUI : MENU PRINCIPAL =======================

    private void openMain(Player p) {
        ShopHolder h = new ShopHolder("MENU");
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("\u25AA Shop \u25AA").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fill(inv, filler());

        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31, 32};
        int i = 0;
        for (String cat : categoryNames.keySet()) {
            if (i >= slots.length) break;
            ItemStack it = named(categoryIcons.getOrDefault(cat, Material.PAPER),
                    categoryNames.get(cat), NamedTextColor.YELLOW,
                    "Clique pour ouvrir");
            tag(it, "CAT:" + cat);
            inv.setItem(slots[i++], it);
        }
        inv.setItem(49, named(Material.EMERALD, "Vendre vos objets (/vendre)", NamedTextColor.GREEN, "Ouvre un coffre de vente"));
        tag(inv.getItem(49), "OPENSELL");
        p.openInventory(inv);
    }

    // ======================= GUI : CATEGORIE =======================

    private void openCategory(Player p, String cat, int page) {
        List<ItemStack> items = new ArrayList<>();
        if (cat.equals("spawners")) {
            for (EntityType et : EntityType.values()) {
                Class<?> cls = et.getEntityClass();
                if (cls == null || !Mob.class.isAssignableFrom(cls)) continue;
                items.add(spawnerDisplay(et));
            }
        } else if (cat.equals("potions")) {
            for (PotionType pt : PotionType.values()) {
                ItemStack disp = potionDisplay(pt);
                if (disp != null) items.add(disp);
            }
        } else {
            for (Material m : categories.getOrDefault(cat, List.of())) items.add(itemDisplay(m));
        }

        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pages - 1));

        ShopHolder h = new ShopHolder("CAT");
        h.category = cat; h.page = page;
        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text(categoryNames.getOrDefault(cat, cat) + "  (" + (page + 1) + "/" + pages + ")")
                        .color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;

        int from = page * perPage, to = Math.min(from + perPage, items.size()), slot = 0;
        for (int i = from; i < to; i++) inv.setItem(slot++, items.get(i));

        for (int s = 45; s < 54; s++) inv.setItem(s, filler());
        if (page > 0) { ItemStack b = named(Material.ARROW, "Precedent", NamedTextColor.AQUA, null); tag(b, "PREV"); inv.setItem(45, b); }
        ItemStack back = named(Material.BARRIER, "Menu", NamedTextColor.RED, null); tag(back, "MENU"); inv.setItem(49, back);
        if (page < pages - 1) { ItemStack b = named(Material.ARROW, "Suivant", NamedTextColor.AQUA, null); tag(b, "NEXT"); inv.setItem(53, b); }

        p.openInventory(inv);
    }

    // ======================= GUI : TRANSACTION (1 objet) =======================

    private void openItem(Player p, Material mat, String mob, String potion, String backCat, int backPage) {
        ShopHolder h = new ShopHolder("ITEM");
        h.material = mat; h.mob = mob; h.potion = potion; h.category = backCat; h.page = backPage;
        if (potion != null) h.unit = potionPrice(PotionType.valueOf(potion));
        else if (mob != null) h.unit = spawnerPrice(EntityType.valueOf(mob));
        else h.unit = priceOf(mat);
        h.amount = 1;
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Transaction").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        renderItem(p, h);
        p.openInventory(inv);
    }

    private void renderItem(Player p, ShopHolder h) {
        Inventory inv = h.inv;
        fill(inv, filler());

        // objet au centre
        ItemStack center = (h.potion != null) ? potionItem(PotionType.valueOf(h.potion))
                : (h.mob != null) ? spawnerItem(EntityType.valueOf(h.mob))
                : new ItemStack(h.material, Math.max(1, h.amount));
        ItemMeta cm = center.getItemMeta();
        double total = h.unit * h.amount;
        List<Component> lore = new ArrayList<>();
        lore.add(line("Quantite : " + h.amount, NamedTextColor.WHITE));
        lore.add(line("Prix achat : " + money(total) + "$", NamedTextColor.GREEN));
        if (h.mob == null && h.potion == null) lore.add(line("Prix vente : " + money(total * SELL_RATIO) + "$", NamedTextColor.GOLD));
        cm.lore(lore);
        center.setItemMeta(cm);
        inv.setItem(13, center);

        // boutons - a gauche
        inv.setItem(29, amountBtn(Material.RED_STAINED_GLASS_PANE, "-1", "AMT:-1"));
        inv.setItem(20, amountBtn(Material.RED_STAINED_GLASS_PANE, "-16", "AMT:-16"));
        inv.setItem(11, amountBtn(Material.RED_STAINED_GLASS_PANE, "-64", "AMT:-64"));
        // boutons + a droite
        inv.setItem(33, amountBtn(Material.LIME_STAINED_GLASS_PANE, "+1", "AMT:+1"));
        inv.setItem(24, amountBtn(Material.LIME_STAINED_GLASS_PANE, "+16", "AMT:+16"));
        inv.setItem(15, amountBtn(Material.LIME_STAINED_GLASS_PANE, "+64", "AMT:+64"));

        // acheter / vendre
        ItemStack buy = named(Material.EMERALD_BLOCK, "ACHETER  (" + money(total) + "$)", NamedTextColor.GREEN, "Clique pour acheter");
        tag(buy, "BUY"); inv.setItem(48, buy);
        if (h.mob == null && h.potion == null) {
            ItemStack sell = named(Material.GOLD_BLOCK, "VENDRE  (" + money(total * SELL_RATIO) + "$)", NamedTextColor.GOLD, "Vend depuis ton inventaire");
            tag(sell, "SELL"); inv.setItem(50, sell);
        }
        ItemStack back = named(Material.BARRIER, "Retour", NamedTextColor.RED, null); tag(back, "BACK"); inv.setItem(45, back);

        double bal = eco.ready() ? eco.balance(p) : 0;
        inv.setItem(4, named(Material.SUNFLOWER, "Ton argent : " + money(bal) + "$", NamedTextColor.YELLOW, null));
    }

    // ======================= GUI : COFFRE DE VENTE (/vendre) =======================

    private void openSellChest(Player p) {
        ShopHolder h = new ShopHolder("SELL");
        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text("Vendre : depose puis ferme").color(NamedTextColor.GOLD));
        h.inv = inv;
        p.openInventory(inv);
    }

    private void processSellChest(Player p, Inventory inv) {
        boolean ecoOk = eco.ready();
        double total = 0;
        int sold = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            inv.setItem(i, null);
            if (ecoOk && sellable(it.getType())) {
                total += priceOf(it.getType()) * SELL_RATIO * it.getAmount();
                sold += it.getAmount();
            } else {
                // non vendable (ou economie indispo) : on rend l'objet
                for (ItemStack rest : p.getInventory().addItem(it).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        if (!ecoOk) {
            p.sendMessage(Component.text("Economie indisponible : objets rendus.").color(NamedTextColor.RED));
            return;
        }
        if (sold > 0) {
            eco.deposit(p, total);
            p.sendMessage(Component.text("Vendu " + sold + " objets pour " + money(total) + "$ (meme prix qu'en boutique)").color(NamedTextColor.GOLD));
        } else {
            p.sendMessage(Component.text("Rien de vendable : objets rendus.").color(NamedTextColor.YELLOW));
        }
    }

    // ======================= EVENEMENTS =======================

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder h && !"SELL".equals(h.type))
            e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ShopHolder h)) return;
        if ("SELL".equals(h.type)) return; // coffre de vente : interactions libres

        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof ShopHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        String t = clicked.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
        if (t == null) return;

        switch (h.type) {
            case "MENU" -> {
                if (t.startsWith("CAT:")) openCategory(p, t.substring(4), 0);
                else if (t.equals("OPENSELL")) openSellChest(p);
            }
            case "CAT" -> {
                switch (t) {
                    case "PREV" -> openCategory(p, h.category, h.page - 1);
                    case "NEXT" -> openCategory(p, h.category, h.page + 1);
                    case "MENU" -> openMain(p);
                    default -> {
                        if (t.startsWith("ITEM:")) openItem(p, Material.valueOf(t.substring(5)), null, null, h.category, h.page);
                        else if (t.startsWith("SPAWN:")) openItem(p, Material.SPAWNER, t.substring(6), null, h.category, h.page);
                        else if (t.startsWith("POTION:")) openItem(p, Material.POTION, null, t.substring(7), h.category, h.page);
                    }
                }
            }
            case "ITEM" -> handleItemMenu(p, h, t);
        }
    }

    private void handleItemMenu(Player p, ShopHolder h, String t) {
        if (t.startsWith("AMT:")) {
            int delta = Integer.parseInt(t.substring(4));
            h.amount = Math.max(1, Math.min(MAX_AMOUNT, h.amount + delta));
            renderItem(p, h);
            p.updateInventory();
        } else if (t.equals("BUY")) {
            buy(p, h);
        } else if (t.equals("SELL")) {
            sellAmount(p, h.material, h.amount);
        } else if (t.equals("BACK")) {
            openCategory(p, h.category, h.page);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder h && "SELL".equals(h.type)
                && e.getPlayer() instanceof Player p) {
            processSellChest(p, e.getInventory());
        }
    }

    // ======================= ACHAT / VENTE =======================

    private void buy(Player p, ShopHolder h) {
        if (!eco.ready()) { p.sendMessage(Component.text("Economie indisponible (Vault/EssentialsX).").color(NamedTextColor.RED)); return; }
        double total = h.unit * h.amount;
        if (eco.balance(p) < total) { p.sendMessage(Component.text("Pas assez d'argent (" + money(total) + "$).").color(NamedTextColor.RED)); return; }
        if (!eco.withdraw(p, total)) { p.sendMessage(Component.text("Transaction refusee.").color(NamedTextColor.RED)); return; }

        if (h.potion != null) {
            ItemStack pot = potionItem(PotionType.valueOf(h.potion));
            for (int i = 0; i < h.amount; i++)
                for (ItemStack rest : p.getInventory().addItem(pot.clone()).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
        } else {
            ItemStack give = (h.mob != null) ? spawnerItem(EntityType.valueOf(h.mob)) : new ItemStack(h.material, h.amount);
            for (ItemStack rest : p.getInventory().addItem(give).values())
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }

        p.sendMessage(Component.text("Achete x" + h.amount + " pour " + money(total) + "$").color(NamedTextColor.GREEN));
        renderItem(p, h);
        p.updateInventory();
    }

    private void sellAmount(Player p, Material mat, int wanted) {
        if (!sellable(mat)) { p.sendMessage(Component.text("Non vendable.").color(NamedTextColor.RED)); return; }
        int have = count(p, mat);
        int amount = Math.min(wanted, have);
        if (amount <= 0) { p.sendMessage(Component.text("Tu n'as pas cet objet.").color(NamedTextColor.RED)); return; }
        remove(p, mat, amount);
        double total = priceOf(mat) * SELL_RATIO * amount;
        eco.deposit(p, total);
        p.sendMessage(Component.text("Vendu x" + amount + " pour " + money(total) + "$").color(NamedTextColor.GOLD));
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || !sellable(hand.getType())) {
            p.sendMessage(Component.text("Objet non vendable en main.").color(NamedTextColor.RED)); return;
        }
        int amount = hand.getAmount();
        double total = priceOf(hand.getType()) * SELL_RATIO * amount;
        p.getInventory().setItemInMainHand(null);
        eco.deposit(p, total);
        p.sendMessage(Component.text("Vendu x" + amount + " pour " + money(total) + "$").color(NamedTextColor.GOLD));
    }

    private void sellAll(Player p) {
        double total = 0; int count = 0;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || !sellable(it.getType())) continue;
            total += priceOf(it.getType()) * SELL_RATIO * it.getAmount();
            count += it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (count == 0) { p.sendMessage(Component.text("Rien de vendable.").color(NamedTextColor.RED)); return; }
        eco.deposit(p, total);
        p.sendMessage(Component.text("Vendu " + count + " objets pour " + money(total) + "$").color(NamedTextColor.GOLD));
    }

    private boolean sellable(Material m) {
        return m != Material.SPAWNER && !m.name().endsWith("_SPAWN_EGG") && buyPrices.containsKey(m);
    }

    private int count(Player p, Material m) {
        int c = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == m) c += it.getAmount();
        return c;
    }

    private void remove(Player p, Material m, int amount) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - take);
            amount -= take;
            if (it.getAmount() <= 0) p.getInventory().setItem(i, null);
        }
    }

    // ======================= AFFICHAGE / UTILS =======================

    private ItemStack itemDisplay(Material m) {
        double buy = priceOf(m);
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(line("Achat : " + money(buy) + "$", NamedTextColor.GREEN));
        lore.add(line("Vente : " + money(buy * SELL_RATIO) + "$", NamedTextColor.GOLD));
        lore.add(line("Clique pour choisir la quantite", NamedTextColor.GRAY));
        meta.lore(lore);
        it.setItemMeta(meta);
        tag(it, "ITEM:" + m.name());
        return it;
    }

    private ItemStack spawnerDisplay(EntityType et) {
        ItemStack it = spawnerItem(et);
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(line("Achat : " + money(spawnerPrice(et)) + "$", NamedTextColor.GREEN));
        lore.add(line("Clique pour acheter", NamedTextColor.GRAY));
        meta.lore(lore);
        it.setItemMeta(meta);
        tag(it, "SPAWN:" + et.name());
        return it;
    }

    private ItemStack spawnerItem(EntityType et) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(et);
            bsm.setBlockState(cs);
        }
        meta.displayName(line("Spawner " + pretty(et.name()), NamedTextColor.LIGHT_PURPLE));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack potionItem(PotionType pt) {
        ItemStack it = new ItemStack(Material.POTION);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof PotionMeta pm) {
            try { pm.setBasePotionType(pt); } catch (Throwable ignored) {}
        }
        meta.displayName(line("Potion " + pretty(pt.name()), NamedTextColor.LIGHT_PURPLE));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack potionDisplay(PotionType pt) {
        try {
            ItemStack it = potionItem(pt);
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(line("Achat : " + money(potionPrice(pt)) + "$", NamedTextColor.GREEN));
            lore.add(line("Clique pour acheter", NamedTextColor.GRAY));
            meta.lore(lore);
            it.setItemMeta(meta);
            tag(it, "POTION:" + pt.name());
            return it;
        } catch (Throwable t) {
            return null;
        }
    }

    private double potionPrice(PotionType pt) {
        String n = pt.name();
        if (n.equals("WATER") || n.equals("MUNDANE") || n.equals("THICK") || n.equals("AWKWARD")) return 100;
        double base = 350;
        if (n.startsWith("STRONG_")) base += 200;
        if (n.startsWith("LONG_")) base += 100;
        return base;
    }

    private ItemStack named(Material mat, String name, NamedTextColor color, String loreLine) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(name, color));
        if (loreLine != null) meta.lore(List.of(line(loreLine, NamedTextColor.GRAY)));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack amountBtn(Material mat, String name, String tag) {
        ItemStack it = named(mat, name, NamedTextColor.WHITE, null);
        tag(it, tag);
        return it;
    }

    private ItemStack filler() {
        return named(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, null);
    }

    private void fill(Inventory inv, ItemStack f) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
    }

    private void tag(ItemStack it, String value) {
        if (it == null) return;
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, value);
        it.setItemMeta(meta);
    }

    private Component line(String s, NamedTextColor c) {
        return Component.text(s).color(c).decoration(TextDecoration.ITALIC, false);
    }

    private String money(double v) {
        return String.format("%,d", Math.round(v)).replace(',', ' ');
    }

    private String pretty(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder h && "SELL".equals(h.type))
                p.closeInventory();
        }
        getLogger().info("SuperShop desactive.");
    }

    // ======================= CLASSES INTERNES =======================

    static class ShopHolder implements InventoryHolder {
        final String type;      // MENU, CAT, ITEM, SELL
        String category;
        int page;
        Material material;
        String mob;
        String potion;
        double unit;
        int amount = 1;
        Inventory inv;
        ShopHolder(String type) { this.type = type; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class EconomyHook {
        private Object economy;
        private Method mBal, mWit, mDep;

        boolean setup(Server server) {
            for (Class<?> svc : server.getServicesManager().getKnownServices()) {
                if (svc.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    RegisteredServiceProvider<?> rsp = server.getServicesManager().getRegistration(svc);
                    if (rsp != null) {
                        economy = rsp.getProvider();
                        try {
                            Class<?> ec = economy.getClass();
                            mBal = ec.getMethod("getBalance", OfflinePlayer.class);
                            mWit = ec.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                            mDep = ec.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                        } catch (Exception e) { economy = null; }
                    }
                }
            }
            return ready();
        }

        boolean ready() { return economy != null && mBal != null && mWit != null && mDep != null; }

        double balance(OfflinePlayer p) {
            try { return ((Number) mBal.invoke(economy, p)).doubleValue(); } catch (Exception e) { return 0; }
        }
        boolean withdraw(OfflinePlayer p, double a) {
            try { return success(mWit.invoke(economy, p, a)); } catch (Exception e) { return false; }
        }
        boolean deposit(OfflinePlayer p, double a) {
            try { return success(mDep.invoke(economy, p, a)); } catch (Exception e) { return false; }
        }
        private boolean success(Object r) {
            try { Object b = r.getClass().getMethod("transactionSuccess").invoke(r); return !(b instanceof Boolean) || (Boolean) b; }
            catch (Exception e) { return true; }
        }
    }
}

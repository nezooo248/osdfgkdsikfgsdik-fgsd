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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SuperShop : shop GUI + /sell, economie via Vault (EssentialsX).
 * A mettre dans : SuperShop/java/fr/shop/SuperShop.java
 *
 * Clic gauche = acheter 1 | clic droit = acheter 32 | shift+gauche = acheter 64
 * Prix coherents : un bloc vaut toujours 9x l'objet (4x pour le quartz), en achat ET en vente.
 * /sell        vend l'objet tenu en main
 * /sell all    vend tout ce qui est vendable dans l'inventaire
 */
public class SuperShop extends LoadedPlugin implements Listener {

    private static final double SELL_RATIO = 0.5; // vente = 50% du prix d'achat (modifiable)

    private final Map<Material, Double> buyPrices = new EnumMap<>(Material.class);
    private final Map<String, List<Material>> categories = new LinkedHashMap<>();
    private final Map<String, String> categoryNames = new LinkedHashMap<>();
    private final Map<String, Material> categoryIcons = new LinkedHashMap<>();

    private double defaultSpawnerPrice = 50000;

    private EconomyHook eco;
    private NamespacedKey keyPrice;
    private NamespacedKey keyMob;

    @Override
    public void onEnable() {
        keyPrice = new NamespacedKey(getHost(), "shop_price");
        keyMob = new NamespacedKey(getHost(), "shop_mob");

        eco = new EconomyHook();
        if (!eco.setup(getServer())) {
            getLogger().warning("Vault + une economie (EssentialsX) sont requis ! Le shop ne pourra pas gerer l'argent.");
        }

        buildPrices();
        registerListener(this);

        registerCommand("shop", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
                return true;
            }
            openMain(p);
            return true;
        });

        registerCommand("sell", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
                sellAll(p);
            } else {
                sellHand(p);
            }
            return true;
        });
    }

    // ======================= PRIX =======================

    private void register(String cat, Material m, double price) {
        buyPrices.put(m, price);
        categories.computeIfAbsent(cat, k -> new ArrayList<>()).add(m);
    }

    /** Un bloc compresse = count x le prix de l'objet (coherence achat/vente garantie). */
    private void registerBlock(String cat, Material block, Material item, int count) {
        Double ip = buyPrices.get(item);
        if (ip != null) register(cat, block, ip * count);
    }

    private void buildPrices() {
        categoryNames.put("minerais", "Minerais & Blocs");   categoryIcons.put("minerais", Material.DIAMOND);
        categoryNames.put("bois", "Bois");                    categoryIcons.put("bois", Material.OAK_LOG);
        categoryNames.put("nourriture", "Nourriture");        categoryIcons.put("nourriture", Material.BREAD);
        categoryNames.put("mobs", "Loot de mobs");            categoryIcons.put("mobs", Material.ROTTEN_FLESH);
        categoryNames.put("construction", "Construction");    categoryIcons.put("construction", Material.STONE);
        categoryNames.put("redstone", "Redstone");            categoryIcons.put("redstone", Material.REDSTONE);
        categoryNames.put("divers", "Divers");                categoryIcons.put("divers", Material.ENDER_PEARL);
        categoryNames.put("spawners", "Spawners");            categoryIcons.put("spawners", Material.SPAWNER);

        // --- Minerais / gemmes / lingots (prix unitaires, economie moyenne) ---
        register("minerais", Material.COAL, 20);
        register("minerais", Material.CHARCOAL, 20);
        register("minerais", Material.RAW_COPPER, 15);
        register("minerais", Material.COPPER_INGOT, 20);
        register("minerais", Material.RAW_IRON, 40);
        register("minerais", Material.IRON_INGOT, 50);
        register("minerais", Material.RAW_GOLD, 90);
        register("minerais", Material.GOLD_INGOT, 100);
        register("minerais", Material.REDSTONE, 25);
        register("minerais", Material.LAPIS_LAZULI, 20);
        register("minerais", Material.QUARTZ, 20);
        register("minerais", Material.DIAMOND, 1000);
        register("minerais", Material.EMERALD, 250);
        register("minerais", Material.AMETHYST_SHARD, 60);
        register("minerais", Material.NETHERITE_SCRAP, 2000);
        register("minerais", Material.NETHERITE_INGOT, 8000);

        // --- Blocs compresses (9x, sauf quartz 4x) ---
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

        // --- Bois (1 buche = 4 planches) ---
        Material[][] woods = {
            {Material.OAK_LOG, Material.OAK_PLANKS},
            {Material.SPRUCE_LOG, Material.SPRUCE_PLANKS},
            {Material.BIRCH_LOG, Material.BIRCH_PLANKS},
            {Material.JUNGLE_LOG, Material.JUNGLE_PLANKS},
            {Material.ACACIA_LOG, Material.ACACIA_PLANKS},
            {Material.DARK_OAK_LOG, Material.DARK_OAK_PLANKS},
            {Material.MANGROVE_LOG, Material.MANGROVE_PLANKS},
            {Material.CHERRY_LOG, Material.CHERRY_PLANKS},
        };
        for (Material[] w : woods) {
            register("bois", w[0], 16);
            register("bois", w[1], 4);
        }
        register("bois", Material.CRIMSON_STEM, 16);
        register("bois", Material.WARPED_STEM, 16);
        register("bois", Material.BAMBOO, 3);

        // --- Nourriture ---
        register("nourriture", Material.WHEAT, 5);
        registerBlock("nourriture", Material.HAY_BLOCK, Material.WHEAT, 9);
        register("nourriture", Material.BREAD, 15);
        register("nourriture", Material.APPLE, 20);
        register("nourriture", Material.GOLDEN_APPLE, 800);
        register("nourriture", Material.CARROT, 5);
        register("nourriture", Material.POTATO, 5);
        register("nourriture", Material.BAKED_POTATO, 8);
        register("nourriture", Material.BEETROOT, 5);
        register("nourriture", Material.BEEF, 12);
        register("nourriture", Material.COOKED_BEEF, 20);
        register("nourriture", Material.PORKCHOP, 12);
        register("nourriture", Material.COOKED_PORKCHOP, 20);
        register("nourriture", Material.CHICKEN, 10);
        register("nourriture", Material.COOKED_CHICKEN, 16);
        register("nourriture", Material.MUTTON, 10);
        register("nourriture", Material.SALMON, 10);
        register("nourriture", Material.COD, 8);
        register("nourriture", Material.SUGAR_CANE, 4);
        register("nourriture", Material.SUGAR, 5);
        register("nourriture", Material.EGG, 6);
        register("nourriture", Material.MELON_SLICE, 3);
        register("nourriture", Material.PUMPKIN, 12);
        register("nourriture", Material.SWEET_BERRIES, 5);
        register("nourriture", Material.HONEY_BOTTLE, 20);

        // --- Loot de mobs ---
        register("mobs", Material.ROTTEN_FLESH, 3);
        register("mobs", Material.BONE, 8);
        registerBlock("mobs", Material.BONE_BLOCK, Material.BONE, 9);
        register("mobs", Material.STRING, 6);
        register("mobs", Material.SPIDER_EYE, 8);
        register("mobs", Material.GUNPOWDER, 20);
        register("mobs", Material.SLIME_BALL, 10);
        registerBlock("mobs", Material.SLIME_BLOCK, Material.SLIME_BALL, 9);
        register("mobs", Material.ENDER_PEARL, 60);
        register("mobs", Material.BLAZE_ROD, 40);
        register("mobs", Material.GHAST_TEAR, 150);
        register("mobs", Material.MAGMA_CREAM, 20);
        register("mobs", Material.PHANTOM_MEMBRANE, 30);
        register("mobs", Material.LEATHER, 8);
        register("mobs", Material.FEATHER, 4);
        register("mobs", Material.INK_SAC, 6);
        register("mobs", Material.PRISMARINE_SHARD, 12);
        register("mobs", Material.PRISMARINE_CRYSTALS, 20);
        register("mobs", Material.NETHER_STAR, 30000);
        register("mobs", Material.WITHER_SKELETON_SKULL, 2000);
        register("mobs", Material.SHULKER_SHELL, 400);

        // --- Construction ---
        register("construction", Material.DIRT, 1);
        register("construction", Material.COBBLESTONE, 2);
        register("construction", Material.STONE, 3);
        register("construction", Material.GRANITE, 3);
        register("construction", Material.DIORITE, 3);
        register("construction", Material.ANDESITE, 3);
        register("construction", Material.DEEPSLATE, 3);
        register("construction", Material.SAND, 3);
        register("construction", Material.RED_SAND, 3);
        register("construction", Material.GRAVEL, 3);
        register("construction", Material.GLASS, 4);
        register("construction", Material.SANDSTONE, 6);
        register("construction", Material.NETHERRACK, 2);
        register("construction", Material.OBSIDIAN, 50);
        register("construction", Material.CRYING_OBSIDIAN, 120);
        register("construction", Material.CLAY_BALL, 4);
        register("construction", Material.BRICK, 8);
        register("construction", Material.TERRACOTTA, 6);
        register("construction", Material.ICE, 4);
        register("construction", Material.PACKED_ICE, 20);
        register("construction", Material.MOSS_BLOCK, 15);
        register("construction", Material.CALCITE, 5);
        register("construction", Material.TUFF, 3);

        // --- Redstone ---
        register("redstone", Material.REDSTONE_TORCH, 30);
        register("redstone", Material.REPEATER, 90);
        register("redstone", Material.COMPARATOR, 130);
        register("redstone", Material.PISTON, 200);
        register("redstone", Material.STICKY_PISTON, 250);
        register("redstone", Material.HOPPER, 300);
        register("redstone", Material.DISPENSER, 200);
        register("redstone", Material.DROPPER, 150);
        register("redstone", Material.OBSERVER, 180);
        register("redstone", Material.LEVER, 20);
        register("redstone", Material.STONE_BUTTON, 10);
        register("redstone", Material.TRIPWIRE_HOOK, 20);
        register("redstone", Material.TARGET, 60);
        register("redstone", Material.SLIME_BLOCK, buyPrices.getOrDefault(Material.SLIME_BLOCK, 90.0));

        // --- Divers ---
        register("divers", Material.TORCH, 5);
        register("divers", Material.STICK, 2);
        register("divers", Material.FLINT, 6);
        register("divers", Material.PAPER, 6);
        register("divers", Material.BOOK, 30);
        register("divers", Material.NAME_TAG, 500);
        register("divers", Material.EXPERIENCE_BOTTLE, 100);
        register("divers", Material.ENDER_EYE, 120);
        register("divers", Material.BLAZE_POWDER, 25);
        register("divers", Material.GLOWSTONE_DUST, 10);
        register("divers", Material.GLOWSTONE, 40);
        register("divers", Material.NETHER_BRICK, 8);
        register("divers", Material.BOOKSHELF, 120);
        register("divers", Material.ITEM_FRAME, 40);
        register("divers", Material.ARMOR_STAND, 60);
        register("divers", Material.LANTERN, 30);
        register("divers", Material.CHAIN, 40);

        // --- Prix spawners specifiques (les autres = defaut) ---
        // (les cles sont des EntityType, geres a l'ouverture de la categorie)
    }

    private double spawnerPrice(EntityType et) {
        switch (et) {
            case BLAZE: return 100000;
            case WITHER_SKELETON: return 250000;
            case ENDERMAN: return 120000;
            case IRON_GOLEM: return 150000;
            case GUARDIAN: case ELDER_GUARDIAN: return 300000;
            case ZOMBIE: case SKELETON: case SPIDER: case CREEPER: return 75000;
            default: return defaultSpawnerPrice;
        }
    }

    // ======================= GUI =======================

    private void openMain(Player p) {
        ShopHolder holder = new ShopHolder("MENU", 0);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Shop").color(NamedTextColor.DARK_PURPLE));
        holder.inv = inv;

        int slot = 10;
        for (String cat : categoryNames.keySet()) {
            Material icon = categoryIcons.getOrDefault(cat, Material.PAPER);
            ItemStack it = new ItemStack(icon);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.text(categoryNames.get(cat))
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Clique pour ouvrir")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(keyMob, PersistentDataType.STRING, "CAT:" + cat);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
            slot++;
            if (slot == 17) slot = 19;
        }
        p.openInventory(inv);
    }

    private void openCategory(Player p, String cat, int page) {
        List<ItemStack> items = new ArrayList<>();

        if (cat.equals("spawners")) {
            for (EntityType et : EntityType.values()) {
                Class<?> cls = et.getEntityClass();
                if (cls == null || !Mob.class.isAssignableFrom(cls)) continue;
                items.add(buildSpawnerDisplay(et));
            }
        } else {
            List<Material> mats = categories.getOrDefault(cat, List.of());
            for (Material m : mats) {
                items.add(buildItemDisplay(m));
            }
        }

        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pages - 1));

        ShopHolder holder = new ShopHolder(cat, page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text(categoryNames.getOrDefault(cat, cat)).color(NamedTextColor.DARK_PURPLE));
        holder.inv = inv;

        int from = page * perPage;
        int to = Math.min(from + perPage, items.size());
        int slot = 0;
        for (int i = from; i < to; i++) {
            inv.setItem(slot++, items.get(i));
        }

        inv.setItem(45, navButton(Material.ARROW, "Precedent", page > 0 ? "PREV" : null));
        inv.setItem(49, navButton(Material.BARRIER, "Retour au menu", "MENU"));
        inv.setItem(53, navButton(Material.ARROW, "Suivant", page < pages - 1 ? "NEXT" : null));

        p.openInventory(inv);
    }

    private ItemStack navButton(Material mat, String name, String action) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        if (action != null) {
            meta.getPersistentDataContainer().set(keyMob, PersistentDataType.STRING, "NAV:" + action);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildItemDisplay(Material m) {
        double buy = buyPrices.getOrDefault(m, 0.0);
        double sell = buy * SELL_RATIO;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.lore(priceLore(buy, sell));
        meta.getPersistentDataContainer().set(keyPrice, PersistentDataType.DOUBLE, buy);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildSpawnerDisplay(EntityType et) {
        double buy = spawnerPrice(et);
        ItemStack it = new ItemStack(Material.SPAWNER);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(et);
            bsm.setBlockState(cs);
        }
        meta.displayName(Component.text("Spawner " + pretty(et.name()))
                .color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Achat : " + money(buy) + "$").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Clic gauche pour acheter").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(keyPrice, PersistentDataType.DOUBLE, buy);
        meta.getPersistentDataContainer().set(keyMob, PersistentDataType.STRING, "SPAWN:" + et.name());
        it.setItemMeta(meta);
        return it;
    }

    private List<Component> priceLore(double buy, double sell) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Achat : " + money(buy) + "$").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Vente : " + money(sell) + "$").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Gauche = 1  |  Droit = 32  |  Shift = 64").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    // ======================= CLICS =======================

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder topHolder = e.getView().getTopInventory().getHolder();
        if (!(topHolder instanceof ShopHolder holder)) return;

        e.setCancelled(true); // empeche de voler les items du shop

        if (e.getClickedInventory() == null
                || !(e.getClickedInventory().getHolder() instanceof ShopHolder)) {
            return; // clic dans son propre inventaire
        }
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String tag = pdc.get(keyMob, PersistentDataType.STRING);

        // Menu principal : ouverture d'une categorie
        if ("MENU".equals(holder.category)) {
            if (tag != null && tag.startsWith("CAT:")) {
                openCategory(p, tag.substring(4), 0);
            }
            return;
        }

        // Navigation
        if (tag != null && tag.startsWith("NAV:")) {
            String action = tag.substring(4);
            switch (action) {
                case "PREV" -> openCategory(p, holder.category, holder.page - 1);
                case "NEXT" -> openCategory(p, holder.category, holder.page + 1);
                case "MENU" -> openMain(p);
            }
            return;
        }

        // Achat
        Double unit = pdc.get(keyPrice, PersistentDataType.DOUBLE);
        if (unit == null) return;

        int amount;
        if (e.isShiftClick() && e.isLeftClick()) amount = 64;
        else if (e.isRightClick()) amount = 32;
        else amount = 1;

        // les spawners s'achetent 1 par 1
        if (clicked.getType() == Material.SPAWNER) amount = 1;

        buy(p, clicked, unit, amount, tag);
    }

    // ======================= ACHAT / VENTE =======================

    private void buy(Player p, ItemStack display, double unit, int amount, String tag) {
        if (!eco.ready()) {
            p.sendMessage(Component.text("Economie indisponible (Vault/EssentialsX manquant).").color(NamedTextColor.RED));
            return;
        }
        double total = unit * amount;
        if (eco.balance(p) < total) {
            p.sendMessage(Component.text("Pas assez d'argent ! Il te faut " + money(total) + "$").color(NamedTextColor.RED));
            return;
        }
        if (!eco.withdraw(p, total)) {
            p.sendMessage(Component.text("Transaction refusee.").color(NamedTextColor.RED));
            return;
        }

        ItemStack give;
        if (display.getType() == Material.SPAWNER && tag != null && tag.startsWith("SPAWN:")) {
            give = buildSpawnerItem(EntityType.valueOf(tag.substring(6)));
        } else {
            give = new ItemStack(display.getType(), amount);
        }

        Map<Integer, ItemStack> left = p.getInventory().addItem(give);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        p.sendMessage(Component.text("Achete x" + amount + " pour " + money(total) + "$").color(NamedTextColor.GREEN));
    }

    private ItemStack buildSpawnerItem(EntityType et) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(et);
            bsm.setBlockState(cs);
        }
        meta.displayName(Component.text("Spawner " + pretty(et.name()))
                .color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            p.sendMessage(Component.text("Tiens un objet a vendre en main.").color(NamedTextColor.RED));
            return;
        }
        Double unit = buyPrices.get(hand.getType());
        if (unit == null || hand.getType() == Material.SPAWNER) {
            p.sendMessage(Component.text("Cet objet n'est pas vendable.").color(NamedTextColor.RED));
            return;
        }
        int amount = hand.getAmount();
        double total = unit * SELL_RATIO * amount;
        p.getInventory().setItemInMainHand(null);
        eco.deposit(p, total);
        p.sendMessage(Component.text("Vendu x" + amount + " pour " + money(total) + "$").color(NamedTextColor.GOLD));
    }

    private void sellAll(Player p) {
        double total = 0;
        int count = 0;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getType() == Material.SPAWNER) continue;
            Double unit = buyPrices.get(it.getType());
            if (unit == null) continue;
            total += unit * SELL_RATIO * it.getAmount();
            count += it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (count == 0) {
            p.sendMessage(Component.text("Rien de vendable dans ton inventaire.").color(NamedTextColor.RED));
            return;
        }
        eco.deposit(p, total);
        p.sendMessage(Component.text("Vendu " + count + " objets pour " + money(total) + "$").color(NamedTextColor.GOLD));
    }

    // ======================= UTILS =======================

    private String money(double v) {
        long l = Math.round(v);
        return String.format("%,d", l).replace(',', ' ');
    }

    private String pretty(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public void onDisable() {
        getLogger().info("SuperShop desactive.");
    }

    // ======================= CLASSES INTERNES =======================

    /** Marque nos inventaires GUI pour les reconnaitre dans les clics. */
    static class ShopHolder implements InventoryHolder {
        final String category;
        final int page;
        Inventory inv;
        ShopHolder(String category, int page) { this.category = category; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** Acces a l'economie Vault par reflexion (pas besoin de Vault a la compilation). */
    static class EconomyHook {
        private Object economy;
        private Method mGetBalance, mWithdraw, mDeposit;

        boolean setup(Server server) {
            for (Class<?> svc : server.getServicesManager().getKnownServices()) {
                if (svc.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    RegisteredServiceProvider<?> rsp = server.getServicesManager().getRegistration(svc);
                    if (rsp != null) {
                        economy = rsp.getProvider();
                        try {
                            Class<?> ec = economy.getClass();
                            mGetBalance = ec.getMethod("getBalance", OfflinePlayer.class);
                            mWithdraw = ec.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                            mDeposit = ec.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                        } catch (Exception e) {
                            economy = null;
                        }
                    }
                }
            }
            return ready();
        }

        boolean ready() {
            return economy != null && mGetBalance != null && mWithdraw != null && mDeposit != null;
        }

        double balance(OfflinePlayer p) {
            try { return ((Number) mGetBalance.invoke(economy, p)).doubleValue(); }
            catch (Exception e) { return 0; }
        }

        boolean withdraw(OfflinePlayer p, double amt) {
            try { return success(mWithdraw.invoke(economy, p, amt)); }
            catch (Exception e) { return false; }
        }

        boolean deposit(OfflinePlayer p, double amt) {
            try { return success(mDeposit.invoke(economy, p, amt)); }
            catch (Exception e) { return false; }
        }

        private boolean success(Object resp) {
            try {
                Object b = resp.getClass().getMethod("transactionSuccess").invoke(resp);
                return !(b instanceof Boolean) || (Boolean) b;
            } catch (Exception e) { return true; }
        }
    }
}

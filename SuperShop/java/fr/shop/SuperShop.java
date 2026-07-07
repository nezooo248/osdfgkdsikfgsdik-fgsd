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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
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

import java.lang.reflect.Field;
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

    private double defaultSpawnerPrice = 150000;

    private EconomyHook eco;
    private NamespacedKey keyPrice;
    private NamespacedKey keyTag;
    private final Set<String> myCommands = new HashSet<>();

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

        forceRegister("shop", (sender, cmd, label, args) -> {
            if (sender instanceof Player p) openMain(p);
            else sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
            return true;
        });
        forceRegister("vendre", (sender, cmd, label, args) -> {
            if (sender instanceof Player p) openSellChest(p);
            else sender.sendMessage(Component.text("Reserve aux joueurs.").color(NamedTextColor.RED));
            return true;
        });
        syncCommands();
    }

    // ======================= COMMANDES (force + sync client) =======================

    private CommandMap getCommandMap() {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(server);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands(CommandMap map) {
        Class<?> c = map.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Enregistre la commande en forcant le label principal (meme s'il est deja pris). */
    private void forceRegister(String label, CommandExecutor exec) {
        CommandMap map = getCommandMap();
        if (map == null) return;
        Command cmd = new Command(label) {
            @Override
            public boolean execute(CommandSender sender, String lbl, String[] args) {
                return exec.onCommand(sender, this, lbl, args);
            }
        };
        map.register("supershop", cmd);
        Map<String, Command> known = knownCommands(map);
        if (known != null) known.put(label, cmd);
        myCommands.add(label);
    }

    /** Pousse la liste des commandes aux joueurs (plus de rouge, auto-completion active). */
    private void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.updateCommands(); } catch (Throwable ignored) {}
        }
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
            "INFESTED_CHISELED_STONE_BRICKS", "INFESTED_DEEPSLATE",
            // --- non obtenables / creatif uniquement ---
            "TRIAL_SPAWNER", "VAULT", "FROGSPAWN", "DRAGON_EGG",
            "PLAYER_HEAD", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "POWDER_SNOW"
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
        register("minerais", Material.COAL, 80);
        register("minerais", Material.CHARCOAL, 75);
        register("minerais", Material.RAW_COPPER, 70);
        register("minerais", Material.COPPER_INGOT, 95);
        register("minerais", Material.RAW_IRON, 180);
        register("minerais", Material.IRON_INGOT, 240);
        register("minerais", Material.RAW_GOLD, 420);
        register("minerais", Material.GOLD_INGOT, 500);
        register("minerais", Material.REDSTONE, 90);
        register("minerais", Material.LAPIS_LAZULI, 85);
        register("minerais", Material.QUARTZ, 110);
        register("minerais", Material.DIAMOND, 4500);
        register("minerais", Material.EMERALD, 1300);
        register("minerais", Material.AMETHYST_SHARD, 260);
        register("minerais", Material.NETHERITE_SCRAP, 9500);
        register("minerais", Material.NETHERITE_INGOT, 42000);

        // --- Minerais bruts (blocs a miner, obtenables en survie) ---
        register("minerais", Material.ANCIENT_DEBRIS, 8000);
        register("minerais", Material.COAL_ORE, 110);
        register("minerais", Material.DEEPSLATE_COAL_ORE, 120);
        register("minerais", Material.COPPER_ORE, 95);
        register("minerais", Material.DEEPSLATE_COPPER_ORE, 105);
        register("minerais", Material.IRON_ORE, 220);
        register("minerais", Material.DEEPSLATE_IRON_ORE, 240);
        register("minerais", Material.GOLD_ORE, 480);
        register("minerais", Material.DEEPSLATE_GOLD_ORE, 520);
        register("minerais", Material.NETHER_GOLD_ORE, 300);
        register("minerais", Material.REDSTONE_ORE, 140);
        register("minerais", Material.DEEPSLATE_REDSTONE_ORE, 150);
        register("minerais", Material.LAPIS_ORE, 200);
        register("minerais", Material.DEEPSLATE_LAPIS_ORE, 220);
        register("minerais", Material.NETHER_QUARTZ_ORE, 160);
        register("minerais", Material.DIAMOND_ORE, 5000);
        register("minerais", Material.DEEPSLATE_DIAMOND_ORE, 5400);
        register("minerais", Material.EMERALD_ORE, 1600);
        register("minerais", Material.DEEPSLATE_EMERALD_ORE, 1750);

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

        // --- Bois (chaque essence a un prix different) ---
        register("bois", Material.OAK_LOG, 70);        register("bois", Material.OAK_PLANKS, 18);
        register("bois", Material.SPRUCE_LOG, 68);     register("bois", Material.SPRUCE_PLANKS, 17);
        register("bois", Material.BIRCH_LOG, 66);      register("bois", Material.BIRCH_PLANKS, 17);
        register("bois", Material.JUNGLE_LOG, 74);     register("bois", Material.JUNGLE_PLANKS, 19);
        register("bois", Material.ACACIA_LOG, 76);     register("bois", Material.ACACIA_PLANKS, 19);
        register("bois", Material.DARK_OAK_LOG, 80);   register("bois", Material.DARK_OAK_PLANKS, 20);
        register("bois", Material.MANGROVE_LOG, 82);   register("bois", Material.MANGROVE_PLANKS, 21);
        register("bois", Material.CHERRY_LOG, 88);     register("bois", Material.CHERRY_PLANKS, 22);
        register("bois", Material.CRIMSON_STEM, 78);   register("bois", Material.CRIMSON_PLANKS, 20);
        register("bois", Material.WARPED_STEM, 78);    register("bois", Material.WARPED_PLANKS, 20);
        register("bois", Material.BAMBOO, 12);

        // --- Nourriture ---
        register("nourriture", Material.WHEAT, 20);
        registerBlock("nourriture", Material.HAY_BLOCK, Material.WHEAT, 9);
        register("nourriture", Material.BREAD, 55);
        register("nourriture", Material.APPLE, 70);
        register("nourriture", Material.GOLDEN_APPLE, 3200);
        register("nourriture", Material.CARROT, 18);
        register("nourriture", Material.POTATO, 18);
        register("nourriture", Material.BEETROOT, 16);
        register("nourriture", Material.BEEF, 45);
        register("nourriture", Material.COOKED_BEEF, 70);
        register("nourriture", Material.PORKCHOP, 44);
        register("nourriture", Material.COOKED_PORKCHOP, 68);
        register("nourriture", Material.CHICKEN, 35);
        register("nourriture", Material.COOKED_CHICKEN, 55);
        register("nourriture", Material.MUTTON, 35);
        register("nourriture", Material.SALMON, 35);
        register("nourriture", Material.COD, 30);
        register("nourriture", Material.SUGAR_CANE, 16);
        register("nourriture", Material.EGG, 20);
        register("nourriture", Material.MELON_SLICE, 10);
        register("nourriture", Material.PUMPKIN, 40);
        register("nourriture", Material.SWEET_BERRIES, 18);
        register("nourriture", Material.HONEY_BOTTLE, 75);

        // --- Loot de mobs ---
        register("mobs", Material.ROTTEN_FLESH, 12);
        register("mobs", Material.BONE, 30);
        registerBlock("mobs", Material.BONE_BLOCK, Material.BONE, 9);
        register("mobs", Material.STRING, 25);
        register("mobs", Material.SPIDER_EYE, 30);
        register("mobs", Material.GUNPOWDER, 80);
        register("mobs", Material.SLIME_BALL, 45);
        registerBlock("mobs", Material.SLIME_BLOCK, Material.SLIME_BALL, 9);
        register("mobs", Material.ENDER_PEARL, 240);
        register("mobs", Material.BLAZE_ROD, 160);
        register("mobs", Material.GHAST_TEAR, 600);
        register("mobs", Material.MAGMA_CREAM, 85);
        register("mobs", Material.PHANTOM_MEMBRANE, 130);
        register("mobs", Material.LEATHER, 30);
        register("mobs", Material.FEATHER, 16);
        register("mobs", Material.INK_SAC, 20);
        register("mobs", Material.PRISMARINE_SHARD, 50);
        register("mobs", Material.PRISMARINE_CRYSTALS, 85);
        register("mobs", Material.NETHER_STAR, 120000);
        register("mobs", Material.SHULKER_SHELL, 1800);

        // --- Redstone ---
        register("redstone", Material.REDSTONE_TORCH, 110);
        register("redstone", Material.REPEATER, 320);
        register("redstone", Material.COMPARATOR, 480);
        register("redstone", Material.PISTON, 750);
        register("redstone", Material.STICKY_PISTON, 950);
        register("redstone", Material.HOPPER, 1100);
        register("redstone", Material.DISPENSER, 720);
        register("redstone", Material.DROPPER, 540);
        register("redstone", Material.OBSERVER, 640);
        register("redstone", Material.LEVER, 70);
        register("redstone", Material.STONE_BUTTON, 35);
        register("redstone", Material.TARGET, 220);

        // --- Divers ---
        register("divers", Material.TORCH, 18);
        register("divers", Material.STICK, 8);
        register("divers", Material.FLINT, 22);
        register("divers", Material.PAPER, 22);
        register("divers", Material.BOOK, 110);
        register("divers", Material.NAME_TAG, 2000);
        register("divers", Material.EXPERIENCE_BOTTLE, 380);
        register("divers", Material.ENDER_PEARL, 240);
        register("divers", Material.ENDER_EYE, 450);
        register("divers", Material.BLAZE_POWDER, 90);
        register("divers", Material.GLOWSTONE_DUST, 40);
        register("divers", Material.BOOKSHELF, 450);
        register("divers", Material.ITEM_FRAME, 150);
        register("divers", Material.ARMOR_STAND, 220);
        register("divers", Material.LANTERN, 110);
        register("divers", Material.CHAIN, 150);

        // --- TOUS LES BLOCS (auto, SANS doublons) ---
        // On rassemble tout ce qui est deja range dans une categorie pour ne pas le reproposer.
        Set<Material> already = new HashSet<>();
        for (List<Material> list : categories.values()) already.addAll(list);

        List<Material> allBlocks = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isBlock() || !m.isItem()) continue;
            if (m.isAir()) continue;
            if (blacklist.contains(m)) continue;
            if (isNonSurvival(m)) continue;              // command block, spawner, etc. (par motif)
            if (m.name().endsWith("_SPAWN_EGG")) continue;
            if (already.contains(m)) continue;           // deja dans une autre categorie -> pas de doublon
            if (!buyPrices.containsKey(m)) buyPrices.put(m, fallbackPrice(m));
            allBlocks.add(m);
        }
        allBlocks.sort(Comparator.comparing(Enum::name));
        categories.put("tousblocs", allBlocks);

        // --- Diagnostic console : prouve que cette version est bien chargee ---
        long cb = allBlocks.stream().filter(x -> x.name().contains("COMMAND_BLOCK")).count();
        long spawn = allBlocks.stream().filter(x -> x.name().contains("SPAWNER")).count();
        getLogger().info("[SuperShop] TOUS les blocs = " + allBlocks.size()
                + " | command_block dedans = " + cb + " (doit etre 0)"
                + " | spawner dedans = " + spawn + " (doit etre 0)");
    }

    /** Bloc non recuperable en survie (creatif uniquement, technique, etc.). Filtre par motif. */
    private boolean isNonSurvival(Material m) {
        String n = m.name();
        return n.contains("COMMAND_BLOCK")            // COMMAND_BLOCK, CHAIN_..., REPEATING_...
            || n.contains("STRUCTURE")                // STRUCTURE_BLOCK, STRUCTURE_VOID
            || n.contains("INFESTED")                 // tous les blocs infestes
            || n.contains("SPAWNER")                  // SPAWNER, TRIAL_SPAWNER
            || n.contains("SUSPICIOUS")               // SUSPICIOUS_SAND/GRAVEL
            || n.endsWith("_HEAD") || n.endsWith("_SKULL")   // tetes/cranes (dont PLAYER_HEAD)
            || n.equals("JIGSAW")
            || n.equals("BARRIER") || n.equals("LIGHT")
            || n.equals("BEDROCK")
            || n.equals("DEBUG_STICK")
            || n.equals("BUDDING_AMETHYST")
            || n.equals("REINFORCED_DEEPSLATE")
            || n.equals("END_PORTAL_FRAME") || n.equals("END_GATEWAY")
            || n.equals("PETRIFIED_OAK_SLAB")
            || n.equals("FARMLAND") || n.equals("DIRT_PATH")
            || n.equals("FROGSPAWN")
            || n.equals("DRAGON_EGG")
            || n.equals("VAULT")
            || n.equals("POWDER_SNOW");
    }

    /** Prix estime pour un bloc non defini a la main. Plus haut + unique par bloc. */
    private double fallbackPrice(Material m) {
        String n = m.name();
        double base;
        if (n.contains("ORE")) base = 900;
        else if (n.endsWith("_SLAB")) base = 30;
        else if (n.endsWith("_STAIRS")) base = 75;
        else if (n.endsWith("_WALL")) base = 55;
        else if (n.endsWith("_FENCE_GATE")) base = 70;
        else if (n.endsWith("_FENCE")) base = 60;
        else if (n.endsWith("_TRAPDOOR")) base = 80;
        else if (n.endsWith("_DOOR")) base = 90;
        else if (n.endsWith("_BUTTON")) base = 25;
        else if (n.endsWith("_PRESSURE_PLATE")) base = 40;
        else if (n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN")) base = 45;
        else if (n.contains("CONCRETE_POWDER")) base = 55;
        else if (n.contains("CONCRETE")) base = 85;
        else if (n.contains("TERRACOTTA")) base = 70;
        else if (n.contains("GLAZED")) base = 95;
        else if (n.contains("GLASS")) base = 45;
        else if (n.contains("WOOL")) base = 45;
        else if (n.contains("CARPET")) base = 30;
        else if (n.contains("PLANKS")) base = 42;
        else if (n.contains("LOG") || n.contains("WOOD") || n.contains("STEM") || n.contains("HYPHAE")) base = 60;
        else if (n.contains("LEAVES")) base = 22;
        else if (n.contains("COPPER")) base = 80;
        else if (n.contains("DEEPSLATE")) base = 48;
        else if (n.contains("BLACKSTONE")) base = 55;
        else if (n.contains("BASALT")) base = 45;
        else if (n.contains("PRISMARINE")) base = 90;
        else if (n.contains("QUARTZ")) base = 120;
        else if (n.contains("PURPUR")) base = 95;
        else if (n.contains("CORAL")) base = 160;
        else if (n.contains("BRICK")) base = 65;
        else if (n.contains("SANDSTONE")) base = 60;
        else if (n.contains("SAND")) base = 32;
        else if (n.contains("GRAVEL")) base = 30;
        else if (n.contains("MUSHROOM") || n.contains("FUNGUS") || n.contains("ROOTS")) base = 55;
        else if (n.contains("FLOWER") || n.contains("TULIP") || n.contains("ORCHID") || n.contains("POPPY")) base = 40;
        else if (n.contains("SAPLING")) base = 50;
        else base = 75;
        // Variation deterministe 0-40% -> chaque bloc a un prix unique
        int h = Math.floorMod(n.hashCode(), 100);        // 0..99
        return Math.round(base * (1.0 + h / 100.0 * 0.40));
    }

    private double spawnerPrice(EntityType et) {
        switch (et) {
            case BLAZE: return 260000;
            case WITHER_SKELETON: return 650000;
            case ENDERMAN: return 320000;
            case IRON_GOLEM: return 400000;
            case GUARDIAN: case ELDER_GUARDIAN: return 750000;
            case ZOMBIE: case SKELETON: case SPIDER: case CREEPER: return 180000;
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
            for (Material m : categories.getOrDefault(cat, List.of())) {
                if (blacklist.contains(m) || isNonSurvival(m)) continue; // securite affichage
                items.add(itemDisplay(m));
            }
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
            p.sendMessage(Component.text("Vendu " + sold + " objets pour " + money(total) + "$").color(NamedTextColor.GOLD));
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
        if (n.equals("WATER") || n.equals("MUNDANE") || n.equals("THICK") || n.equals("AWKWARD")) return 250;
        double base = 900;
        if (n.startsWith("STRONG_")) base += 500;
        if (n.startsWith("LONG_")) base += 300;
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
        CommandMap map = getCommandMap();
        Map<String, Command> known = (map != null) ? knownCommands(map) : null;
        if (known != null) {
            for (String label : myCommands) {
                Command c = known.remove(label);
                if (c != null) { try { c.unregister(map); } catch (Throwable ignored) {} }
                known.remove("supershop:" + label);
            }
        }
        myCommands.clear();
        syncCommands();

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

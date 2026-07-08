package fr.stackspawner;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StackSpawner - a mettre dans : fr/stackspawner/StackSpawner.java
 *
 * - Les spawners NE font PAS apparaitre de mobs (spawn annule).
 * - A la place, ils generent virtuellement le LOOT et l'XP des mobs, accumules dedans.
 * - Clic droit sur le spawner -> menu : une case XP (clic = recuperer) + un "coffre"
 *   contenant tout ce que les mobs ont genere (clic = recuperer).
 * - Stacking : poser un spawner sur un autre du MEME mob augmente le stack (max 10000).
 *   Si le mob est different, il se pose a cote (spawner separe).
 * - Tout est sauvegarde sur le disque (type, stack, xp, contenu du loot).
 *
 * Commande admin : /stackspawner give <mob> [nombre]   (perm : stackspawner.admin ou OP)
 */
public class StackSpawner extends LoadedPlugin implements Listener {

    // ---- config ----
    private int intervalTicks = 200;         // 10s
    private boolean requireNearby = true;
    private double activationRange = 16;
    private int lootSample = 8;
    private int maxStack = 10000;
    private int maxLootTypes = 45;
    private long maxLootPerType = 2_000_000L;
    private long maxXp = 100_000_000L;
    private int saveIntervalTicks = 2400;    // 2 min
    private String defaultType = "PIG";

    private final Map<String, SpawnerData> spawners = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Object diskLock = new Object();
    private volatile boolean dirty = false;

    private File dataFile;
    private File configFile;
    private int genTaskId = -1;
    private int saveTaskId = -1;

    // ===================== MODELE =====================

    private static final class LootEntry {
        ItemStack proto;   // 1 exemplaire (amount = 1)
        long count;        // quantite reelle stockee
    }

    private static final class SpawnerData {
        EntityType type;
        int stack = 1;
        long xp = 0;
        final List<LootEntry> loot = new ArrayList<>();

        void addLoot(ItemStack sample, long amount, int maxTypes, long maxPerType) {
            if (sample == null || sample.getType() == Material.AIR || amount <= 0) return;
            ItemStack proto = sample.clone();
            proto.setAmount(1);
            for (LootEntry le : loot) {
                if (le.proto.isSimilar(proto)) {
                    le.count = Math.min(maxPerType, le.count + amount);
                    return;
                }
            }
            if (loot.size() >= maxTypes) return; // plein : on ignore les nouveaux types
            LootEntry le = new LootEntry();
            le.proto = proto;
            le.count = Math.min(maxPerType, amount);
            loot.add(le);
        }
    }

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        this.dataFile = new File(getHost().getDataFolder(), "spawners.yml");
        this.configFile = new File(getHost().getDataFolder(), "stackspawner-config.yml");
        loadConfig();
        loadData();
        unregisterStaleListeners();
        registerListener(this);

        registerCommand("stackspawner", (sender, cmd, label, args) -> {
            handleAdmin(sender, args);
            return true;
        });

        if (getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            genTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGeneration,
                    intervalTicks, intervalTicks).getTaskId();
            saveTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) { saveData(); dirty = false; } },
                    saveIntervalTicks, saveIntervalTicks).getTaskId();
        } else {
            getLogger().warning("[StackSpawner] getHost() n'est pas un Plugin -> generation auto desactivee.");
        }

        getLogger().info("[StackSpawner] active. (" + spawners.size() + " spawners charges)");
    }

    @Override
    public void onDisable() {
        try { if (genTaskId != -1) Bukkit.getScheduler().cancelTask(genTaskId); } catch (Throwable ignored) {}
        try { if (saveTaskId != -1) Bukkit.getScheduler().cancelTask(saveTaskId); } catch (Throwable ignored) {}
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        saveData();
        getLogger().info("[StackSpawner] desactive.");
    }

    private void unregisterStaleListeners() {
        try {
            String myClass = getClass().getName();
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                for (org.bukkit.plugin.RegisteredListener rl : hl.getRegisteredListeners()) {
                    Object l = rl.getListener();
                    if (l != null && l != this && l.getClass().getName().equals(myClass)) {
                        try { hl.unregister(rl); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    // ===================== CONFIG =====================

    private static final String DEFAULT_CONFIG =
            "# ============================================\n" +
            "#              StackSpawner\n" +
            "# ============================================\n" +
            "generation:\n" +
            "  # Intervalle entre deux cycles de generation (en ticks, 20 = 1s).\n" +
            "  interval-ticks: 200\n" +
            "  # N'accumule que si un joueur est a portee.\n" +
            "  require-nearby-player: true\n" +
            "  activation-range: 16\n" +
            "  # Nombre de tirages de la table de loot par cycle (approximation, perf).\n" +
            "  loot-sample: 8\n" +
            "limits:\n" +
            "  max-stack: 10000\n" +
            "  max-loot-types: 45\n" +
            "  max-loot-per-type: 2000000\n" +
            "  max-xp: 100000000\n" +
            "save-interval-ticks: 2400\n" +
            "default-type: PIG\n";

    private void loadConfig() {
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (!configFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) { w.write(DEFAULT_CONFIG); }
            }
            YamlConfiguration c = YamlConfiguration.loadConfiguration(configFile);
            intervalTicks    = Math.max(20, c.getInt("generation.interval-ticks", 200));
            requireNearby    = c.getBoolean("generation.require-nearby-player", true);
            activationRange  = Math.max(1, c.getDouble("generation.activation-range", 16));
            lootSample       = Math.max(1, c.getInt("generation.loot-sample", 8));
            maxStack         = Math.max(1, c.getInt("limits.max-stack", 10000));
            maxLootTypes     = Math.max(1, c.getInt("limits.max-loot-types", 45));
            maxLootPerType   = Math.max(1L, c.getLong("limits.max-loot-per-type", 2_000_000L));
            maxXp            = Math.max(1L, c.getLong("limits.max-xp", 100_000_000L));
            saveIntervalTicks= Math.max(200, c.getInt("save-interval-ticks", 2400));
            defaultType      = c.getString("default-type", "PIG");
        } catch (Throwable t) {
            getLogger().warning("[StackSpawner] config KO : " + t.getMessage());
        }
    }

    // ===================== EVENEMENTS =====================

    /** Les mobs ne spawnent pas depuis nos spawners. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent e) {
        try {
            Location l = e.getSpawner().getLocation();
            if (spawners.containsKey(keyOf(l))) e.setCancelled(true);
        } catch (Throwable ignored) {}
    }

    /** Pose : stacking si meme mob sur un spawner existant, sinon nouveau spawner. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        EntityType newType = typeFromItem(e.getItemInHand());
        if (newType == null) newType = safeType(defaultType);

        Block against = e.getBlockAgainst();
        SpawnerData existing = (against != null && against.getType() == Material.SPAWNER)
                ? spawners.get(keyOf(against.getLocation())) : null;

        if (existing != null) {
            if (existing.type == newType) {
                if (existing.stack >= maxStack) {
                    e.setCancelled(true);
                    msg(p, "Ce stack est deja au maximum (" + maxStack + ").", NamedTextColor.RED);
                    return;
                }
                e.setCancelled(true);            // on ne pose pas un nouveau bloc : on empile
                existing.stack = Math.min(maxStack, existing.stack + 1);
                consumeOne(p, e.getHand());
                dirty = true;
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                msg(p, "Spawner empile : " + prettyMob(existing.type)
                        + " x" + existing.stack + "/" + maxStack, NamedTextColor.GREEN);
                return;
            }
            // mob different -> on laisse la pose normale (spawner separe).
        }

        // Nouveau spawner.
        final EntityType ft = newType;
        final Location loc = e.getBlockPlaced().getLocation();
        SpawnerData d = new SpawnerData();
        d.type = ft;
        d.stack = 1;
        spawners.put(keyOf(loc), d);
        dirty = true;

        if (getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            Bukkit.getScheduler().runTask(plugin, () -> setupBlock(loc.getBlock(), ft));
        } else {
            setupBlock(loc.getBlock(), ft);
        }
        msg(p, "Spawner pose : " + prettyMob(ft) + ".", NamedTextColor.GRAY);
    }

    /** Casse : retire 1 du stack ; a 0 on rend le loot + l'XP restants. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.SPAWNER) return;
        String key = keyOf(e.getBlock().getLocation());
        SpawnerData d = spawners.get(key);
        if (d == null) return;

        Player p = e.getPlayer();
        e.setDropItems(false);
        e.setExpToDrop(0);

        if (d.stack > 1) {
            e.setCancelled(true); // le bloc reste : il reste des spawners dans le stack
            d.stack--;
            dirty = true;
            giveOrDrop(p, spawnerItem(d.type, 1));
            msg(p, "Spawner retire du stack. Reste : x" + d.stack, NamedTextColor.YELLOW);
            return;
        }

        // Dernier spawner : on rend l'item + le loot + l'XP accumules, puis on supprime.
        giveOrDrop(p, spawnerItem(d.type, 1));
        Location drop = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (LootEntry le : new ArrayList<>(d.loot)) dropAll(drop, le.proto, le.count);
        if (d.xp > 0 && drop.getWorld() != null) {
            long xp = d.xp;
            while (xp > 0) {
                final int give = (int) Math.min(Integer.MAX_VALUE, xp);
                drop.getWorld().spawn(drop, org.bukkit.entity.ExperienceOrb.class, o -> o.setExperience(give));
                xp -= give;
            }
        }
        spawners.remove(key);
        dirty = true;
        msg(p, "Spawner casse : loot et XP restants relaches.", NamedTextColor.GRAY);
    }

    /** Clic droit : ouvre le menu du spawner. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        if (!spawners.containsKey(keyOf(b.getLocation()))) return;
        e.setCancelled(true); // empeche l'edition vanilla (oeuf) et ouvre notre menu
        openMenu(e.getPlayer(), b);
    }

    // ===================== MENU =====================

    private static final class Menu implements InventoryHolder {
        final String key;
        Inventory inv;
        Menu(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private void openMenu(Player p, Block b) {
        String key = keyOf(b.getLocation());
        SpawnerData d = spawners.get(key);
        if (d == null) return;
        Menu holder = new Menu(key);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("Spawner " + prettyMob(d.type), NamedTextColor.GOLD, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
        holder.inv = inv;
        render(inv, d);
        p.openInventory(inv);
    }

    private void render(Inventory inv, SpawnerData d) {
        inv.clear();
        for (int i = 0; i < d.loot.size() && i < 45; i++) inv.setItem(i, displayLoot(d.loot.get(i)));
        ItemStack pane = pane();
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(45, infoItem(d));
        inv.setItem(49, xpItem(d));
        inv.setItem(53, collectAllItem());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        SpawnerData d = spawners.get(menu.key);
        if (d == null) { p.closeInventory(); return; }

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return; // clic dans l'inventaire du joueur : ignore

        if (slot == 49) {                 // XP
            collectXp(p, d);
        } else if (slot == 53) {          // tout recuperer
            collectAllLoot(p, d);
        } else if (slot == 45) {          // info : rien
            return;
        } else if (slot < 45 && slot < d.loot.size()) {
            LootEntry le = d.loot.get(slot);
            giveLoot(p, d, le);
        } else {
            return;
        }
        dirty = true;
        render(e.getInventory(), d);
    }

    private void collectXp(Player p, SpawnerData d) {
        if (d.xp <= 0) { msg(p, "Aucune XP a recuperer.", NamedTextColor.RED); return; }
        long xp = d.xp;
        d.xp = 0;
        while (xp > 0) { int give = (int) Math.min(Integer.MAX_VALUE, xp); p.giveExp(give); xp -= give; }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        msg(p, "XP recuperee.", NamedTextColor.GREEN);
    }

    private void collectAllLoot(Player p, SpawnerData d) {
        if (d.loot.isEmpty()) { msg(p, "Le coffre est vide.", NamedTextColor.RED); return; }
        boolean full = false;
        for (LootEntry le : new ArrayList<>(d.loot)) {
            giveInto(p, le);
            if (le.count <= 0) d.loot.remove(le);
            else full = true;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        msg(p, full ? "Inventaire plein : il reste du loot dans le spawner."
                    : "Loot recupere.", full ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
    }

    private void giveLoot(Player p, SpawnerData d, LootEntry le) {
        long before = le.count;
        giveInto(p, le);
        if (le.count <= 0) d.loot.remove(le);
        else if (le.count == before) msg(p, "Inventaire plein.", NamedTextColor.YELLOW);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
    }

    /** Donne autant que possible dans l'inventaire ; met a jour le count restant. */
    private void giveInto(Player p, LootEntry le) {
        long remaining = le.count;
        int max = Math.max(1, le.proto.getMaxStackSize());
        while (remaining > 0) {
            int amt = (int) Math.min(max, remaining);
            ItemStack st = le.proto.clone();
            st.setAmount(amt);
            Map<Integer, ItemStack> left = p.getInventory().addItem(st);
            if (!left.isEmpty()) {
                int notAdded = 0;
                for (ItemStack l : left.values()) notAdded += l.getAmount();
                remaining -= (amt - notAdded);
                break; // inventaire plein
            }
            remaining -= amt;
        }
        le.count = remaining;
    }

    // ===================== GENERATION =====================

    private void tickGeneration() {
        boolean any = false;
        for (Map.Entry<String, SpawnerData> e : new ArrayList<>(spawners.entrySet())) {
            SpawnerData d = e.getValue();
            Location loc = locOf(e.getKey());
            if (loc == null || loc.getWorld() == null) continue;
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            Block b = loc.getBlock();
            if (b.getType() != Material.SPAWNER) { spawners.remove(e.getKey()); any = true; continue; }
            if (requireNearby && !playerNear(loc, activationRange)) continue;
            generate(d, loc.clone().add(0.5, 0.5, 0.5));
            any = true;
        }
        if (any) dirty = true;
    }

    private void generate(SpawnerData d, Location loc) {
        int n = d.stack;
        LootTable lt = lootTableFor(d.type);
        if (lt != null) {
            int sample = Math.min(Math.max(1, n), lootSample);
            List<ItemStack> merged = new ArrayList<>();
            try {
                LootContext ctx = new LootContext.Builder(loc).build();
                for (int s = 0; s < sample; s++) {
                    for (ItemStack it : lt.populateLoot(random, ctx)) {
                        if (it == null || it.getType() == Material.AIR) continue;
                        boolean done = false;
                        for (ItemStack m : merged) {
                            if (m.isSimilar(it)) { m.setAmount(m.getAmount() + it.getAmount()); done = true; break; }
                        }
                        if (!done) merged.add(it.clone());
                    }
                }
            } catch (Throwable ignored) {}
            for (ItemStack m : merged) {
                long scaled = (long) m.getAmount() * n / sample;
                if (scaled > 0) d.addLoot(m, scaled, maxLootTypes, maxLootPerType);
            }
        }
        d.xp = Math.min(maxXp, d.xp + (long) xpFor(d.type) * n);
    }

    private LootTable lootTableFor(EntityType type) {
        try { return LootTables.valueOf(type.name()).getLootTable(); }
        catch (Throwable t) { return null; }
    }

    private int xpFor(EntityType t) {
        switch (t.name()) {
            case "COW": case "PIG": case "SHEEP": case "CHICKEN": case "RABBIT":
            case "HORSE": case "DONKEY": case "MULE": case "LLAMA": case "CAT":
            case "WOLF": case "FOX": case "PANDA": case "BEE": case "GOAT":
            case "MOOSHROOM": case "MUSHROOM_COW":
                return 2;                 // animaux : ~1-3
            case "BLAZE": return 10;
            default: return 5;            // hostiles
        }
    }

    private boolean playerNear(Location loc, double range) {
        if (loc.getWorld() == null) return false;
        double r2 = range * range;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) return true;
        }
        return false;
    }

    // ===================== ITEMS / MENU DISPLAY =====================

    private ItemStack displayLoot(LootEntry le) {
        ItemStack is = le.proto.clone();
        is.setAmount((int) Math.max(1, Math.min(le.proto.getMaxStackSize(), le.count)));
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(line("Quantite : ", NamedTextColor.GRAY)
                    .append(Component.text(fmt(le.count), NamedTextColor.YELLOW)));
            lore.add(line("Clic pour recuperer", NamedTextColor.DARK_GRAY));
            m.lore(lore);
            is.setItemMeta(m);
        }
        return is;
    }

    private ItemStack xpItem(SpawnerData d) {
        ItemStack is = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.displayName(Component.text("XP", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line("Disponible : ", NamedTextColor.GRAY).append(Component.text(fmt(d.xp) + " XP", NamedTextColor.YELLOW)));
            lore.add(line("Clic pour recuperer", NamedTextColor.DARK_GRAY));
            m.lore(lore);
            is.setItemMeta(m);
        }
        return is;
    }

    private ItemStack infoItem(SpawnerData d) {
        ItemStack is = new ItemStack(Material.SPAWNER);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.displayName(Component.text("Spawner " + prettyMob(d.type), NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line("Stack : ", NamedTextColor.GRAY).append(Component.text(d.stack + " / " + maxStack, NamedTextColor.AQUA)));
            lore.add(line("Genere le loot + l'XP de " + d.stack + " mobs / cycle.", NamedTextColor.DARK_GRAY));
            m.lore(lore);
            is.setItemMeta(m);
        }
        return is;
    }

    private ItemStack collectAllItem() {
        ItemStack is = new ItemStack(Material.CHEST);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.displayName(Component.text("Tout recuperer", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(m);
        }
        return is;
    }

    private ItemStack pane() {
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        if (m != null) { m.displayName(Component.text(" ")); is.setItemMeta(m); }
        return is;
    }

    private ItemStack spawnerItem(EntityType type, int amount) {
        ItemStack is = new ItemStack(Material.SPAWNER, Math.max(1, amount));
        ItemMeta meta = is.getItemMeta();
        if (meta instanceof BlockStateMeta bsm) {
            try {
                BlockState st = bsm.getBlockState();
                if (st instanceof CreatureSpawner cs) { cs.setSpawnedType(type); bsm.setBlockState(cs); }
            } catch (Throwable ignored) {}
            bsm.displayName(Component.text("Spawner " + prettyMob(type), NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            is.setItemMeta(bsm);
        }
        return is;
    }

    private EntityType typeFromItem(ItemStack item) {
        if (item != null && item.getItemMeta() instanceof BlockStateMeta bsm) {
            try {
                BlockState st = bsm.getBlockState();
                if (st instanceof CreatureSpawner cs) return cs.getSpawnedType();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void setupBlock(Block b, EntityType type) {
        try {
            if (b.getType() != Material.SPAWNER) return;
            BlockState st = b.getState();
            if (st instanceof CreatureSpawner cs) {
                cs.setSpawnedType(type);
                try { cs.setRequiredPlayerRange(0); } catch (Throwable ignored) {}
                try { cs.setMinSpawnDelay(Integer.MAX_VALUE); cs.setMaxSpawnDelay(Integer.MAX_VALUE); } catch (Throwable ignored) {}
                cs.update(true, false);
            }
        } catch (Throwable ignored) {}
    }

    // ===================== COMMANDE ADMIN =====================

    private void handleAdmin(org.bukkit.command.CommandSender s, String[] args) {
        if (!(s.isOp() || s.hasPermission("stackspawner.admin"))) {
            msg(s, "Reserve aux OP.", NamedTextColor.RED); return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            loadConfig();
            msg(s, "Config rechargee.", NamedTextColor.GREEN);
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            if (!(s instanceof Player p)) { msg(s, "Joueurs uniquement.", NamedTextColor.RED); return; }
            EntityType type = safeType(args[1].toUpperCase(Locale.ROOT));
            if (type == null) { msg(p, "Mob inconnu : " + args[1], NamedTextColor.RED); return; }
            int amount = 1;
            if (args.length >= 3) { try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (Exception ignored) {} }
            giveOrDrop(p, spawnerItem(type, amount));
            msg(p, "Recu : " + amount + " spawner(s) " + prettyMob(type) + ".", NamedTextColor.GREEN);
            return;
        }
        msg(s, "Usage: /stackspawner give <mob> [nombre]  |  /stackspawner reload", NamedTextColor.GRAY);
    }

    private EntityType safeType(String name) {
        try { return EntityType.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Throwable t) { return null; }
    }

    // ===================== PERSISTANCE =====================

    private void loadData() {
        spawners.clear();
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration cfg;
        synchronized (diskLock) { cfg = YamlConfiguration.loadConfiguration(dataFile); }
        List<Map<?, ?>> list = cfg.getMapList("spawners");
        for (Map<?, ?> raw : list) {
            try {
                String world = String.valueOf(raw.get("world"));
                int x = ((Number) raw.get("x")).intValue();
                int y = ((Number) raw.get("y")).intValue();
                int z = ((Number) raw.get("z")).intValue();
                EntityType type = safeType(String.valueOf(raw.get("type")));
                if (type == null) continue;
                SpawnerData d = new SpawnerData();
                d.type = type;
                d.stack = raw.get("stack") != null ? ((Number) raw.get("stack")).intValue() : 1;
                d.xp = raw.get("xp") != null ? ((Number) raw.get("xp")).longValue() : 0;
                Object lootObj = raw.get("loot");
                if (lootObj instanceof List<?> ll) {
                    for (Object o : ll) {
                        String entry = String.valueOf(o);
                        int at = entry.lastIndexOf("@@");
                        if (at < 0) continue;
                        String b64 = entry.substring(0, at);
                        long count = Long.parseLong(entry.substring(at + 2));
                        ItemStack proto = deserializeItem(b64);
                        if (proto == null || count <= 0) continue;
                        LootEntry le = new LootEntry();
                        proto.setAmount(1);
                        le.proto = proto; le.count = count;
                        d.loot.add(le);
                    }
                }
                spawners.put(world + ";" + x + ";" + y + ";" + z, d);
            } catch (Throwable t) {
                getLogger().warning("[StackSpawner] entree spawner illisible ignoree : " + t);
            }
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        synchronized (diskLock) {
            YamlConfiguration cfg = new YamlConfiguration();
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, SpawnerData> e : spawners.entrySet()) {
                String[] parts = e.getKey().split(";");
                if (parts.length != 4) continue;
                SpawnerData d = e.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("world", parts[0]);
                m.put("x", Integer.parseInt(parts[1]));
                m.put("y", Integer.parseInt(parts[2]));
                m.put("z", Integer.parseInt(parts[3]));
                m.put("type", d.type.name());
                m.put("stack", d.stack);
                m.put("xp", d.xp);
                List<String> loot = new ArrayList<>();
                for (LootEntry le : d.loot) {
                    String b64 = serializeItem(le.proto);
                    if (b64 != null) loot.add(b64 + "@@" + le.count);
                }
                m.put("loot", loot);
                list.add(m);
            }
            cfg.set("spawners", list);
            try {
                File dir = dataFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                cfg.save(dataFile);
            } catch (IOException e) {
                getLogger().warning("[StackSpawner] sauvegarde KO : " + e.getMessage());
            }
        }
    }

    private String serializeItem(ItemStack it) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) { out.writeObject(it); }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) { getLogger().warning("[StackSpawner] serialisation item KO : " + t); return null; }
    }

    private ItemStack deserializeItem(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                return (ItemStack) in.readObject();
            }
        } catch (Throwable t) { getLogger().warning("[StackSpawner] lecture item KO : " + t); return null; }
    }

    // ===================== OUTILS =====================

    private String keyOf(Location l) { return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    private Location locOf(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try { return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
        catch (Exception ex) { return null; }
    }

    private void consumeOne(Player p, EquipmentSlot hand) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack is = (hand == EquipmentSlot.OFF_HAND) ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (is == null || is.getType() == Material.AIR) return;
        int amt = is.getAmount() - 1;
        if (amt <= 0) {
            if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null);
            else p.getInventory().setItemInMainHand(null);
        } else is.setAmount(amt);
    }

    private void giveOrDrop(Player p, ItemStack item) {
        for (ItemStack leftover : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }

    private void dropAll(Location loc, ItemStack proto, long count) {
        if (loc.getWorld() == null) return;
        int max = Math.max(1, proto.getMaxStackSize());
        long remaining = count;
        int safety = 0;
        while (remaining > 0 && safety++ < 100000) {
            int amt = (int) Math.min(max, remaining);
            ItemStack st = proto.clone(); st.setAmount(amt);
            loc.getWorld().dropItemNaturally(loc, st);
            remaining -= amt;
        }
    }

    private String prettyMob(EntityType t) {
        String n = t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private String fmt(long v) { return String.format(Locale.FRANCE, "%,d", v); }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static final Component PREFIX = Component.text("Spawner ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("\u00bb ", NamedTextColor.DARK_GRAY));

    private void msg(org.bukkit.command.CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(PREFIX.append(Component.text(text, color)));
    }
}

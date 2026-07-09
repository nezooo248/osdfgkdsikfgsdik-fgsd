package fr.kit;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Systeme de kits pour Optaland.
 *
 * Permissions :
 *   kit.kit.<nom>          -> autorise l'utilisation d'un kit precis.
 *   kit.admin              -> creation / suppression / give / reload (OP aussi autorise).
 *   kit.cooldown.bypass    -> ignore les cooldowns de kit.
 *
 * Commandes :
 *   /kit                       -> ouvre le menu des kits.
 *   /kit <nom>                 -> recupere directement un kit (si permission).
 *   /kit create <nom> [MATERIAL] [cooldownSec]  (admin) -> cree un kit depuis TON inventaire actuel.
 *   /kit delete <nom>          (admin) -> supprime un kit.
 *   /kit give <nom> [joueur]   (admin) -> donne un kit (ignore permission + cooldown).
 *   /kit list                  -> liste les kits.
 *   /kit reload                (admin) -> recharge les fichiers.
 *
 * Les kits (inventaire + armure + main gauche) sont serialises en base64 dans kits.yml.
 * Les cooldowns sont conserves dans kit-cooldowns.yml (survivent au redemarrage).
 */
public class Kit extends LoadedPlugin implements Listener {

    static final String ADMIN_PERM      = "kit.admin";
    static final String KIT_PERM_PREFIX = "kit.kit.";
    static final String COOLDOWN_BYPASS = "kit.cooldown.bypass";

    private static final NamedTextColor RED    = NamedTextColor.RED;
    private static final NamedTextColor GREEN  = NamedTextColor.GREEN;
    private static final NamedTextColor GRAY   = NamedTextColor.GRAY;
    private static final NamedTextColor YELLOW = NamedTextColor.YELLOW;
    private static final NamedTextColor GOLD   = NamedTextColor.GOLD;

    private final Map<String, KitDef> kits = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    private final Set<String> myCommands = new HashSet<>();
    private final Object diskLock = new Object();
    private File kitsFile;
    private File cooldownsFile;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        File dir = getHost().getDataFolder();
        if (dir != null && !dir.exists()) dir.mkdirs();
        this.kitsFile = new File(dir, "kits.yml");
        this.cooldownsFile = new File(dir, "kit-cooldowns.yml");

        loadKits();
        loadCooldowns();
        unregisterStaleListeners();
        registerListener(this);
        registerCommands();
        syncCommands();

        getLogger().info("[Kit] active. " + kits.size() + " kit(s) charge(s).");
    }

    @Override
    public void onDisable() {
        try {
            saveCooldowns();
            try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}

            CommandMap map = getCommandMap();
            Map<String, Command> known = (map != null) ? knownCommands(map) : null;
            if (known != null) {
                for (String label : myCommands) {
                    Command c = known.remove(label);
                    if (c != null) { try { c.unregister(map); } catch (Throwable ignored) {} }
                    known.remove("kit:" + label);
                }
            }
            myCommands.clear();
            syncCommands();
        } catch (Throwable t) {
            getLogger().warning("[Kit] erreur onDisable : " + t);
        }
        getLogger().info("[Kit] desactive.");
    }

    /** Retire tout listener Kit fantome laisse par un /plreload precedent. */
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

    // ===================== ENREGISTREMENT DES COMMANDES =====================

    private CommandMap getCommandMap() {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(server);
        } catch (Throwable t) { return null; }
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
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private interface Exec { boolean run(CommandSender s, String[] a); }
    private interface Tab { List<String> run(CommandSender s, String[] a); }

    private void registerCommands() {
        CommandMap map = getCommandMap();
        if (map == null) { getLogger().warning("[Kit] CommandMap introuvable."); return; }
        registerOne(map, "kit", "Menu des kits",
                "/kit [nom|create|delete|list|give|reload]", List.of("kits"),
                (s, a) -> handleKit(s, a), (s, a) -> tabKit(s, a));
    }

    private void registerOne(CommandMap map, String name, String desc, String usage,
                             List<String> aliases, Exec exec, Tab tab) {
        Command cmd = new Command(name, desc, usage, aliases) {
            @Override public boolean execute(CommandSender s, String l, String[] a) { return exec.run(s, a); }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                return tab != null ? tab.run(s, a) : List.of();
            }
        };
        map.register("kit", cmd);
        Map<String, Command> known = knownCommands(map);
        if (known != null) known.put(name, cmd);
        myCommands.add(name);
    }

    private void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.updateCommands(); } catch (Throwable ignored) {}
        }
    }

    // ===================== MESSAGES =====================

    static final Component PREFIX = Component.text("Kits ", GREEN, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    static void send(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(PREFIX.append(Component.text(text, color)));
    }

    private boolean isAdmin(CommandSender s) { return s.isOp() || s.hasPermission(ADMIN_PERM); }

    private boolean noPerm(CommandSender s) {
        send(s, "Tu n'as pas la permission (" + ADMIN_PERM + ").", RED);
        return true;
    }

    // ===================== COMMANDE PRINCIPALE =====================

    private boolean handleKit(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { send(s, "Joueurs uniquement pour ouvrir le menu.", RED); return true; }
            if (kits.isEmpty()) { send(p, "Aucun kit n'a encore ete cree.", GRAY); return true; }
            openMenu(p);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": case "save": case "set":
                if (!isAdmin(s)) return noPerm(s);
                return createKit(s, a);
            case "delete": case "remove": case "del":
                if (!isAdmin(s)) return noPerm(s);
                return deleteKit(s, a);
            case "give": case "donner":
                if (!isAdmin(s)) return noPerm(s);
                return giveCommand(s, a);
            case "reload": case "rl":
                if (!isAdmin(s)) return noPerm(s);
                loadKits(); loadCooldowns();
                send(s, "Config rechargee (" + kits.size() + " kit(s)).", GREEN);
                return true;
            case "list": case "liste":
                return listKits(s);
            case "help": case "aide":
                return help(s);
            default:
                // Nom de kit -> recuperation directe.
                if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", RED); return true; }
                giveKit(p, sub, false);
                return true;
        }
    }

    private boolean help(CommandSender s) {
        send(s, "Commandes :", GOLD);
        s.sendMessage(Component.text("  /kit", GOLD).append(Component.text("  ouvre le menu.", GRAY)));
        s.sendMessage(Component.text("  /kit <nom>", GOLD).append(Component.text("  recupere un kit.", GRAY)));
        if (isAdmin(s)) {
            s.sendMessage(Component.text("  /kit create <nom> [MATERIAL] [cooldownSec]", GOLD)
                    .append(Component.text("  cree depuis ton inventaire.", GRAY)));
            s.sendMessage(Component.text("  /kit delete <nom>", GOLD).append(Component.text("  supprime.", GRAY)));
            s.sendMessage(Component.text("  /kit give <nom> [joueur]", GOLD).append(Component.text("  donne (ignore perm/cd).", GRAY)));
            s.sendMessage(Component.text("  /kit reload", GOLD).append(Component.text("  recharge.", GRAY)));
        }
        s.sendMessage(Component.text("  /kit list", GOLD).append(Component.text("  liste les kits.", GRAY)));
        send(s, "Permission par kit : " + KIT_PERM_PREFIX + "<nom>", GRAY);
        return true;
    }

    private boolean listKits(CommandSender s) {
        if (kits.isEmpty()) { send(s, "Aucun kit.", GRAY); return true; }
        send(s, "Kits (" + kits.size() + ") :", YELLOW);
        List<KitDef> list = new ArrayList<>(kits.values());
        list.sort(Comparator.comparing(k -> k.name));
        for (KitDef k : list) {
            boolean allowed = (s instanceof Player p)
                    ? (p.hasPermission(KIT_PERM_PREFIX + k.name) || isAdmin(s)) : true;
            Component line = Component.text("  " + k.display + " ", allowed ? GREEN : RED)
                    .append(Component.text("(" + countItems(k) + " items", GRAY))
                    .append(Component.text(k.cooldownSeconds > 0 ? ", cd " + human(k.cooldownSeconds * 1000L) : "", GRAY))
                    .append(Component.text(") ", GRAY))
                    .append(Component.text(KIT_PERM_PREFIX + k.name, NamedTextColor.DARK_GRAY));
            s.sendMessage(line);
        }
        return true;
    }

    // ===================== CREATION / SUPPRESSION =====================

    private boolean createKit(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement (l'inventaire sert de modele).", RED); return true; }
        if (a.length < 2) { send(s, "Usage: /kit create <nom> [MATERIAL] [cooldownSec]", GRAY); return true; }

        String name = a[1].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            send(p, "Nom invalide. Autorise : a-z 0-9 _ - (max 32).", RED);
            return true;
        }

        KitDef kit = new KitDef();
        kit.name = name;
        kit.display = a[1];
        kit.storage = deepClone(p.getInventory().getStorageContents());
        kit.armor   = deepClone(p.getInventory().getArmorContents());
        ItemStack off = p.getInventory().getItemInOffHand();
        kit.offhand = (off == null || off.getType() == Material.AIR) ? null : off.clone();

        if (countItems(kit) == 0) {
            send(p, "Ton inventaire est vide, impossible de creer un kit vide.", RED);
            return true;
        }

        // Icone.
        if (a.length >= 3) {
            Material m = Material.matchMaterial(a[2]);
            if (m != null && m.isItem()) kit.icon = m;
            else send(p, "Material inconnu : " + a[2] + " -> icone automatique.", YELLOW);
        }
        if (kit.icon == null) kit.icon = firstMaterial(kit);

        // Cooldown (dernier argument facultatif).
        if (a.length >= 4) {
            try { kit.cooldownSeconds = Math.max(0, Integer.parseInt(a[3])); }
            catch (Exception ignored) { send(p, "Cooldown invalide -> 0s.", YELLOW); }
        } else if (kits.containsKey(name)) {
            kit.cooldownSeconds = kits.get(name).cooldownSeconds; // on garde l'ancien cd si non precise
            kit.slot = kits.get(name).slot;
        }

        boolean update = kits.containsKey(name);
        kits.put(name, kit);
        saveKits();
        send(p, "Kit '" + name + "' " + (update ? "mis a jour" : "cree")
                + " (" + countItems(kit) + " items). Permission : " + KIT_PERM_PREFIX + name, GREEN);
        return true;
    }

    private boolean deleteKit(CommandSender s, String[] a) {
        if (a.length < 2) { send(s, "Usage: /kit delete <nom>", GRAY); return true; }
        String name = a[1].toLowerCase(Locale.ROOT);
        if (kits.remove(name) != null) {
            saveKits();
            send(s, "Kit '" + name + "' supprime.", GREEN);
        } else {
            send(s, "Kit inconnu : " + a[1], RED);
        }
        return true;
    }

    private boolean giveCommand(CommandSender s, String[] a) {
        if (a.length < 2) { send(s, "Usage: /kit give <nom> [joueur]", GRAY); return true; }
        String name = a[1].toLowerCase(Locale.ROOT);
        KitDef kit = kits.get(name);
        if (kit == null) { send(s, "Kit inconnu : " + a[1], RED); return true; }

        Player target;
        if (a.length >= 3) {
            target = Bukkit.getPlayerExact(a[2]);
            if (target == null) { send(s, "Joueur introuvable : " + a[2], RED); return true; }
        } else if (s instanceof Player p) {
            target = p;
        } else {
            send(s, "Precise un joueur depuis la console.", RED);
            return true;
        }

        applyKit(target, kit);
        send(s, "Kit '" + name + "' donne a " + target.getName() + ".", GREEN);
        if (!target.equals(s)) send(target, "Tu as recu le kit '" + name + "'.", GREEN);
        return true;
    }

    // ===================== RECUPERATION D'UN KIT =====================

    private void giveKit(Player p, String name, boolean bypass) {
        KitDef kit = kits.get(name.toLowerCase(Locale.ROOT));
        if (kit == null) { send(p, "Kit inconnu : " + name, RED); return; }

        if (!bypass) {
            boolean allowed = p.hasPermission(KIT_PERM_PREFIX + kit.name) || isAdmin(p);
            if (!allowed) { send(p, "Tu n'as pas la permission pour le kit '" + kit.name + "'.", RED); return; }

            long rem = cooldownRemainingMillis(p, kit);
            if (rem > 0 && !p.hasPermission(COOLDOWN_BYPASS)) {
                send(p, "Kit '" + kit.name + "' disponible dans " + human(rem) + ".", YELLOW);
                return;
            }
        }

        applyKit(p, kit);
        if (!bypass && kit.cooldownSeconds > 0 && !p.hasPermission(COOLDOWN_BYPASS)) setCooldown(p, kit);
        send(p, "Tu as recu le kit '" + kit.name + "'.", GREEN);
    }

    /** Donne les items du kit : storage/offhand par addItem, armure equipee si le slot est libre. */
    private void applyKit(Player p, KitDef kit) {
        if (kit.storage != null) {
            for (ItemStack it : kit.storage) {
                if (it != null && it.getType() != Material.AIR) {
                    for (ItemStack left : p.getInventory().addItem(it.clone()).values())
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
            }
        }
        if (kit.armor != null) {
            ItemStack[] cur = p.getInventory().getArmorContents(); // [bottes, jambieres, plastron, casque]
            for (int i = 0; i < 4 && i < kit.armor.length; i++) {
                ItemStack piece = kit.armor[i];
                if (piece == null || piece.getType() == Material.AIR) continue;
                if (cur[i] == null || cur[i].getType() == Material.AIR) {
                    cur[i] = piece.clone();
                } else {
                    for (ItemStack left : p.getInventory().addItem(piece.clone()).values())
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
            }
            p.getInventory().setArmorContents(cur);
        }
        if (kit.offhand != null && kit.offhand.getType() != Material.AIR) {
            ItemStack curOff = p.getInventory().getItemInOffHand();
            if (curOff == null || curOff.getType() == Material.AIR) {
                p.getInventory().setItemInOffHand(kit.offhand.clone());
            } else {
                for (ItemStack left : p.getInventory().addItem(kit.offhand.clone()).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        p.updateInventory();
    }

    // ===================== COOLDOWNS =====================

    private long cooldownRemainingMillis(Player p, KitDef kit) {
        if (kit.cooldownSeconds <= 0) return 0;
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long last = m.get(kit.name);
        if (last == null) return 0;
        long end = last + kit.cooldownSeconds * 1000L;
        return Math.max(0, end - System.currentTimeMillis());
    }

    private void setCooldown(Player p, KitDef kit) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                 .put(kit.name, System.currentTimeMillis());
        saveCooldowns();
    }

    // ===================== MENU (GUI) =====================

    static final Component MENU_TITLE = Component.text("Kits", NamedTextColor.DARK_GREEN, TextDecoration.BOLD);

    static final class KitMenuHolder implements InventoryHolder {
        final Map<Integer, String> slotToKit = new java.util.HashMap<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void openMenu(Player p) {
        List<KitDef> list = new ArrayList<>(kits.values());
        list.sort(Comparator
                .comparingInt((KitDef k) -> k.slot < 0 ? Integer.MAX_VALUE : k.slot)
                .thenComparing(k -> k.name));

        int maxSlot = 0;
        for (KitDef k : list) if (k.slot >= 0) maxSlot = Math.max(maxSlot, k.slot + 1);
        int count = Math.max(list.size(), maxSlot);
        int rows = Math.min(6, Math.max(1, (int) Math.ceil(count / 9.0)));
        int size = rows * 9;

        KitMenuHolder holder = new KitMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, size, MENU_TITLE);
        holder.inv = inv;

        Set<Integer> used = new HashSet<>();
        List<KitDef> auto = new ArrayList<>();
        for (KitDef k : list) {
            if (k.slot >= 0 && k.slot < size && !used.contains(k.slot)) {
                inv.setItem(k.slot, buildIcon(p, k));
                holder.slotToKit.put(k.slot, k.name);
                used.add(k.slot);
            } else {
                auto.add(k);
            }
        }
        int idx = 0;
        for (KitDef k : auto) {
            while (idx < size && used.contains(idx)) idx++;
            if (idx >= size) break;
            inv.setItem(idx, buildIcon(p, k));
            holder.slotToKit.put(idx, k.name);
            used.add(idx);
            idx++;
        }

        p.openInventory(inv);
    }

    private ItemStack buildIcon(Player p, KitDef kit) {
        boolean allowed = p.hasPermission(KIT_PERM_PREFIX + kit.name) || isAdmin(p);
        Material mat = allowed ? (kit.icon != null ? kit.icon : Material.CHEST) : Material.BARRIER;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(kit.display, allowed ? GREEN : RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(countItems(kit) + " item(s)", GRAY).decoration(TextDecoration.ITALIC, false));
            if (kit.cooldownSeconds > 0)
                lore.add(Component.text("Cooldown : " + human(kit.cooldownSeconds * 1000L), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());

            if (!allowed) {
                lore.add(Component.text("Tu n'as pas acces a ce kit.", RED).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("(" + KIT_PERM_PREFIX + kit.name + ")", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                long rem = cooldownRemainingMillis(p, kit);
                if (rem > 0 && !p.hasPermission(COOLDOWN_BYPASS))
                    lore.add(Component.text("Disponible dans " + human(rem), YELLOW).decoration(TextDecoration.ITALIC, false));
                else
                    lore.add(Component.text("Clique pour recuperer ce kit.", YELLOW).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof KitMenuHolder holder)) return;
        e.setCancelled(true); // menu en lecture seule
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) return; // clic dans l'inventaire du joueur
        String kitName = holder.slotToKit.get(raw);
        if (kitName == null) return;

        p.closeInventory();
        giveKit(p, kitName, false);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof KitMenuHolder) e.setCancelled(true);
    }

    // ===================== OUTILS ITEMS =====================

    private ItemStack[] deepClone(ItemStack[] src) {
        if (src == null) return null;
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (src[i] == null) ? null : src[i].clone();
        return out;
    }

    private int countItems(KitDef k) {
        int c = countNonNull(k.storage) + countNonNull(k.armor);
        if (k.offhand != null && k.offhand.getType() != Material.AIR) c++;
        return c;
    }

    private int countNonNull(ItemStack[] arr) {
        if (arr == null) return 0;
        int c = 0;
        for (ItemStack it : arr) if (it != null && it.getType() != Material.AIR) c++;
        return c;
    }

    private Material firstMaterial(KitDef k) {
        if (k.storage != null) for (ItemStack it : k.storage) if (it != null && it.getType() != Material.AIR) return it.getType();
        if (k.armor != null)   for (ItemStack it : k.armor)   if (it != null && it.getType() != Material.AIR) return it.getType();
        if (k.offhand != null && k.offhand.getType() != Material.AIR) return k.offhand.getType();
        return Material.CHEST;
    }

    private String human(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    // ===================== TAB COMPLETION =====================

    private List<String> tabKit(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> opts = new ArrayList<>(kits.keySet());
            if (isAdmin(s)) opts.addAll(List.of("create", "delete", "give", "list", "reload", "help"));
            return filter(opts, a[0]);
        }
        if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") || sub.equals("remove") || sub.equals("del") || sub.equals("give"))
                return filter(new ArrayList<>(kits.keySet()), a[1]);
        }
        if (a.length == 3) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give")) return tabPlayers(a[2]);
            if (sub.equals("create") || sub.equals("save") || sub.equals("set"))
                return filter(List.of("DIAMOND_SWORD", "IRON_SWORD", "BOW", "CHEST", "GOLDEN_APPLE", "COOKED_BEEF"), a[2]);
        }
        return List.of();
    }

    private List<String> tabPlayers(String prefix) {
        String x = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers())
            if (o.getName().toLowerCase(Locale.ROOT).startsWith(x)) out.add(o.getName());
        return out;
    }

    private List<String> filter(List<String> src, String prefix) {
        String x = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : src) if (o.toLowerCase(Locale.ROOT).startsWith(x)) out.add(o);
        return out;
    }

    // ===================== SERIALISATION (base64) =====================

    private String toBase64(ItemStack[] items) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(items.length);
            for (ItemStack it : items) out.writeObject(it);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private ItemStack[] fromBase64(String data) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(data);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int len = in.readInt();
            ItemStack[] out = new ItemStack[len];
            for (int i = 0; i < len; i++) out[i] = (ItemStack) in.readObject();
            return out;
        }
    }

    private ItemStack[] fromBase64Safe(String data) {
        if (data == null) return null;
        try { return fromBase64(data); }
        catch (Throwable t) { getLogger().warning("[Kit] lecture item KO : " + t); return null; }
    }

    private String toBase64Safe(ItemStack[] arr) {
        try { return toBase64(arr == null ? new ItemStack[0] : arr); }
        catch (Throwable t) { getLogger().warning("[Kit] serialisation KO : " + t); return null; }
    }

    // ===================== PERSISTANCE =====================

    private static final class KitDef {
        String name;
        String display;
        Material icon;
        int cooldownSeconds = 0;
        int slot = -1;
        ItemStack[] storage;
        ItemStack[] armor;
        ItemStack offhand;
    }

    private void loadKits() {
        kits.clear();
        if (kitsFile == null || !kitsFile.exists()) return;
        YamlConfiguration cfg;
        synchronized (diskLock) { cfg = YamlConfiguration.loadConfiguration(kitsFile); }
        ConfigurationSection sec = cfg.getConfigurationSection("kits");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                String base = "kits." + key;
                KitDef k = new KitDef();
                k.name = key.toLowerCase(Locale.ROOT);
                k.display = cfg.getString(base + ".display", key);
                Material m = Material.matchMaterial(cfg.getString(base + ".icon", "CHEST"));
                k.icon = (m != null) ? m : Material.CHEST;
                k.cooldownSeconds = Math.max(0, cfg.getInt(base + ".cooldown", 0));
                k.slot = cfg.getInt(base + ".slot", -1);
                k.storage = fromBase64Safe(cfg.getString(base + ".storage"));
                k.armor   = fromBase64Safe(cfg.getString(base + ".armor"));
                ItemStack[] off = fromBase64Safe(cfg.getString(base + ".offhand"));
                k.offhand = (off != null && off.length > 0 && off[0] != null
                        && off[0].getType() != Material.AIR) ? off[0] : null;
                kits.put(k.name, k);
            } catch (Throwable t) {
                getLogger().warning("[Kit] kit illisible '" + key + "' : " + t);
            }
        }
    }

    private void saveKits() {
        if (kitsFile == null) return;
        synchronized (diskLock) {
            YamlConfiguration cfg = new YamlConfiguration();
            for (KitDef k : kits.values()) {
                String base = "kits." + k.name;
                cfg.set(base + ".display", k.display);
                cfg.set(base + ".icon", (k.icon != null ? k.icon.name() : "CHEST"));
                cfg.set(base + ".cooldown", k.cooldownSeconds);
                cfg.set(base + ".slot", k.slot);
                cfg.set(base + ".storage", toBase64Safe(k.storage));
                cfg.set(base + ".armor", toBase64Safe(k.armor));
                ItemStack offToSave = (k.offhand != null) ? k.offhand : new ItemStack(Material.AIR);
                cfg.set(base + ".offhand", toBase64Safe(new ItemStack[]{ offToSave }));
            }
            try {
                File dir = kitsFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                cfg.save(kitsFile);
            } catch (IOException e) {
                getLogger().warning("[Kit] sauvegarde kits KO : " + e.getMessage());
            }
        }
    }

    private void loadCooldowns() {
        cooldowns.clear();
        if (cooldownsFile == null || !cooldownsFile.exists()) return;
        YamlConfiguration cfg;
        synchronized (diskLock) { cfg = YamlConfiguration.loadConfiguration(cooldownsFile); }
        ConfigurationSection sec = cfg.getConfigurationSection("players");
        if (sec == null) return;
        for (String uid : sec.getKeys(false)) {
            try {
                UUID u = UUID.fromString(uid);
                ConfigurationSection ks = cfg.getConfigurationSection("players." + uid);
                if (ks == null) continue;
                Map<String, Long> m = new ConcurrentHashMap<>();
                for (String kn : ks.getKeys(false))
                    m.put(kn.toLowerCase(Locale.ROOT), cfg.getLong("players." + uid + "." + kn));
                if (!m.isEmpty()) cooldowns.put(u, m);
            } catch (Throwable ignored) {}
        }
    }

    private void saveCooldowns() {
        if (cooldownsFile == null) return;
        synchronized (diskLock) {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, Map<String, Long>> e : cooldowns.entrySet()) {
                for (Map.Entry<String, Long> ke : e.getValue().entrySet())
                    cfg.set("players." + e.getKey() + "." + ke.getKey(), ke.getValue());
            }
            try {
                File dir = cooldownsFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                cfg.save(cooldownsFile);
            } catch (IOException e) {
                getLogger().warning("[Kit] sauvegarde cooldowns KO : " + e.getMessage());
            }
        }
    }
}

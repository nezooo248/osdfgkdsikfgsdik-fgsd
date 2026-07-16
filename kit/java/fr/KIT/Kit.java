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
import org.bukkit.event.inventory.InventoryCloseEvent;
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
import java.util.HashMap;
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
 *   kit.admin              -> creation / suppression / give / reload / edit (OP aussi autorise).
 *   kit.cooldown.bypass    -> ignore les cooldowns de kit.
 *
 * Commandes :
 *   /kit                       -> ouvre le menu des kits.
 *   /kit <nom>                 -> recupere directement un kit (si permission).
 *   /kit create <nom> [MATERIAL] [cooldownSec]  (admin)
 *   /kit delete <nom>          (admin)
 *   /kit give <nom> [joueur]   (admin)
 *   /kit edit <nom>            (admin) -> ouvre le menu d'edition du kit.
 *   /kit organize              (admin) -> ouvre le menu principal en mode reorganisation.
 *   /kit list                  -> liste les kits.
 *   /kit reload                (admin)
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
    private static final NamedTextColor ORANGE = NamedTextColor.GOLD;

    private static final int MENU_SIZE = 54; // double coffre

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
                "/kit [nom|create|delete|edit|organize|list|give|reload]", List.of("kits"),
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
            openMenu(p, false);
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
            case "edit": case "editer":
                if (!isAdmin(s)) return noPerm(s);
                return editKit(s, a);
            case "organize": case "organise": case "reorganize":
                if (!isAdmin(s)) return noPerm(s);
                if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", RED); return true; }
                openMenu(p, true);
                return true;
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
            s.sendMessage(Component.text("  /kit edit <nom>", GOLD).append(Component.text("  editer (contenu/nom/cd/icone).", GRAY)));
            s.sendMessage(Component.text("  /kit organize", GOLD).append(Component.text("  reorganiser les emplacements.", GRAY)));
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

        if (a.length >= 3) {
            Material m = Material.matchMaterial(a[2]);
            if (m != null && m.isItem()) kit.icon = m;
            else send(p, "Material inconnu : " + a[2] + " -> icone automatique.", YELLOW);
        }
        if (kit.icon == null) kit.icon = firstMaterial(kit);

        if (a.length >= 4) {
            try { kit.cooldownSeconds = Math.max(0, Integer.parseInt(a[3])); }
            catch (Exception ignored) { send(p, "Cooldown invalide -> 0s.", YELLOW); }
        } else if (kits.containsKey(name)) {
            kit.cooldownSeconds = kits.get(name).cooldownSeconds;
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

    private boolean editKit(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", RED); return true; }
        if (a.length < 2) { send(s, "Usage: /kit edit <nom>", GRAY); return true; }
        String name = a[1].toLowerCase(Locale.ROOT);
        KitDef kit = kits.get(name);
        if (kit == null) { send(s, "Kit inconnu : " + a[1], RED); return true; }
        openEditMenu(p, kit);
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
            ItemStack[] cur = p.getInventory().getArmorContents();
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

    // ===================== DECORATION =====================

    /** Coins en L : vitres orange. Renvoie l'ensemble des slots decoratifs pour un menu 54. */
    private static Set<Integer> cornerSlots() {
        Set<Integer> s = new HashSet<>();
        // Coin haut-gauche
        s.add(0); s.add(1); s.add(9);
        // Coin haut-droit
        s.add(7); s.add(8); s.add(17);
        // Coin bas-gauche
        s.add(45); s.add(36); s.add(46);
        // Coin bas-droit
        s.add(53); s.add(52); s.add(44);
        return s;
    }

    private ItemStack orangePane() {
        ItemStack it = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            it.setItemMeta(meta);
        }
        return it;
    }

    // ===================== MENU PRINCIPAL (GUI) =====================

    static final Component MENU_TITLE      = Component.text("Kits", NamedTextColor.DARK_GREEN, TextDecoration.BOLD);
    static final Component MENU_TITLE_EDIT = Component.text("Kits ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("[Reorganisation]", NamedTextColor.YELLOW));

    static final class KitMenuHolder implements InventoryHolder {
        final Map<Integer, String> slotToKit = new HashMap<>();
        final boolean organize;
        Inventory inv;
        KitMenuHolder(boolean organize) { this.organize = organize; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int SLOT_SAVE = 49; // bouton sauvegarder (mode organize)

    private void openMenu(Player p, boolean organize) {
        List<KitDef> list = new ArrayList<>(kits.values());
        list.sort(Comparator
                .comparingInt((KitDef k) -> k.slot < 0 ? Integer.MAX_VALUE : k.slot)
                .thenComparing(k -> k.name));

        KitMenuHolder holder = new KitMenuHolder(organize);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, organize ? MENU_TITLE_EDIT : MENU_TITLE);
        holder.inv = inv;

        Set<Integer> corners = cornerSlots();
        for (int slot : corners) inv.setItem(slot, orangePane());

        Set<Integer> used = new HashSet<>(corners);
        if (organize) used.add(SLOT_SAVE);

        List<KitDef> auto = new ArrayList<>();
        for (KitDef k : list) {
            if (k.slot >= 0 && k.slot < MENU_SIZE && !used.contains(k.slot)) {
                inv.setItem(k.slot, buildIcon(p, k, organize));
                holder.slotToKit.put(k.slot, k.name);
                used.add(k.slot);
            } else {
                auto.add(k);
            }
        }
        int idx = 0;
        for (KitDef k : auto) {
            while (idx < MENU_SIZE && used.contains(idx)) idx++;
            if (idx >= MENU_SIZE) break;
            inv.setItem(idx, buildIcon(p, k, organize));
            holder.slotToKit.put(idx, k.name);
            used.add(idx);
            idx++;
        }

        if (organize) {
            ItemStack save = new ItemStack(Material.LIME_CONCRETE);
            ItemMeta meta = save.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("Sauvegarder les emplacements", GREEN, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Deplace les icones puis clique ici.", GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Les vitres oranges sont fixes.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                save.setItemMeta(meta);
            }
            inv.setItem(SLOT_SAVE, save);
        }

        p.openInventory(inv);
    }

    private ItemStack buildIcon(Player p, KitDef kit, boolean organize) {
        boolean allowed = organize || p.hasPermission(KIT_PERM_PREFIX + kit.name) || isAdmin(p);
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

            if (organize) {
                lore.add(Component.text("Glisse-moi pour changer d'emplacement.", YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Kit : " + kit.name, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            } else if (!allowed) {
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

    // ===================== MENU D'EDITION (GUI) =====================

    static final class KitEditHolder implements InventoryHolder {
        final String kitName;
        Inventory inv;
        KitEditHolder(String kitName) { this.kitName = kitName; }
        @Override public Inventory getInventory() { return inv; }
    }

    // Slots du menu d'edition (taille 27)
    private static final int E_CONTENT   = 11; // editer le contenu
    private static final int E_ICON      = 13; // changer l'icone
    private static final int E_COOLDOWN  = 15; // changer le cooldown
    private static final int E_RENAME    = 21; // renommer (display)
    private static final int E_GIVEME    = 23; // se donner le kit (test)
    private static final int E_DELETE    = 26; // supprimer

    private void openEditMenu(Player p, KitDef kit) {
        KitEditHolder holder = new KitEditHolder(kit.name);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Edition : ", GOLD, TextDecoration.BOLD)
                        .append(Component.text(kit.display, YELLOW)));
        holder.inv = inv;

        for (int i = 0; i < 27; i++) inv.setItem(i, orangePane());

        inv.setItem(E_CONTENT, button(Material.CHEST, "Editer le contenu", GREEN,
                "Ouvre une grille.", "Depose les items du kit,", "puis ferme pour sauvegarder."));
        inv.setItem(E_ICON, button(kit.icon != null ? kit.icon : Material.CHEST, "Changer l'icone", YELLOW,
                "Clique gauche : prendre l'item", "dans ta main comme icone.",
                "Icone actuelle : " + (kit.icon != null ? kit.icon.name() : "CHEST")));
        inv.setItem(E_COOLDOWN, button(Material.CLOCK, "Changer le cooldown", YELLOW,
                "Clic gauche : +10s   Clic droit : -10s",
                "Shift+clic : +/- 60s",
                "Actuel : " + (kit.cooldownSeconds > 0 ? human(kit.cooldownSeconds * 1000L) : "aucun")));
        inv.setItem(E_RENAME, button(Material.NAME_TAG, "Renommer", YELLOW,
                "Clic gauche : prendre le NOM de l'item", "dans ta main (son nom affiche).",
                "Nom actuel : " + kit.display));
        inv.setItem(E_GIVEME, button(Material.PLAYER_HEAD, "Se donner le kit (test)", GREEN,
                "Clique pour recevoir le kit", "et verifier son contenu."));
        inv.setItem(E_DELETE, button(Material.BARRIER, "Supprimer le kit", RED,
                "Shift + clic gauche pour", "supprimer definitivement."));

        p.openInventory(inv);
    }

    private ItemStack button(Material mat, String title, NamedTextColor color, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(title, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Component.text(l, GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ===================== EDITEUR DE CONTENU (grille) =====================

    static final class KitContentHolder implements InventoryHolder {
        final String kitName;
        Inventory inv;
        KitContentHolder(String kitName) { this.kitName = kitName; }
        @Override public Inventory getInventory() { return inv; }
    }

    private void openContentEditor(Player p, KitDef kit) {
        KitContentHolder holder = new KitContentHolder(kit.name);
        // 54 slots : on met le storage (jusqu'a 45) puis une ligne pour armure/offhand.
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("Contenu : ", GOLD, TextDecoration.BOLD)
                        .append(Component.text(kit.display, YELLOW)));
        holder.inv = inv;

        // Remplit les 45 premiers slots avec le storage existant.
        if (kit.storage != null) {
            for (int i = 0; i < kit.storage.length && i < 45; i++) {
                if (kit.storage[i] != null && kit.storage[i].getType() != Material.AIR)
                    inv.setItem(i, kit.storage[i].clone());
            }
        }
        // Ligne du bas (45-53) : armure + offhand, avec des indicateurs par-dessus les vides.
        // 45 casque, 46 plastron, 47 jambieres, 48 bottes, 50 offhand
        if (kit.armor != null) {
            // armor: [bottes, jambieres, plastron, casque]
            if (kit.armor.length > 3 && valid(kit.armor[3])) inv.setItem(45, kit.armor[3].clone());
            if (kit.armor.length > 2 && valid(kit.armor[2])) inv.setItem(46, kit.armor[2].clone());
            if (kit.armor.length > 1 && valid(kit.armor[1])) inv.setItem(47, kit.armor[1].clone());
            if (kit.armor.length > 0 && valid(kit.armor[0])) inv.setItem(48, kit.armor[0].clone());
        }
        if (kit.offhand != null && valid(kit.offhand)) inv.setItem(50, kit.offhand.clone());

        p.openInventory(inv);
        send(p, "Depose les items. Bas: 45=casque 46=plastron 47=jambieres 48=bottes 50=offhand. Ferme pour sauvegarder.", GRAY);
    }

    private static boolean valid(ItemStack it) { return it != null && it.getType() != Material.AIR; }

    private void saveContentFrom(Inventory inv, KitDef kit) {
        ItemStack[] storage = new ItemStack[45];
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            storage[i] = valid(it) ? it.clone() : null;
        }
        ItemStack helmet = inv.getItem(45), chest = inv.getItem(46),
                  legs = inv.getItem(47), boots = inv.getItem(48), off = inv.getItem(50);
        ItemStack[] armor = new ItemStack[]{
                valid(boots) ? boots.clone() : null,
                valid(legs)  ? legs.clone()  : null,
                valid(chest) ? chest.clone() : null,
                valid(helmet)? helmet.clone(): null
        };
        kit.storage = storage;
        kit.armor = armor;
        kit.offhand = valid(off) ? off.clone() : null;
        if (kit.icon == null) kit.icon = firstMaterial(kit);
        saveKits();
    }

    // ===================== EVENEMENTS INVENTAIRE =====================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // --- Menu principal ---
        if (h instanceof KitMenuHolder holder) {
            if (holder.organize) {
                handleOrganizeClick(e, holder, p, top);
                return;
            }
            e.setCancelled(true);
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= top.getSize()) return;
            String kitName = holder.slotToKit.get(raw);
            if (kitName == null) return;
            p.closeInventory();
            giveKit(p, kitName, false);
            return;
        }

        // --- Menu d'edition ---
        if (h instanceof KitEditHolder eh) {
            e.setCancelled(true);
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= top.getSize()) return;
            handleEditClick(e, eh, p, raw);
            return;
        }

        // --- Editeur de contenu : on laisse faire (drag & drop libre), rien a annuler ---
        // (KitContentHolder -> aucun setCancelled)
    }

    private void handleOrganizeClick(InventoryClickEvent e, KitMenuHolder holder, Player p, Inventory top) {
        int raw = e.getRawSlot();
        // Clic bouton sauvegarder
        if (raw == SLOT_SAVE) {
            e.setCancelled(true);
            saveOrganize(top, holder);
            send(p, "Emplacements sauvegardes.", GREEN);
            p.closeInventory();
            return;
        }
        // Empeche de bouger/retirer les vitres oranges
        Set<Integer> corners = cornerSlots();
        if (raw >= 0 && raw < top.getSize() && corners.contains(raw)) {
            e.setCancelled(true);
            return;
        }
        // Sinon : on autorise le deplacement des icones dans le menu (glisser-deposer).
        // On bloque uniquement le shift-click depuis l'inventaire du joueur pour ne pas injecter d'items.
        if (raw >= top.getSize() && e.isShiftClick()) {
            e.setCancelled(true);
        }
    }

    private void saveOrganize(Inventory top, KitMenuHolder holder) {
        Set<Integer> corners = cornerSlots();
        Map<String, Integer> newSlots = new HashMap<>();
        for (int slot = 0; slot < top.getSize(); slot++) {
            if (corners.contains(slot) || slot == SLOT_SAVE) continue;
            ItemStack it = top.getItem(slot);
            if (!valid(it)) continue;
            String kitName = matchKitByIcon(it);
            if (kitName != null) newSlots.put(kitName, slot);
        }
        for (Map.Entry<String, Integer> en : newSlots.entrySet()) {
            KitDef k = kits.get(en.getKey());
            if (k != null) k.slot = en.getValue();
        }
        // Les kits non retrouves gardent leur slot precedent.
        saveKits();
    }

    /** Retrouve le nom du kit a partir du nom affiche de l'icone. */
    private String matchKitByIcon(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(meta.displayName());
        for (KitDef k : kits.values()) {
            if (k.display.equalsIgnoreCase(plain)) return k.name;
        }
        return null;
    }

    private void handleEditClick(InventoryClickEvent e, KitEditHolder eh, Player p, int raw) {
        KitDef kit = kits.get(eh.kitName);
        if (kit == null) { p.closeInventory(); send(p, "Kit introuvable.", RED); return; }

        switch (raw) {
            case E_CONTENT -> {
                p.closeInventory();
                openContentEditor(p, kit);
            }
            case E_ICON -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (valid(hand)) {
                    kit.icon = hand.getType();
                    saveKits();
                    send(p, "Icone changee : " + kit.icon.name(), GREEN);
                    openEditMenu(p, kit);
                } else send(p, "Tiens un item en main pour definir l'icone.", YELLOW);
            }
            case E_COOLDOWN -> {
                int delta = e.isShiftClick() ? 60 : 10;
                if (e.isRightClick()) delta = -delta;
                kit.cooldownSeconds = Math.max(0, kit.cooldownSeconds + delta);
                saveKits();
                openEditMenu(p, kit);
            }
            case E_RENAME -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                ItemMeta hm = valid(hand) ? hand.getItemMeta() : null;
                if (hm != null && hm.hasDisplayName()) {
                    String newName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(hm.displayName());
                    if (!newName.isBlank()) {
                        kit.display = newName;
                        saveKits();
                        send(p, "Nom d'affichage change : " + newName, GREEN);
                        openEditMenu(p, kit);
                    } else send(p, "Le nom de l'item est vide.", YELLOW);
                } else send(p, "Tiens un item RENOMME en main (son nom deviendra le nom du kit).", YELLOW);
            }
            case E_GIVEME -> {
                p.closeInventory();
                applyKit(p, kit);
                send(p, "Kit '" + kit.name + "' donne (test).", GREEN);
            }
            case E_DELETE -> {
                if (e.isShiftClick() && e.isLeftClick()) {
                    kits.remove(kit.name);
                    saveKits();
                    p.closeInventory();
                    send(p, "Kit '" + kit.name + "' supprime.", GREEN);
                } else send(p, "Shift + clic gauche pour confirmer la suppression.", YELLOW);
            }
            default -> { /* vitres : rien */ }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof KitEditHolder) { e.setCancelled(true); return; }
        if (h instanceof KitMenuHolder holder && !holder.organize) { e.setCancelled(true); return; }
        // organize + content editor : drag autorise.
        // En organize, empeche de deposer sur une vitre.
        if (h instanceof KitMenuHolder holder && holder.organize) {
            Set<Integer> corners = cornerSlots();
            for (int slot : e.getRawSlots()) {
                if (slot < e.getView().getTopInventory().getSize()
                        && (corners.contains(slot) || slot == SLOT_SAVE)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onCloseInventory(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(e.getPlayer() instanceof Player p)) return;
        if (h instanceof KitContentHolder ch) {
            KitDef kit = kits.get(ch.kitName);
            if (kit != null) {
                saveContentFrom(e.getInventory(), kit);
                send(p, "Contenu du kit '" + kit.name + "' sauvegarde (" + countItems(kit) + " items).", GREEN);
            }
        }
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
            if (isAdmin(s)) opts.addAll(List.of("create", "delete", "edit", "organize", "give", "list", "reload", "help"));
            return filter(opts, a[0]);
        }
        if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") || sub.equals("remove") || sub.equals("del")
                    || sub.equals("give") || sub.equals("edit") || sub.equals("editer"))
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

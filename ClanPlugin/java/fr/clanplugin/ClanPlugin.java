package fr.clanplugin;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClanPlugin extends LoadedPlugin {

    static final int MAX_MEMBERS = 5;

    private ClanManager clanManager;

    @Override
    public void onEnable() {
        this.clanManager = new ClanManager(this);
        this.clanManager.load();

        // Enregistrement de la commande /clan via le CommandMap (pas de plugin.yml)
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            CommandMap map = (CommandMap) f.get(Bukkit.getServer());
            map.register("clan", new ClanCommand(this));
        } catch (Exception e) {
            getLogger().severe("Impossible d'enregistrer /clan : " + e.getMessage());
        }

        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("ClanPlugin active.");
    }

    @Override
    public void onDisable() {
        if (clanManager != null) clanManager.save();
    }

    public ClanManager getClanManager() { return clanManager; }

    /* ---- Helpers de messages (Adventure) ---- */
    static final Component PREFIX =
            Component.text("Clans ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    static void send(CommandSender s, Component body) {
        s.sendMessage(PREFIX.append(body));
    }

    void broadcast(Clan clan, Component body) {
        for (UUID u : clan.getMembers()) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.sendMessage(PREFIX.append(body));
        }
    }
}

/* ===================== MODELE ===================== */
class Clan {
    enum Role { LEADER, OFFICER, MEMBER }

    private final String name;
    private final UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> officers = new LinkedHashSet<>();

    Clan(String name, UUID leader) { this.name = name; this.leader = leader; this.members.add(leader); }

    String getName() { return name; }
    UUID getLeader() { return leader; }
    Set<UUID> getMembers() { return members; }
    Set<UUID> getOfficers() { return officers; }
    int size() { return members.size(); }

    boolean isMember(UUID u) { return members.contains(u); }
    boolean isLeader(UUID u) { return leader.equals(u); }
    boolean isOfficer(UUID u) { return officers.contains(u); }

    Role getRole(UUID u) {
        if (leader.equals(u)) return Role.LEADER;
        if (officers.contains(u)) return Role.OFFICER;
        if (members.contains(u)) return Role.MEMBER;
        return null;
    }

    void addMember(UUID u) { members.add(u); }
    void removeMember(UUID u) { members.remove(u); officers.remove(u); }
    void promote(UUID u) { if (members.contains(u) && !leader.equals(u)) officers.add(u); }
    void demote(UUID u) { officers.remove(u); }
    boolean canInvite(UUID u) { return isLeader(u) || isOfficer(u); }
    boolean canManageRoles(UUID u) { return isLeader(u); }
}

/* ===================== MANAGER ===================== */
class ClanManager {
    enum CreateResult { SUCCESS, ALREADY_IN_CLAN, NAME_TAKEN, INVALID_NAME }
    enum InviteResult { SUCCESS, NO_CLAN, NO_PERMISSION, CLAN_FULL, TARGET_IN_CLAN, ALREADY_INVITED, TARGET_SELF }
    enum JoinResult { SUCCESS, NO_INVITE, CLAN_GONE, CLAN_FULL, ALREADY_IN_CLAN }

    private final ClanPlugin plugin;
    private final File file;
    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, String> playerClan = new HashMap<>();
    private final Map<UUID, Set<String>> invites = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    ClanManager(ClanPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clans.yml");
    }

    Clan getClan(String name) { return name == null ? null : clans.get(name.toLowerCase(Locale.ROOT)); }
    Clan getClanOf(UUID u) { String k = playerClan.get(u); return k == null ? null : clans.get(k); }
    boolean hasClan(UUID u) { return playerClan.containsKey(u); }
    Collection<Clan> getClans() { return clans.values(); }
    Set<String> getInvites(UUID u) { return invites.getOrDefault(u, Collections.emptySet()); }

    String getName(UUID u) {
        Player o = Bukkit.getPlayer(u);
        if (o != null) return o.getName();
        return names.getOrDefault(u, u.toString().substring(0, 8));
    }
    void updateName(Player p) { names.put(p.getUniqueId(), p.getName()); }

    UUID getUUIDByName(String name) {
        Player o = Bukkit.getPlayerExact(name);
        if (o != null) return o.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet())
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    boolean isValidName(String n) { return n != null && n.matches("[A-Za-z0-9_]{3,16}"); }

    CreateResult createClan(Player p, String name) {
        if (hasClan(p.getUniqueId())) return CreateResult.ALREADY_IN_CLAN;
        if (!isValidName(name)) return CreateResult.INVALID_NAME;
        if (clans.containsKey(name.toLowerCase(Locale.ROOT))) return CreateResult.NAME_TAKEN;
        Clan clan = new Clan(name, p.getUniqueId());
        clans.put(name.toLowerCase(Locale.ROOT), clan);
        playerClan.put(p.getUniqueId(), name.toLowerCase(Locale.ROOT));
        updateName(p); save();
        return CreateResult.SUCCESS;
    }

    InviteResult invite(Player inviter, Player target) {
        Clan clan = getClanOf(inviter.getUniqueId());
        if (clan == null) return InviteResult.NO_CLAN;
        if (!clan.canInvite(inviter.getUniqueId())) return InviteResult.NO_PERMISSION;
        if (inviter.getUniqueId().equals(target.getUniqueId())) return InviteResult.TARGET_SELF;
        if (clan.size() >= ClanPlugin.MAX_MEMBERS) return InviteResult.CLAN_FULL;
        if (hasClan(target.getUniqueId())) return InviteResult.TARGET_IN_CLAN;
        Set<String> set = invites.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>());
        if (!set.add(clan.getName().toLowerCase(Locale.ROOT))) return InviteResult.ALREADY_INVITED;
        updateName(inviter); updateName(target);
        return InviteResult.SUCCESS;
    }

    JoinResult accept(Player p, String clanName) {
        if (hasClan(p.getUniqueId())) return JoinResult.ALREADY_IN_CLAN;
        Set<String> set = invites.get(p.getUniqueId());
        if (set == null || set.isEmpty()) return JoinResult.NO_INVITE;
        String key;
        if (clanName == null) {
            if (set.size() == 1) key = set.iterator().next(); else return JoinResult.NO_INVITE;
        } else {
            key = clanName.toLowerCase(Locale.ROOT);
            if (!set.contains(key)) return JoinResult.NO_INVITE;
        }
        Clan clan = clans.get(key);
        if (clan == null) { set.remove(key); return JoinResult.CLAN_GONE; }
        if (clan.size() >= ClanPlugin.MAX_MEMBERS) return JoinResult.CLAN_FULL;
        clan.addMember(p.getUniqueId());
        playerClan.put(p.getUniqueId(), key);
        invites.remove(p.getUniqueId());
        updateName(p); save();
        return JoinResult.SUCCESS;
    }

    boolean deny(Player p, String clanName) {
        Set<String> set = invites.get(p.getUniqueId());
        if (set == null || set.isEmpty()) return false;
        if (clanName == null) { invites.remove(p.getUniqueId()); return true; }
        boolean r = set.remove(clanName.toLowerCase(Locale.ROOT));
        if (set.isEmpty()) invites.remove(p.getUniqueId());
        return r;
    }

    boolean kick(Clan clan, UUID actor, UUID target) {
        if (!clan.isMember(target) || clan.isLeader(target) || target.equals(actor)) return false;
        if (clan.isLeader(actor)) { /* ok */ }
        else if (clan.isOfficer(actor)) { if (clan.isOfficer(target)) return false; }
        else return false;
        clan.removeMember(target); playerClan.remove(target); save();
        return true;
    }

    boolean promote(Clan clan, UUID actor, UUID target) {
        if (!clan.canManageRoles(actor)) return false;
        if (!clan.isMember(target) || clan.isLeader(target) || clan.isOfficer(target)) return false;
        clan.promote(target); save();
        return true;
    }

    boolean demote(Clan clan, UUID actor, UUID target) {
        if (!clan.canManageRoles(actor)) return false;
        if (!clan.isOfficer(target)) return false;
        clan.demote(target); save();
        return true;
    }

    void leaveClan(Clan clan, UUID u) { clan.removeMember(u); playerClan.remove(u); save(); }

    void disband(Clan clan) {
        String key = clan.getName().toLowerCase(Locale.ROOT);
        for (UUID u : clan.getMembers()) playerClan.remove(u);
        clans.remove(key);
        for (Set<String> s : invites.values()) s.remove(key);
        save();
    }

    void load() {
        clans.clear(); playerClan.clear(); names.clear();
        if (!file.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        org.bukkit.configuration.ConfigurationSection cs = cfg.getConfigurationSection("clans");
        if (cs != null) {
            for (String name : cs.getKeys(false)) {
                String path = "clans." + name;
                String ls = cfg.getString(path + ".leader");
                if (ls == null) continue;
                try {
                    Clan clan = new Clan(name, UUID.fromString(ls));
                    for (String mm : cfg.getStringList(path + ".members")) clan.addMember(UUID.fromString(mm));
                    for (String oo : cfg.getStringList(path + ".officers")) clan.getOfficers().add(UUID.fromString(oo));
                    clans.put(name.toLowerCase(Locale.ROOT), clan);
                    for (UUID u : clan.getMembers()) playerClan.put(u, name.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Clan invalide ignore : " + name);
                }
            }
        }
        org.bukkit.configuration.ConfigurationSection ns = cfg.getConfigurationSection("names");
        if (ns != null) for (String k : ns.getKeys(false)) {
            try { names.put(UUID.fromString(k), ns.getString(k)); } catch (IllegalArgumentException ignored) {}
        }
    }

    void save() {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (Clan clan : clans.values()) {
            String path = "clans." + clan.getName();
            cfg.set(path + ".leader", clan.getLeader().toString());
            List<String> mem = new ArrayList<>();
            for (UUID u : clan.getMembers()) mem.add(u.toString());
            cfg.set(path + ".members", mem);
            List<String> off = new ArrayList<>();
            for (UUID u : clan.getOfficers()) off.add(u.toString());
            cfg.set(path + ".officers", off);
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) cfg.set("names." + e.getKey(), e.getValue());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Sauvegarde KO : " + e.getMessage());
        }
    }
}

/* ===================== LISTENER JOIN ===================== */
class PlayerListener implements Listener {
    private final ClanPlugin plugin;
    PlayerListener(ClanPlugin plugin) { this.plugin = plugin; }
    @EventHandler public void onJoin(PlayerJoinEvent e) { plugin.getClanManager().updateName(e.getPlayer()); }
}

/* ===================== HOLDERS GUI ===================== */
class ClanMenuHolder implements InventoryHolder {
    private final String clanName;
    private final Map<Integer, UUID> slotToMember = new HashMap<>();
    private Inventory inventory;
    ClanMenuHolder(String clanName) { this.clanName = clanName; }
    String getClanName() { return clanName; }
    Map<Integer, UUID> getSlotToMember() { return slotToMember; }
    void setInventory(Inventory i) { this.inventory = i; }
    @Override public Inventory getInventory() { return inventory; }
}

class MemberMenuHolder implements InventoryHolder {
    private final String clanName;
    private final UUID target;
    private Inventory inventory;
    MemberMenuHolder(String clanName, UUID target) { this.clanName = clanName; this.target = target; }
    String getClanName() { return clanName; }
    UUID getTarget() { return target; }
    void setInventory(Inventory i) { this.inventory = i; }
    @Override public Inventory getInventory() { return inventory; }
}

/* ===================== GUI ===================== */
class ClanGUI {
    static final int INFO_SLOT = 4;
    static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23};
    static final int HEAD_SLOT = 4, KICK_SLOT = 11, ROLE_SLOT = 15, BACK_SLOT = 22;

    static Component txt(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    static void openMain(ClanPlugin plugin, Player viewer, Clan clan) {
        ClanManager m = plugin.getClanManager();
        ClanMenuHolder holder = new ClanMenuHolder(clan.getName());
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Clan: " + clan.getName(), NamedTextColor.GOLD));
        holder.setInventory(inv);
        fill(inv);

        List<Component> info = new ArrayList<>();
        info.add(txt("Chef: " + m.getName(clan.getLeader()), NamedTextColor.GRAY));
        info.add(txt("Membres: " + clan.size() + "/" + ClanPlugin.MAX_MEMBERS, NamedTextColor.GRAY));
        info.add(txt("Bras droits: " + clan.getOfficers().size(), NamedTextColor.GRAY));
        inv.setItem(INFO_SLOT, item(Material.OAK_SIGN,
                Component.text("Clan " + clan.getName(), NamedTextColor.GOLD, TextDecoration.BOLD), info));

        boolean canManage = clan.isLeader(viewer.getUniqueId()) || clan.isOfficer(viewer.getUniqueId());
        List<UUID> ordered = new ArrayList<>();
        ordered.add(clan.getLeader());
        for (UUID u : clan.getOfficers()) if (!ordered.contains(u)) ordered.add(u);
        for (UUID u : clan.getMembers()) if (!ordered.contains(u)) ordered.add(u);

        int i = 0;
        for (UUID u : ordered) {
            if (i >= MEMBER_SLOTS.length) break;
            int slot = MEMBER_SLOTS[i];
            List<Component> lore = new ArrayList<>();
            lore.add(txt("Grade: ", NamedTextColor.GRAY).append(roleComp(clan.getRole(u))));
            if (canManage && canManage(clan, viewer.getUniqueId(), u)) {
                lore.add(Component.empty());
                lore.add(txt("Clic pour gerer ce membre", NamedTextColor.YELLOW));
            }
            inv.setItem(slot, head(m.getName(u), clan.getRole(u), lore, u));
            holder.getSlotToMember().put(slot, u);
            i++;
        }
        viewer.openInventory(inv);
    }

    static void openMember(ClanPlugin plugin, Player viewer, Clan clan, UUID target) {
        ClanManager m = plugin.getClanManager();
        MemberMenuHolder holder = new MemberMenuHolder(clan.getName(), target);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Gerer: " + m.getName(target), NamedTextColor.GOLD));
        holder.setInventory(inv);
        fill(inv);

        Clan.Role tr = clan.getRole(target);
        boolean leader = clan.isLeader(viewer.getUniqueId());
        boolean officer = clan.isOfficer(viewer.getUniqueId());

        List<Component> hl = new ArrayList<>();
        hl.add(txt("Grade: ", NamedTextColor.GRAY).append(roleComp(tr)));
        inv.setItem(HEAD_SLOT, head(m.getName(target), tr, hl, target));

        boolean canKick = (leader && tr != Clan.Role.LEADER) || (officer && tr == Clan.Role.MEMBER);
        if (canKick) inv.setItem(KICK_SLOT, item(Material.BARRIER,
                Component.text("Exclure", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(txt("Retire ce joueur du clan.", NamedTextColor.GRAY))));

        if (leader) {
            if (tr == Clan.Role.MEMBER)
                inv.setItem(ROLE_SLOT, item(Material.EMERALD,
                        Component.text("Promouvoir bras droit", NamedTextColor.GREEN, TextDecoration.BOLD),
                        List.of(txt("Peut inviter et exclure des membres.", NamedTextColor.GRAY))));
            else if (tr == Clan.Role.OFFICER)
                inv.setItem(ROLE_SLOT, item(Material.GRAY_DYE,
                        Component.text("Repasser membre", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        List.of(txt("Retire le grade de bras droit.", NamedTextColor.GRAY))));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW,
                Component.text("Retour", NamedTextColor.GRAY), List.of()));
        viewer.openInventory(inv);
    }

    static boolean canManage(Clan clan, UUID viewer, UUID target) {
        if (viewer.equals(target)) return false;
        Clan.Role vr = clan.getRole(viewer), tr = clan.getRole(target);
        if (vr == null || tr == null || tr == Clan.Role.LEADER) return false;
        if (vr == Clan.Role.LEADER) return true;
        if (vr == Clan.Role.OFFICER) return tr == Clan.Role.MEMBER;
        return false;
    }

    static void fill(Inventory inv) {
        ItemStack f = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
    }

    static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    static ItemStack head(String name, Clan.Role role, List<Component> lore, UUID owner) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) is.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            meta.displayName(Component.text(name, NamedTextColor.YELLOW)
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(roleComp(role))
                    .decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    static Component roleComp(Clan.Role r) {
        if (r == null) return Component.text("?", NamedTextColor.GRAY);
        switch (r) {
            case LEADER: return Component.text("Chef", NamedTextColor.GOLD);
            case OFFICER: return Component.text("Bras droit", NamedTextColor.AQUA);
            default: return Component.text("Membre", NamedTextColor.WHITE);
        }
    }
}

/* ===================== CLICS GUI ===================== */
class GUIListener implements Listener {
    private final ClanPlugin plugin;
    GUIListener(ClanPlugin plugin) { this.plugin = plugin; }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof ClanMenuHolder || h instanceof MemberMenuHolder) e.setCancelled(true);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof ClanMenuHolder) && !(h instanceof MemberMenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getClickedInventory() == null) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR || it.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        ClanManager m = plugin.getClanManager();
        if (h instanceof ClanMenuHolder) {
            ClanMenuHolder holder = (ClanMenuHolder) h;
            Clan clan = m.getClan(holder.getClanName());
            if (clan == null) { p.closeInventory(); return; }
            UUID target = holder.getSlotToMember().get(e.getSlot());
            if (target == null || !ClanGUI.canManage(clan, p.getUniqueId(), target)) return;
            ClanGUI.openMember(plugin, p, clan, target);
        } else {
            MemberMenuHolder holder = (MemberMenuHolder) h;
            Clan clan = m.getClan(holder.getClanName());
            if (clan == null) { p.closeInventory(); return; }
            UUID target = holder.getTarget();
            int slot = e.getSlot();

            if (slot == ClanGUI.BACK_SLOT) { ClanGUI.openMain(plugin, p, clan); return; }

            if (slot == ClanGUI.KICK_SLOT) {
                String tn = m.getName(target);
                if (m.kick(clan, p.getUniqueId(), target)) {
                    ClanPlugin.send(p, Component.text("Tu as exclu " + tn + " du clan.", NamedTextColor.GREEN));
                    Player k = Bukkit.getPlayer(target);
                    if (k != null) ClanPlugin.send(k, Component.text("Tu as ete exclu du clan " + clan.getName() + ".", NamedTextColor.RED));
                    plugin.broadcast(clan, Component.text(tn + " a ete exclu du clan.", NamedTextColor.GRAY));
                    ClanGUI.openMain(plugin, p, clan);
                } else ClanPlugin.send(p, Component.text("Impossible d'exclure ce membre.", NamedTextColor.RED));
                return;
            }

            if (slot == ClanGUI.ROLE_SLOT) {
                Clan.Role r = clan.getRole(target);
                String tn = m.getName(target);
                if (r == Clan.Role.MEMBER) {
                    if (m.promote(clan, p.getUniqueId(), target)) {
                        ClanPlugin.send(p, Component.text("Tu as promu " + tn + " bras droit.", NamedTextColor.GREEN));
                        Player t = Bukkit.getPlayer(target);
                        if (t != null) ClanPlugin.send(t, Component.text("Tu es maintenant bras droit du clan !", NamedTextColor.GREEN));
                    }
                } else if (r == Clan.Role.OFFICER) {
                    if (m.demote(clan, p.getUniqueId(), target)) {
                        ClanPlugin.send(p, Component.text("Tu as repasse " + tn + " membre simple.", NamedTextColor.YELLOW));
                        Player t = Bukkit.getPlayer(target);
                        if (t != null) ClanPlugin.send(t, Component.text("Tu es de nouveau membre du clan.", NamedTextColor.GRAY));
                    }
                }
                ClanGUI.openMember(plugin, p, clan, target);
            }
        }
    }
}

/* ===================== COMMANDE ===================== */
class ClanCommand extends Command {
    private final ClanPlugin plugin;
    private final ClanManager m;

    ClanCommand(ClanPlugin plugin) {
        super("clan", "Systeme de clans", "/clan", List.of("clans", "guilde"));
        this.plugin = plugin;
        this.m = plugin.getClanManager();
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) { ClanPlugin.send(sender, red("Joueurs uniquement.")); return true; }
            Player p = (Player) sender;
            Clan clan = m.getClanOf(p.getUniqueId());
            if (clan == null) { ClanPlugin.send(p, red("Tu n'es dans aucun clan. /clan create <nom>")); return true; }
            ClanGUI.openMain(plugin, p, clan);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create": case "creer": return create(sender, args);
            case "invite": case "inviter": return invite(sender, args);
            case "accept": case "accepter": return accept(sender, args);
            case "deny": case "refuser": return deny(sender, args);
            case "leave": case "quitter": return leave(sender);
            case "dissoudre": case "disband": return disband(sender, args);
            case "info": return info(sender, args);
            default: help(sender); return true;
        }
    }

    private static Component red(String s) { return Component.text(s, NamedTextColor.RED); }
    private static Component green(String s) { return Component.text(s, NamedTextColor.GREEN); }
    private static Component gray(String s) { return Component.text(s, NamedTextColor.GRAY); }
    private static Component yellow(String s) { return Component.text(s, NamedTextColor.YELLOW); }

    private boolean create(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { ClanPlugin.send(s, red("Joueurs uniquement.")); return true; }
        Player p = (Player) s;
        if (a.length < 2) { ClanPlugin.send(p, red("Usage: /clan create <nom>")); return true; }
        switch (m.createClan(p, a[1])) {
            case SUCCESS: ClanPlugin.send(p, green("Clan " + a[1] + " cree ! Tu en es le chef.")); break;
            case ALREADY_IN_CLAN: ClanPlugin.send(p, red("Tu es deja dans un clan.")); break;
            case NAME_TAKEN: ClanPlugin.send(p, red("Nom deja pris.")); break;
            case INVALID_NAME: ClanPlugin.send(p, red("Nom invalide (3-16 : lettres, chiffres, _).")); break;
        }
        return true;
    }

    private boolean invite(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { ClanPlugin.send(s, red("Joueurs uniquement.")); return true; }
        Player p = (Player) s;
        if (a.length < 2) { ClanPlugin.send(p, red("Usage: /clan invite <joueur>")); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { ClanPlugin.send(p, red("Joueur non connecte.")); return true; }
        Clan clan = m.getClanOf(p.getUniqueId());
        switch (m.invite(p, t)) {
            case SUCCESS:
                ClanPlugin.send(p, green("Tu as invite " + t.getName() + " dans le clan."));
                ClanPlugin.send(t, yellow("Invitation dans le clan " + clan.getName() + " !"));
                ClanPlugin.send(t, gray("/clan accept " + clan.getName() + " ou /clan deny " + clan.getName()));
                break;
            case NO_CLAN: ClanPlugin.send(p, red("Tu n'es dans aucun clan.")); break;
            case NO_PERMISSION: ClanPlugin.send(p, red("Seul le chef ou un bras droit peut inviter.")); break;
            case CLAN_FULL: ClanPlugin.send(p, red("Clan plein (" + ClanPlugin.MAX_MEMBERS + " max).")); break;
            case TARGET_IN_CLAN: ClanPlugin.send(p, red("Ce joueur est deja dans un clan.")); break;
            case ALREADY_INVITED: ClanPlugin.send(p, red("Invitation deja en attente.")); break;
            case TARGET_SELF: ClanPlugin.send(p, red("Tu ne peux pas t'inviter.")); break;
        }
        return true;
    }

    private boolean accept(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { ClanPlugin.send(s, red("Joueurs uniquement.")); return true; }
        Player p = (Player) s;
        String name = a.length >= 2 ? a[1] : null;
        Set<String> pend = m.getInvites(p.getUniqueId());
        if (pend.isEmpty()) { ClanPlugin.send(p, red("Aucune invitation.")); return true; }
        if (name == null && pend.size() > 1) {
            ClanPlugin.send(p, red("Plusieurs invitations. /clan accept <nom>"));
            ClanPlugin.send(p, gray(String.join(", ", pend)));
            return true;
        }
        switch (m.accept(p, name)) {
            case SUCCESS:
                Clan clan = m.getClanOf(p.getUniqueId());
                ClanPlugin.send(p, green("Tu as rejoint le clan " + clan.getName() + " !"));
                plugin.broadcast(clan, green(p.getName() + " a rejoint le clan !"));
                break;
            case NO_INVITE: ClanPlugin.send(p, red("Aucune invitation pour ce clan.")); break;
            case CLAN_GONE: ClanPlugin.send(p, red("Ce clan n'existe plus.")); break;
            case CLAN_FULL: ClanPlugin.send(p, red("Clan plein.")); break;
            case ALREADY_IN_CLAN: ClanPlugin.send(p, red("Tu es deja dans un clan.")); break;
        }
        return true;
    }

    private boolean deny(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { ClanPlugin.send(s, red("Joueurs uniquement.")); return true; }
        Player p = (Player) s;
        ClanPlugin.send(p, m.deny(p, a.length >= 2 ? a[1] : null) ? gray("Invitation refusee.") : red("Aucune invitation."));
        return true;
    }

    private boolean leave(CommandSender s) {
        if (!(s instanceof Player)) { ClanPlugin.send(s, red("Joueurs uniquement.")); return true; }
        Player p = (Player) s;
        Clan clan = m.getClanOf(p.getUniqueId());
        if (clan == null) { ClanPlugin.send(p, red("Tu n'es dans aucun clan.")); return true; }
        if (clan.isLeader(p.getUniqueId())) { ClanPlugin.send(p, red("Tu es le chef. /clan dissoudre " + clan.getName())); return true; }
        m.leaveClan(clan, p.getUniqueId());
        ClanPlugin.send(p, gray("Tu as quitte le clan."));
        plugin.broadcast(clan, gray(p.getName() + " a quitte le clan."));
        return true;
    }

    private boolean disband(CommandSender s, String[] a) {
        boolean staff = s.hasPermission("clan.staff");
        Clan target;
        if (a.length >= 2) {
            target = m.getClan(a[1]);
            if (target == null) { ClanPlugin.send(s, red("Aucun clan nomme " + a[1] + ".")); return true; }
            if (!staff && (!(s instanceof Player) || !target.isLeader(((Player) s).getUniqueId()))) {
                ClanPlugin.send(s, red("Pas la permission.")); return true;
            }
        } else {
            if (!(s instanceof Player)) { ClanPlugin.send(s, red("Usage: /clan dissoudre <nom>")); return true; }
            target = m.getClanOf(((Player) s).getUniqueId());
            if (target == null || !target.isLeader(((Player) s).getUniqueId())) { ClanPlugin.send(s, red("Usage: /clan dissoudre <nom>")); return true; }
        }
        String name = target.getName();
        plugin.broadcast(target, red("Le clan " + name + " a ete dissous."));
        m.disband(target);
        ClanPlugin.send(s, green("Clan " + name + " dissous."));
        return true;
    }

    private boolean info(CommandSender s, String[] a) {
        if (!s.hasPermission("clan.staff")) { ClanPlugin.send(s, red("Permission clan.staff requise.")); return true; }
        if (a.length < 2) { ClanPlugin.send(s, red("Usage: /clan info <joueur|clan>")); return true; }
        Clan clan = m.getClan(a[1]);
        if (clan != null) { clanInfo(s, clan); return true; }
        UUID u = m.getUUIDByName(a[1]);
        if (u == null) { ClanPlugin.send(s, red("Aucun clan ni joueur nomme " + a[1] + ".")); return true; }
        Clan pc = m.getClanOf(u);
        if (pc == null) { ClanPlugin.send(s, gray(m.getName(u) + " n'est dans aucun clan.")); return true; }
        ClanPlugin.send(s, yellow(m.getName(u) + " -> clan " + pc.getName()));
        clanInfo(s, pc);
        return true;
    }

    private void clanInfo(CommandSender s, Clan clan) {
        s.sendMessage(Component.text("Clan: " + clan.getName() + " (" + clan.size() + "/" + ClanPlugin.MAX_MEMBERS + ")", NamedTextColor.GOLD));
        s.sendMessage(gray("Chef: " + m.getName(clan.getLeader())));
        List<String> off = new ArrayList<>();
        for (UUID u : clan.getOfficers()) off.add(m.getName(u));
        s.sendMessage(gray("Bras droits: " + (off.isEmpty() ? "aucun" : String.join(", ", off))));
        List<String> mem = new ArrayList<>();
        for (UUID u : clan.getMembers()) if (!clan.isLeader(u) && !clan.isOfficer(u)) mem.add(m.getName(u));
        s.sendMessage(gray("Membres: " + (mem.isEmpty() ? "aucun" : String.join(", ", mem))));
    }

    private void help(CommandSender s) {
        s.sendMessage(Component.text("Clans - Aide", NamedTextColor.GOLD, TextDecoration.BOLD));
        s.sendMessage(yellow("/clan - Menu du clan"));
        s.sendMessage(yellow("/clan create <nom>"));
        s.sendMessage(yellow("/clan invite <joueur> (max " + ClanPlugin.MAX_MEMBERS + ")"));
        s.sendMessage(yellow("/clan accept [nom]  |  /clan deny [nom]"));
        s.sendMessage(yellow("/clan leave"));
        if (s.hasPermission("clan.staff")) {
            s.sendMessage(red("/clan dissoudre <nom> (staff)"));
            s.sendMessage(red("/clan info <joueur|clan> (staff)"));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender s, String alias, String[] a) {
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "invite", "accept", "deny", "leave", "help"));
            if (s.hasPermission("clan.staff")) { subs.add("dissoudre"); subs.add("info"); }
            else if (s instanceof Player) {
                Clan cl = m.getClanOf(((Player) s).getUniqueId());
                if (cl != null && cl.isLeader(((Player) s).getUniqueId())) subs.add("dissoudre");
            }
            return filter(subs, a[0]);
        }
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "invite": {
                    List<String> l = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
                    return filter(l, a[1]);
                }
                case "accept": case "deny":
                    if (s instanceof Player) return filter(new ArrayList<>(m.getInvites(((Player) s).getUniqueId())), a[1]);
                    return Collections.emptyList();
                case "dissoudre": case "info":
                    if (s.hasPermission("clan.staff")) {
                        List<String> l = new ArrayList<>();
                        for (Clan cl : m.getClans()) l.add(cl.getName());
                        return filter(l, a[1]);
                    }
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> opts, String start) {
        String x = start.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(x)) out.add(o);
        return out;
    }
}

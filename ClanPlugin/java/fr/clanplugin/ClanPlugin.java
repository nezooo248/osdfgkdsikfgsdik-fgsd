package fr.clan;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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

public class ClanPlugin extends JavaPlugin {

    static final int MAX_MEMBERS = 5;
    static final String PREFIX = "&6&lClans &8» &r";

    private ClanManager clanManager;

    @Override
    public void onEnable() {
        this.clanManager = new ClanManager(this);
        this.clanManager.load();

        PluginCommand cmd = getCommand("clan");
        if (cmd != null) {
            ClanCommand ex = new ClanCommand(this);
            cmd.setExecutor(ex);
            cmd.setTabCompleter(ex);
        }

        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Placeholder chargé par reflection : le code principal ne dépend PAS de PlaceholderAPI.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                Class<?> clazz = Class.forName("fr.clan.ClanExpansion");
                Object exp = clazz.getConstructor(ClanPlugin.class).newInstance(this);
                clazz.getMethod("register").invoke(exp);
                getLogger().info("Placeholder %clan_name% active.");
            } catch (ClassNotFoundException e) {
                getLogger().info("ClanExpansion absent : %clan_name% desactive (ajoute le fichier si tu veux les placeholders).");
            } catch (Throwable t) {
                getLogger().warning("Placeholders KO : " + t.getMessage());
            }
        }

        getLogger().info("ClanPlugin active.");
    }

    @Override
    public void onDisable() {
        if (clanManager != null) clanManager.save();
    }

    public ClanManager getClanManager() { return clanManager; }

    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    void msg(CommandSender s, String m) { s.sendMessage(c(PREFIX + m)); }
    void broadcast(Clan clan, String m) {
        for (UUID u : clan.getMembers()) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.sendMessage(c(PREFIX + m));
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

    ClanManager(ClanPlugin plugin) { this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "clans.yml"); }

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
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = cfg.getConfigurationSection("clans");
        if (cs != null) {
            for (String name : cs.getKeys(false)) {
                String path = "clans." + name;
                String ls = cfg.getString(path + ".leader");
                if (ls == null) continue;
                try {
                    Clan clan = new Clan(name, UUID.fromString(ls));
                    for (String m : cfg.getStringList(path + ".members")) clan.addMember(UUID.fromString(m));
                    for (String o : cfg.getStringList(path + ".officers")) clan.getOfficers().add(UUID.fromString(o));
                    clans.put(name.toLowerCase(Locale.ROOT), clan);
                    for (UUID u : clan.getMembers()) playerClan.put(u, name.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Clan invalide ignore : " + name);
                }
            }
        }
        ConfigurationSection ns = cfg.getConfigurationSection("names");
        if (ns != null) for (String k : ns.getKeys(false)) {
            try { names.put(UUID.fromString(k), ns.getString(k)); } catch (IllegalArgumentException ignored) {}
        }
    }

    void save() {
        YamlConfiguration cfg = new YamlConfiguration();
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

    static void openMain(ClanPlugin plugin, Player viewer, Clan clan) {
        ClanManager m = plugin.getClanManager();
        ClanMenuHolder holder = new ClanMenuHolder(clan.getName());
        Inventory inv = Bukkit.createInventory(holder, 27, ClanPlugin.c("&6Clan: &e" + clan.getName()));
        holder.setInventory(inv);
        fill(inv);

        List<String> info = new ArrayList<>();
        info.add(ClanPlugin.c("&7Chef: &f" + m.getName(clan.getLeader())));
        info.add(ClanPlugin.c("&7Membres: &f" + clan.size() + "&7/&f" + ClanPlugin.MAX_MEMBERS));
        info.add(ClanPlugin.c("&7Bras droits: &f" + clan.getOfficers().size()));
        inv.setItem(INFO_SLOT, item(Material.OAK_SIGN, "&6&lClan " + clan.getName(), info));

        boolean canManage = clan.isLeader(viewer.getUniqueId()) || clan.isOfficer(viewer.getUniqueId());
        List<UUID> ordered = new ArrayList<>();
        ordered.add(clan.getLeader());
        for (UUID u : clan.getOfficers()) if (!ordered.contains(u)) ordered.add(u);
        for (UUID u : clan.getMembers()) if (!ordered.contains(u)) ordered.add(u);

        int i = 0;
        for (UUID u : ordered) {
            if (i >= MEMBER_SLOTS.length) break;
            int slot = MEMBER_SLOTS[i];
            List<String> lore = new ArrayList<>();
            lore.add(ClanPlugin.c("&7Grade: " + role(clan.getRole(u))));
            if (canManage && canManage(clan, viewer.getUniqueId(), u)) {
                lore.add(ClanPlugin.c("&8"));
                lore.add(ClanPlugin.c("&eClic &7pour gerer ce membre"));
            }
            inv.setItem(slot, head(m.getName(u), role(clan.getRole(u)), lore, u));
            holder.getSlotToMember().put(slot, u);
            i++;
        }
        viewer.openInventory(inv);
    }

    static void openMember(ClanPlugin plugin, Player viewer, Clan clan, UUID target) {
        ClanManager m = plugin.getClanManager();
        MemberMenuHolder holder = new MemberMenuHolder(clan.getName(), target);
        Inventory inv = Bukkit.createInventory(holder, 27, ClanPlugin.c("&6Gerer: &e" + m.getName(target)));
        holder.setInventory(inv);
        fill(inv);

        Clan.Role tr = clan.getRole(target);
        boolean leader = clan.isLeader(viewer.getUniqueId());
        boolean officer = clan.isOfficer(viewer.getUniqueId());

        List<String> hl = new ArrayList<>();
        hl.add(ClanPlugin.c("&7Grade: " + role(tr)));
        inv.setItem(HEAD_SLOT, head(m.getName(target), role(tr), hl, target));

        boolean canKick = (leader && tr != Clan.Role.LEADER) || (officer && tr == Clan.Role.MEMBER);
        if (canKick) inv.setItem(KICK_SLOT, item(Material.BARRIER, "&c&lExclure",
                List.of(ClanPlugin.c("&7Retire ce joueur du clan."))));

        if (leader) {
            if (tr == Clan.Role.MEMBER)
                inv.setItem(ROLE_SLOT, item(Material.EMERALD, "&a&lPromouvoir bras droit",
                        List.of(ClanPlugin.c("&7Peut inviter et exclure des membres."))));
            else if (tr == Clan.Role.OFFICER)
                inv.setItem(ROLE_SLOT, item(Material.GRAY_DYE, "&e&lRepasser membre",
                        List.of(ClanPlugin.c("&7Retire le grade de bras droit."))));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW, "&7Retour", List.of()));
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
        ItemStack f = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
    }

    static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ClanPlugin.c(name));
            if (!lore.isEmpty()) meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    static ItemStack head(String name, String roleColored, List<String> lore, UUID owner) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) is.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            meta.setDisplayName(ClanPlugin.c("&e" + name + " &7- " + roleColored));
            if (!lore.isEmpty()) meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    static String role(Clan.Role r) {
        if (r == null) return "&7?";
        switch (r) {
            case LEADER: return "&6Chef";
            case OFFICER: return "&bBras droit";
            default: return "&fMembre";
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
                    plugin.msg(p, "&aTu as exclu &e" + tn + " &adu clan.");
                    Player k = Bukkit.getPlayer(target);
                    if (k != null) k.sendMessage(ClanPlugin.c(ClanPlugin.PREFIX + "&cTu as ete exclu du clan &e" + clan.getName() + "&c."));
                    plugin.broadcast(clan, "&e" + tn + " &7a ete exclu du clan.");
                    ClanGUI.openMain(plugin, p, clan);
                } else plugin.msg(p, "&cImpossible d'exclure ce membre.");
                return;
            }

            if (slot == ClanGUI.ROLE_SLOT) {
                Clan.Role r = clan.getRole(target);
                String tn = m.getName(target);
                if (r == Clan.Role.MEMBER) {
                    if (m.promote(clan, p.getUniqueId(), target)) {
                        plugin.msg(p, "&aTu as promu &e" + tn + " &abras droit.");
                        Player t = Bukkit.getPlayer(target);
                        if (t != null) t.sendMessage(ClanPlugin.c(ClanPlugin.PREFIX + "&aTu es maintenant &bbras droit &adu clan !"));
                    }
                } else if (r == Clan.Role.OFFICER) {
                    if (m.demote(clan, p.getUniqueId(), target)) {
                        plugin.msg(p, "&eTu as repasse &f" + tn + " &emembre simple.");
                        Player t = Bukkit.getPlayer(target);
                        if (t != null) t.sendMessage(ClanPlugin.c(ClanPlugin.PREFIX + "&7Tu es de nouveau &fmembre &7du clan."));
                    }
                }
                ClanGUI.openMember(plugin, p, clan, target);
            }
        }
    }
}

/* ===================== COMMANDE ===================== */
class ClanCommand implements CommandExecutor, TabCompleter {
    private final ClanPlugin plugin;
    private final ClanManager m;
    ClanCommand(ClanPlugin plugin) { this.plugin = plugin; this.m = plugin.getClanManager(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) { plugin.msg(sender, "&cJoueurs uniquement."); return true; }
            Player p = (Player) sender;
            Clan clan = m.getClanOf(p.getUniqueId());
            if (clan == null) { plugin.msg(p, "&cTu n'es dans aucun clan. &e/clan create <nom>"); return true; }
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

    private boolean create(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { plugin.msg(s, "&cJoueurs uniquement."); return true; }
        Player p = (Player) s;
        if (a.length < 2) { plugin.msg(p, "&cUsage: &e/clan create <nom>"); return true; }
        switch (m.createClan(p, a[1])) {
            case SUCCESS: plugin.msg(p, "&aClan &e" + a[1] + " &acree ! Tu en es le chef."); break;
            case ALREADY_IN_CLAN: plugin.msg(p, "&cTu es deja dans un clan."); break;
            case NAME_TAKEN: plugin.msg(p, "&cNom deja pris."); break;
            case INVALID_NAME: plugin.msg(p, "&cNom invalide (3-16 : lettres, chiffres, _)."); break;
        }
        return true;
    }

    private boolean invite(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { plugin.msg(s, "&cJoueurs uniquement."); return true; }
        Player p = (Player) s;
        if (a.length < 2) { plugin.msg(p, "&cUsage: &e/clan invite <joueur>"); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { plugin.msg(p, "&cJoueur non connecte."); return true; }
        Clan clan = m.getClanOf(p.getUniqueId());
        switch (m.invite(p, t)) {
            case SUCCESS:
                plugin.msg(p, "&aTu as invite &e" + t.getName() + " &adans le clan.");
                plugin.msg(t, "&eInvitation dans le clan &6" + clan.getName() + "&e !");
                t.sendMessage(ClanPlugin.c(ClanPlugin.PREFIX + "&7&a/clan accept " + clan.getName() + " &7ou &c/clan deny " + clan.getName()));
                break;
            case NO_CLAN: plugin.msg(p, "&cTu n'es dans aucun clan."); break;
            case NO_PERMISSION: plugin.msg(p, "&cSeul le chef ou un bras droit peut inviter."); break;
            case CLAN_FULL: plugin.msg(p, "&cClan plein (" + ClanPlugin.MAX_MEMBERS + " max)."); break;
            case TARGET_IN_CLAN: plugin.msg(p, "&cCe joueur est deja dans un clan."); break;
            case ALREADY_INVITED: plugin.msg(p, "&cInvitation deja en attente."); break;
            case TARGET_SELF: plugin.msg(p, "&cTu ne peux pas t'inviter."); break;
        }
        return true;
    }

    private boolean accept(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { plugin.msg(s, "&cJoueurs uniquement."); return true; }
        Player p = (Player) s;
        String name = a.length >= 2 ? a[1] : null;
        Set<String> pend = m.getInvites(p.getUniqueId());
        if (pend.isEmpty()) { plugin.msg(p, "&cAucune invitation."); return true; }
        if (name == null && pend.size() > 1) {
            plugin.msg(p, "&cPlusieurs invitations. &e/clan accept <nom>");
            plugin.msg(p, "&7" + String.join(", ", pend));
            return true;
        }
        switch (m.accept(p, name)) {
            case SUCCESS:
                Clan clan = m.getClanOf(p.getUniqueId());
                plugin.msg(p, "&aTu as rejoint le clan &e" + clan.getName() + " &a!");
                plugin.broadcast(clan, "&e" + p.getName() + " &aa rejoint le clan !");
                break;
            case NO_INVITE: plugin.msg(p, "&cAucune invitation pour ce clan."); break;
            case CLAN_GONE: plugin.msg(p, "&cCe clan n'existe plus."); break;
            case CLAN_FULL: plugin.msg(p, "&cClan plein."); break;
            case ALREADY_IN_CLAN: plugin.msg(p, "&cTu es deja dans un clan."); break;
        }
        return true;
    }

    private boolean deny(CommandSender s, String[] a) {
        if (!(s instanceof Player)) { plugin.msg(s, "&cJoueurs uniquement."); return true; }
        Player p = (Player) s;
        plugin.msg(p, m.deny(p, a.length >= 2 ? a[1] : null) ? "&7Invitation refusee." : "&cAucune invitation.");
        return true;
    }

    private boolean leave(CommandSender s) {
        if (!(s instanceof Player)) { plugin.msg(s, "&cJoueurs uniquement."); return true; }
        Player p = (Player) s;
        Clan clan = m.getClanOf(p.getUniqueId());
        if (clan == null) { plugin.msg(p, "&cTu n'es dans aucun clan."); return true; }
        if (clan.isLeader(p.getUniqueId())) { plugin.msg(p, "&cTu es le chef. &e/clan dissoudre " + clan.getName()); return true; }
        m.leaveClan(clan, p.getUniqueId());
        plugin.msg(p, "&7Tu as quitte le clan.");
        plugin.broadcast(clan, "&e" + p.getName() + " &7a quitte le clan.");
        return true;
    }

    private boolean disband(CommandSender s, String[] a) {
        boolean staff = s.hasPermission("clan.staff");
        Clan target;
        if (a.length >= 2) {
            target = m.getClan(a[1]);
            if (target == null) { plugin.msg(s, "&cAucun clan nomme &e" + a[1] + "&c."); return true; }
            if (!staff && (!(s instanceof Player) || !target.isLeader(((Player) s).getUniqueId()))) {
                plugin.msg(s, "&cPas la permission."); return true;
            }
        } else {
            if (!(s instanceof Player)) { plugin.msg(s, "&cUsage: &e/clan dissoudre <nom>"); return true; }
            target = m.getClanOf(((Player) s).getUniqueId());
            if (target == null || !target.isLeader(((Player) s).getUniqueId())) { plugin.msg(s, "&cUsage: &e/clan dissoudre <nom>"); return true; }
        }
        String name = target.getName();
        plugin.broadcast(target, "&cLe clan &e" + name + " &ca ete dissous.");
        m.disband(target);
        plugin.msg(s, "&aClan &e" + name + " &adissous.");
        return true;
    }

    private boolean info(CommandSender s, String[] a) {
        if (!s.hasPermission("clan.staff")) { plugin.msg(s, "&cPermission &eclan.staff&c requise."); return true; }
        if (a.length < 2) { plugin.msg(s, "&cUsage: &e/clan info <joueur|clan>"); return true; }
        Clan clan = m.getClan(a[1]);
        if (clan != null) { clanInfo(s, clan); return true; }
        UUID u = m.getUUIDByName(a[1]);
        if (u == null) { plugin.msg(s, "&cAucun clan ni joueur nomme &e" + a[1] + "&c."); return true; }
        Clan pc = m.getClanOf(u);
        if (pc == null) { plugin.msg(s, "&e" + m.getName(u) + " &7n'est dans aucun clan."); return true; }
        plugin.msg(s, "&e" + m.getName(u) + " &7-> clan &6" + pc.getName() + " &7(" + ClanGUI.role(pc.getRole(u)) + "&7)");
        clanInfo(s, pc);
        return true;
    }

    private void clanInfo(CommandSender s, Clan clan) {
        s.sendMessage(ClanPlugin.c("&8&m--------------------------------"));
        s.sendMessage(ClanPlugin.c("&6&lClan: &e" + clan.getName() + " &7(" + clan.size() + "/" + ClanPlugin.MAX_MEMBERS + ")"));
        s.sendMessage(ClanPlugin.c("&7Chef: &f" + m.getName(clan.getLeader())));
        List<String> off = new ArrayList<>();
        for (UUID u : clan.getOfficers()) off.add(m.getName(u));
        s.sendMessage(ClanPlugin.c("&7Bras droits: &f" + (off.isEmpty() ? "aucun" : String.join(", ", off))));
        List<String> mem = new ArrayList<>();
        for (UUID u : clan.getMembers()) if (!clan.isLeader(u) && !clan.isOfficer(u)) mem.add(m.getName(u));
        s.sendMessage(ClanPlugin.c("&7Membres: &f" + (mem.isEmpty() ? "aucun" : String.join(", ", mem))));
        s.sendMessage(ClanPlugin.c("&8&m--------------------------------"));
    }

    private void help(CommandSender s) {
        s.sendMessage(ClanPlugin.c("&6&lClans &7- Aide"));
        s.sendMessage(ClanPlugin.c("&e/clan &7- Menu du clan"));
        s.sendMessage(ClanPlugin.c("&e/clan create <nom>"));
        s.sendMessage(ClanPlugin.c("&e/clan invite <joueur> &7(max " + ClanPlugin.MAX_MEMBERS + ")"));
        s.sendMessage(ClanPlugin.c("&e/clan accept [nom] &7- &e/clan deny [nom]"));
        s.sendMessage(ClanPlugin.c("&e/clan leave"));
        if (s.hasPermission("clan.staff")) {
            s.sendMessage(ClanPlugin.c("&c/clan dissoudre <nom> &7(staff)"));
            s.sendMessage(ClanPlugin.c("&c/clan info <joueur|clan> &7(staff)"));
        }
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String al, String[] a) {
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
                case "dissoudre": case "info": {
                    if (s.hasPermission("clan.staff")) {
                        List<String> l = new ArrayList<>();
                        for (Clan cl : m.getClans()) l.add(cl.getName());
                        return filter(l, a[1]);
                    }
                    return Collections.emptyList();
                }
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

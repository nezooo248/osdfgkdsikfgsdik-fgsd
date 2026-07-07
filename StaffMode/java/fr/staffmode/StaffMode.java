package fr.staffmode;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff mode pour Optaland.
 *
 * Permission : staffmode.use.optaland
 *
 * /staff       -> active / desactive : GOD + FLY (interne) ; l'inventaire est VIDE,
 *                 mis de cote et sauvegarde sur disque, puis restaure a la sortie.
 * /staff chat  -> chat staff
 * /tp <joueur> -> tp au joueur (en mode staff) sinon /tp vanilla
 * /survie      -> survie
 *
 * ROLE (LuckPerms) : hors staff -> "default" ; en staff -> role d'avant. Configurable.
 *
 * Deconnexion en mode staff : l'inventaire n'est PAS restaure en direct (une modif
 * d'inventaire au QuitEvent n'est pas toujours ecrite). On le garde sur disque et on
 * le rend a la reconnexion, comme apres un crash.
 */
public class StaffMode extends LoadedPlugin implements Listener {

    static final String PERM = "staffmode.use.optaland";

    private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();

    // Inventaires mis de cote pendant le mode staff.
    private final Map<UUID, ItemStack[]> savedInv   = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack>   savedOff   = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRestore = ConcurrentHashMap.newKeySet();

    private final Map<UUID, String> previousRole = new ConcurrentHashMap<>();
    private final Set<UUID> staffMembers = ConcurrentHashMap.newKeySet();
    private boolean roleEnabled = true;
    private String  defaultRole = "default";
    private boolean luckPermsPresent = false;

    private final Set<String> myCommands = new HashSet<>();
    private File file;
    private File backupFile;
    private File configFile;

    @Override
    public void onEnable() {
        this.file = new File(getHost().getDataFolder(), "staffmode.yml");
        this.backupFile = new File(getHost().getDataFolder(), "inv-backups.yml");
        this.configFile = new File(getHost().getDataFolder(), "configstaff.yml");
        loadConfig();
        this.luckPermsPresent = hasLuckPerms();
        loadPending();
        unregisterStaleListeners();
        registerListener(this);
        registerStaffCommand();
        syncCommands();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pendingRestore.contains(p.getUniqueId())) restoreFromPending(p);
        }
        getLogger().info("[StaffMode] active." + (roleEnabled && !luckPermsPresent
                ? " (role active mais LuckPerms introuvable -> gestion de role ignoree)" : ""));
    }

    @Override
    public void onDisable() {
        try {
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (staffMode.contains(p.getUniqueId())) exitStaff(p, true, false);
            }
            try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}

            CommandMap map = getCommandMap();
            Map<String, Command> known = (map != null) ? knownCommands(map) : null;
            if (known != null) {
                for (String label : myCommands) {
                    Command c = known.remove(label);
                    if (c != null) { try { c.unregister(map); } catch (Throwable ignored) {} }
                    known.remove("staffmode:" + label);
                }
            }
            myCommands.clear();
            syncCommands();
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] erreur onDisable : " + t);
        }
        persist();
        getLogger().info("[StaffMode] desactive.");
    }

    /** Retire tout listener StaffMode fantome laisse par un /plreload precedent. */
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
            "#            StaffMode - Optaland\n" +
            "# ============================================\n" +
            "\n" +
            "role:\n" +
            "  # true  = le plugin gere le role (LuckPerms) automatiquement.\n" +
            "  # false = le plugin ne touche jamais aux roles.\n" +
            "  enabled: true\n" +
            "  default: \"default\"\n";

    private void loadConfig() {
        roleEnabled = true; defaultRole = "default";
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (!configFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) { w.write(DEFAULT_CONFIG); }
                getLogger().info("[StaffMode] configstaff.yml cree.");
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            boolean changed = false;
            if (!cfg.contains("role.enabled")) { cfg.set("role.enabled", true); changed = true; }
            if (!cfg.contains("role.default")) { cfg.set("role.default", "default"); changed = true; }
            roleEnabled = cfg.getBoolean("role.enabled", true);
            defaultRole = cfg.getString("role.default", "default");
            if (changed) cfg.save(configFile);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] config KO : " + t.getMessage());
        }
    }

    // ===================== ROLE (LuckPerms) =====================

    private boolean hasLuckPerms() {
        try { return Bukkit.getPluginManager().getPlugin("LuckPerms") != null; }
        catch (Throwable t) { return false; }
    }

    private String getPrimaryGroup(Player p) {
        try {
            Object lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
            if (user == null) return null;
            Object grp = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return grp != null ? grp.toString() : null;
        } catch (Throwable t) { return null; }
    }

    private void setRole(Player p, String group) {
        if (group == null || group.isEmpty()) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + p.getUniqueId() + " parent set " + group);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] changement de role KO pour " + p.getName() + " : " + t.getMessage());
        }
    }

    private void applyRoleOnEnter(Player p) {
        if (!roleEnabled || !luckPermsPresent) return;
        String prev = previousRole.get(p.getUniqueId());
        if (prev != null && !prev.isEmpty()) setRole(p, prev);
    }

    private void applyRoleOnExit(Player p) {
        if (!roleEnabled || !luckPermsPresent) return;
        String current = getPrimaryGroup(p);
        if (current != null && !current.equalsIgnoreCase(defaultRole)) {
            previousRole.put(p.getUniqueId(), current);
        }
        setRole(p, defaultRole);
        persist();
    }

    // ===================== ENREGISTREMENT COMMANDE =====================

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

    private void registerStaffCommand() {
        CommandMap map = getCommandMap();
        if (map == null) { getLogger().warning("[StaffMode] CommandMap introuvable."); return; }
        registerOne(map, "staff", "Mode staff", "/staff", List.of("mod", "staffmode"),
                (s, a) -> handleCommand(s, a), (s, a) -> handleTab(s, a));
        registerOne(map, "tp", "Teleportation staff", "/tp <joueur>", List.of(),
                (s, a) -> handleTp(s, a), (s, a) -> tabPlayers(a));
        registerOne(map, "survie", "Passe en survie", "/survie", List.of("survival", "surv"),
                (s, a) -> handleSurvie(s), null);
    }

    private interface Exec { boolean run(CommandSender s, String[] a); }
    private interface Tab { List<String> run(CommandSender s, String[] a); }

    private void registerOne(CommandMap map, String name, String desc, String usage,
                             List<String> aliases, Exec exec, Tab tab) {
        Command cmd = new Command(name, desc, usage, aliases) {
            @Override public boolean execute(CommandSender s, String l, String[] a) { return exec.run(s, a); }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                return tab != null ? tab.run(s, a) : List.of();
            }
        };
        map.register("staffmode", cmd);
        Map<String, Command> known = knownCommands(map);
        if (known != null) known.put(name, cmd);
        myCommands.add(name);
    }

    private boolean handleSurvie(CommandSender s) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        if (!isStaff(p)) { send(p, "Tu n'as pas la permission " + PERM + ".", NamedTextColor.RED); return true; }
        p.setGameMode(GameMode.SURVIVAL);
        send(p, "Tu es maintenant en SURVIE.", NamedTextColor.GREEN);
        return true;
    }

    private boolean handleTp(CommandSender s, String[] a) {
        if (s instanceof Player p && isStaff(p) && staffMode.contains(p.getUniqueId()) && a.length == 1) {
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) { send(p, "Joueur introuvable : " + a[0], NamedTextColor.RED); return true; }
            if (target.equals(p)) { send(p, "Tu ne peux pas te teleporter a toi-meme.", NamedTextColor.RED); return true; }
            p.teleport(target.getLocation());
            send(p, "Teleporte a " + target.getName() + ".", NamedTextColor.GREEN);
            return true;
        }
        StringBuilder sb = new StringBuilder("minecraft:tp");
        for (String arg : a) sb.append(' ').append(arg);
        Bukkit.dispatchCommand(s, sb.toString());
        return true;
    }

    private List<String> tabPlayers(String[] a) {
        if (a.length != 1) return List.of();
        String x = a[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getName().toLowerCase(Locale.ROOT).startsWith(x)) out.add(o.getName());
        }
        return out;
    }

    private void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.updateCommands(); } catch (Throwable ignored) {}
        }
    }

    static final Component PREFIX = Component.text("Staff ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    static void send(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(PREFIX.append(Component.text(text, color)));
    }

    boolean isStaff(CommandSender s) {
        if (s.hasPermission(PERM)) return true;
        if (s instanceof Player p) return staffMembers.contains(p.getUniqueId());
        return false;
    }

    private boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("chat")) {
            if (sender instanceof Player && !isStaff(sender)) { send(sender, "Pas la permission.", NamedTextColor.RED); return true; }
            if (!(sender instanceof Player) && !isStaff(sender)) { send(sender, "Pas la permission.", NamedTextColor.RED); return true; }
            if (args.length < 2) { send(sender, "Usage: /staff chat <message>", NamedTextColor.RED); return true; }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) { if (i > 1) sb.append(' '); sb.append(args[i]); }
            staffChat(sender, sb.toString());
            return true;
        }
        if (!(sender instanceof Player p)) { send(sender, "Joueurs uniquement (sauf /staff chat).", NamedTextColor.RED); return true; }
        if (!isStaff(p)) { send(p, "Tu n'as pas la permission " + PERM + ".", NamedTextColor.RED); return true; }
        if (args.length == 0) {
            if (staffMode.contains(p.getUniqueId())) exitStaff(p, true, true);
            else enterStaff(p);
            return true;
        }
        send(p, "Usage: /staff  |  /staff chat <message>", NamedTextColor.GRAY);
        return true;
    }

    private void staffChat(CommandSender from, String message) {
        String name = (from instanceof Player) ? from.getName() : "Console";
        Component msg = Component.text("[Staff] ", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                .append(Component.text(name, NamedTextColor.RED))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.GRAY));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission(PERM)) o.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private List<String> handleTab(CommandSender s, String[] a) {
        if (a.length == 1) {
            List<String> out = new ArrayList<>();
            String x = a[0].toLowerCase(Locale.ROOT);
            for (String o : List.of("chat")) if (o.startsWith(x)) out.add(o);
            return out;
        }
        return List.of();
    }

    // ===================== ENTREE / SORTIE STAFF =====================

    private void enterStaff(Player p) {
        UUID u = p.getUniqueId();

        // Sauvegarde de l'inventaire normal.
        ItemStack[] contents = deepClone(p.getInventory().getContents());
        ItemStack[] armorC   = deepClone(p.getInventory().getArmorContents());
        ItemStack off = p.getInventory().getItemInOffHand();
        off = (off == null ? new ItemStack(Material.AIR) : off.clone());

        savedInv.put(u, contents);
        savedArmor.put(u, armorC);
        savedOff.put(u, off);

        staffMembers.add(u);
        applyRoleOnEnter(p);

        persist();
        writeBackup(p, contents, armorC, off);

        // On vide l'inventaire (mis de cote) — aucun item staff donne.
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);

        staffMode.add(u);

        // GOD + FLY en interne.
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFireTicks(0);

        p.updateInventory();
        send(p, "Mode staff ACTIVE. (god + fly)", NamedTextColor.GREEN);
    }

    private void exitStaff(Player p, boolean restore, boolean announce) {
        UUID u = p.getUniqueId();
        staffMode.remove(u);

        p.setFlying(false);
        p.setAllowFlight(false);

        if (restore) restoreInventory(p);

        applyRoleOnExit(p);

        savedInv.remove(u);
        savedArmor.remove(u);
        savedOff.remove(u);
        pendingRestore.remove(u);
        clearBackup(u);
        persist();

        p.updateInventory();
        if (announce) send(p, "Mode staff DESACTIVE.", NamedTextColor.GRAY);
    }

    private void restoreInventory(Player p) {
        UUID u = p.getUniqueId();
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        ItemStack[] inv = savedInv.get(u);
        if (inv != null) p.getInventory().setContents(inv);
        ItemStack[] armor = savedArmor.get(u);
        if (armor != null) p.getInventory().setArmorContents(armor);
        ItemStack off = savedOff.get(u);
        if (off != null) p.getInventory().setItemInOffHand(off);
        p.updateInventory();
    }

    private ItemStack[] deepClone(ItemStack[] src) {
        if (src == null) return null;
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (src[i] == null) ? null : src[i].clone();
        return out;
    }

    // ===================== GOD =====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ===================== JOIN / QUIT =====================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (pendingRestore.contains(p.getUniqueId())) restoreFromPending(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (staffMode.contains(u)) {
            // On ne restaure PAS l'inventaire en direct : on garde tout sur disque et on
            // le rend a la reconnexion (comme apres un crash).
            staffMode.remove(u);
            p.setFlying(false);
            p.setAllowFlight(false);
            applyRoleOnExit(p);
            pendingRestore.add(u);
            persist();
        }
    }

    // ===================== PERSISTANCE =====================

    private void restoreFromPending(Player p) {
        UUID u = p.getUniqueId();
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        ItemStack[] inv = savedInv.get(u);
        if (inv != null) p.getInventory().setContents(inv);
        ItemStack[] armor = savedArmor.get(u);
        if (armor != null) p.getInventory().setArmorContents(armor);
        ItemStack off = savedOff.get(u);
        if (off != null) p.getInventory().setItemInOffHand(off);
        p.updateInventory();

        savedInv.remove(u);
        savedArmor.remove(u);
        savedOff.remove(u);
        pendingRestore.remove(u);
        staffMode.remove(u);
        clearBackup(u);
        persist();
        send(p, "Ton inventaire a ete recupere.", NamedTextColor.YELLOW);
    }

    private void writeBackup(Player p, ItemStack[] contents, ItemStack[] armor, ItemStack off) {
        if (backupFile == null) return;
        YamlConfiguration cfg = backupFile.exists()
                ? YamlConfiguration.loadConfiguration(backupFile) : new YamlConfiguration();
        String path = "backup." + p.getUniqueId();
        cfg.set(path + ".name", p.getName());
        cfg.set(path + ".time", System.currentTimeMillis());
        cfg.set(path + ".contents", Arrays.asList(contents));
        cfg.set(path + ".armor", Arrays.asList(armor));
        if (off != null && off.getType() != Material.AIR) cfg.set(path + ".offhand", off);
        try {
            File dir = backupFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            cfg.save(backupFile);
        } catch (IOException e) {
            getLogger().warning("[StaffMode] backup inventaire KO : " + e.getMessage());
        }
    }

    private void clearBackup(UUID u) {
        if (backupFile == null || !backupFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(backupFile);
        if (cfg.contains("backup." + u)) {
            cfg.set("backup." + u, null);
            try { cfg.save(backupFile); } catch (IOException ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private void loadPending() {
        savedInv.clear(); savedArmor.clear(); savedOff.clear(); pendingRestore.clear();
        previousRole.clear(); staffMembers.clear();
        if (file == null || !file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection cs = cfg.getConfigurationSection("pending");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    List<ItemStack> contents = (List<ItemStack>) (List<?>) cfg.getList("pending." + key + ".contents");
                    List<ItemStack> armor    = (List<ItemStack>) (List<?>) cfg.getList("pending." + key + ".armor");
                    if (contents != null) savedInv.put(u, contents.toArray(new ItemStack[0]));
                    if (armor != null)    savedArmor.put(u, armor.toArray(new ItemStack[0]));
                    ItemStack off = cfg.getItemStack("pending." + key + ".offhand");
                    if (off != null) savedOff.put(u, off);
                    pendingRestore.add(u);
                } catch (Throwable ignored) {}
            }
        }

        ConfigurationSection rs = cfg.getConfigurationSection("roles");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    String role = cfg.getString("roles." + key);
                    if (role != null && !role.isEmpty()) previousRole.put(u, role);
                } catch (Throwable ignored) {}
            }
        }

        for (String s : cfg.getStringList("staffMembers")) {
            try { staffMembers.add(UUID.fromString(s)); } catch (Throwable ignored) {}
        }
    }

    private void persist() {
        if (file == null) return;
        YamlConfiguration cfg = new YamlConfiguration();

        Set<UUID> keys = new HashSet<>();
        keys.addAll(savedInv.keySet());
        keys.addAll(savedArmor.keySet());
        for (UUID u : keys) {
            String path = "pending." + u;
            if (savedInv.get(u) != null)   cfg.set(path + ".contents", Arrays.asList(savedInv.get(u)));
            if (savedArmor.get(u) != null) cfg.set(path + ".armor", Arrays.asList(savedArmor.get(u)));
            if (savedOff.get(u) != null)   cfg.set(path + ".offhand", savedOff.get(u));
        }

        for (Map.Entry<UUID, String> e : previousRole.entrySet()) cfg.set("roles." + e.getKey(), e.getValue());

        List<String> sm = new ArrayList<>();
        for (UUID u : staffMembers) sm.add(u.toString());
        cfg.set("staffMembers", sm);

        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            getLogger().warning("[StaffMode] sauvegarde KO : " + e.getMessage());
        }
    }
}

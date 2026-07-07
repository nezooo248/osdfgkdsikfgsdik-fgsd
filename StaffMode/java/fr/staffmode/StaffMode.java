package fr.staffmode;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

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
 * /staff       -> donne les items staff + lance /fly et /god (au nom du staff)
 * /staff chat  -> chat staff
 * /tp <joueur> -> tp au joueur (en mode staff) sinon /tp vanilla
 * /survie      -> survie
 *
 * Items staff :
 *   - Blaze Rod  -> taper un joueur (aucun degat) lance /invsee <joueur>
 *   - Breeze Rod -> taper un joueur = freeze / unfreeze (interne)
 *   - Gray Dye   -> clic droit lance /sqlvanish
 *
 * Les commandes (/fly /god /sqlvanish /invsee) sont configurables dans configstaff.yml.
 */
public class StaffMode extends LoadedPlugin implements Listener {

    static final String PERM = "staffmode.use.optaland";

    private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen    = ConcurrentHashMap.newKeySet();

    private final Map<UUID, ItemStack[]> savedInv   = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack>   savedOff   = new ConcurrentHashMap<>();

    private final Set<UUID> pendingRestore = ConcurrentHashMap.newKeySet();

    private final Map<UUID, String> previousRole = new ConcurrentHashMap<>();
    private final Set<UUID> staffMembers = ConcurrentHashMap.newKeySet();
    private boolean roleEnabled = true;
    private String  defaultRole = "default";
    private boolean luckPermsPresent = false;

    // Commandes deleguees (configurables).
    private String cmdFly    = "fly";
    private String cmdGod    = "god";
    private String cmdVanish = "sqlvanish";
    private String cmdInvsee = "invsee %player%";

    private final Set<String> myCommands = new HashSet<>();
    private File file;
    private File backupFile;
    private File configFile;

    private static final int SLOT_INV    = 0;
    private static final int SLOT_FREEZE = 1;
    private static final int SLOT_VANISH = 4;

    @Override
    public void onEnable() {
        this.file = new File(getHost().getDataFolder(), "staffmode.yml");
        this.backupFile = new File(getHost().getDataFolder(), "inv-backups.yml");
        this.configFile = new File(getHost().getDataFolder(), "configstaff.yml");
        loadConfig();
        this.luckPermsPresent = hasLuckPerms();
        loadPending();
        unregisterStaleListeners(); // vire les listeners fantomes d'un ancien /plreload
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
            frozen.clear();

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
            "  enabled: true\n" +
            "  default: \"default\"\n" +
            "\n" +
            "# Commandes lancees AU NOM du staff.\n" +
            "# %player% = le joueur vise (pour invsee).\n" +
            "commands:\n" +
            "  fly: \"fly\"\n" +
            "  god: \"god\"\n" +
            "  vanish: \"sqlvanish\"\n" +
            "  invsee: \"invsee %player%\"\n";

    private void loadConfig() {
        roleEnabled = true; defaultRole = "default";
        cmdFly = "fly"; cmdGod = "god"; cmdVanish = "sqlvanish"; cmdInvsee = "invsee %player%";
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (!configFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) { w.write(DEFAULT_CONFIG); }
                getLogger().info("[StaffMode] configstaff.yml cree.");
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            boolean changed = false;
            if (!cfg.contains("role.enabled"))    { cfg.set("role.enabled", true); changed = true; }
            if (!cfg.contains("role.default"))    { cfg.set("role.default", "default"); changed = true; }
            if (!cfg.contains("commands.fly"))    { cfg.set("commands.fly", "fly"); changed = true; }
            if (!cfg.contains("commands.god"))    { cfg.set("commands.god", "god"); changed = true; }
            if (!cfg.contains("commands.vanish")) { cfg.set("commands.vanish", "sqlvanish"); changed = true; }
            if (!cfg.contains("commands.invsee")) { cfg.set("commands.invsee", "invsee %player%"); changed = true; }
            roleEnabled = cfg.getBoolean("role.enabled", true);
            defaultRole = cfg.getString("role.default", "default");
            cmdFly    = cfg.getString("commands.fly", "fly");
            cmdGod    = cfg.getString("commands.god", "god");
            cmdVanish = cfg.getString("commands.vanish", "sqlvanish");
            cmdInvsee = cfg.getString("commands.invsee", "invsee %player%");
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

    // ===================== COMMANDES DELEGUEES =====================

    /** Lance une commande au nom du joueur. */
    private void runAs(Player p, String command) {
        if (command == null || command.isBlank()) return;
        try { Bukkit.dispatchCommand(p, command); }
        catch (Throwable t) { getLogger().warning("[StaffMode] commande KO (" + command + ") : " + t.getMessage()); }
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

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        giveStaffItems(p);

        staffMode.add(u);
        p.updateInventory();

        // Lance /fly et /god au nom du staff (au tick suivant, apres application du role).
        try {
            Bukkit.getScheduler().runTask((Plugin) getHost(), () -> { runAs(p, cmdFly); runAs(p, cmdGod); });
        } catch (Throwable t) {
            runAs(p, cmdFly); runAs(p, cmdGod);
        }

        send(p, "Mode staff ACTIVE.", NamedTextColor.GREEN);
    }

    private void exitStaff(Player p, boolean restore, boolean announce) {
        UUID u = p.getUniqueId();
        staffMode.remove(u);

        // On coupe /fly et /god AVANT de retirer le role (tant qu'il a encore ses perms).
        runAs(p, cmdFly);
        runAs(p, cmdGod);

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

    private void giveStaffItems(Player p) {
        p.getInventory().setItem(SLOT_INV, named(Material.BLAZE_ROD, "Voir inventaire", NamedTextColor.GOLD,
                "Tape un joueur pour /invsee.", "(aucun degat)"));
        p.getInventory().setItem(SLOT_FREEZE, named(Material.BREEZE_ROD, "Freeze / Unfreeze", NamedTextColor.AQUA,
                "Tape un joueur pour le freeze ou le unfreeze."));
        p.getInventory().setItem(SLOT_VANISH, named(Material.GRAY_DYE, "Vanish", NamedTextColor.GRAY,
                "Clic droit pour /sqlvanish."));
        p.getInventory().setHeldItemSlot(SLOT_INV);
    }

    private ItemStack named(Material mat, String name, NamedTextColor color, String... lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> l = new ArrayList<>();
                for (String s : lore) l.add(Component.text(s, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(l);
            }
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack[] deepClone(ItemStack[] src) {
        if (src == null) return null;
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (src[i] == null) ? null : src[i].clone();
        return out;
    }

    // ===================== EVENTS =====================

    // ---- Blaze rod (/invsee) + Breeze rod (freeze) en tapant ----
    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a)) return;
        if (!staffMode.contains(a.getUniqueId())) return;
        if (!(e.getEntity() instanceof Player victim)) {
            e.setCancelled(true); // un staff ne fait jamais de degat
            return;
        }
        Material hand = a.getInventory().getItemInMainHand().getType();
        if (hand == Material.BLAZE_ROD) {
            e.setCancelled(true);
            runAs(a, cmdInvsee.replace("%player%", victim.getName()));
        } else if (hand == Material.BREEZE_ROD) {
            e.setCancelled(true);
            toggleFreeze(a, victim);
        } else {
            e.setCancelled(true);
        }
    }

    private void toggleFreeze(Player actor, Player target) {
        UUID t = target.getUniqueId();
        if (frozen.remove(t)) {
            send(actor, target.getName() + " est DE-freeze.", NamedTextColor.GREEN);
            send(target, "Tu es de nouveau libre de bouger.", NamedTextColor.GREEN);
        } else {
            frozen.add(t);
            send(actor, target.getName() + " est FREEZE.", NamedTextColor.AQUA);
            send(target, "Tu as ete freeze par le staff. Ne bouge pas.", NamedTextColor.RED);
        }
    }

    // ---- Clic droit teinture = /sqlvanish ----
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!staffMode.contains(p.getUniqueId())) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null) return;
        Material m = item.getType();
        if (m == Material.GRAY_DYE) {
            e.setCancelled(true);
            runAs(p, cmdVanish);
        } else if (m == Material.BLAZE_ROD || m == Material.BREEZE_ROD) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (staffMode.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && staffMode.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && staffMode.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && staffMode.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ---- Freeze ----
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            e.setTo(keep);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrozenBreak(BlockBreakEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) { e.setCancelled(true); send(e.getPlayer(), "Tu es freeze.", NamedTextColor.RED); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrozenPlace(BlockPlaceEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onFrozenCommand(PlayerCommandPreprocessEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            send(e.getPlayer(), "Tu es freeze, tu ne peux pas faire de commande.", NamedTextColor.RED);
        }
    }

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
            staffMode.remove(u);
            applyRoleOnExit(p);
            pendingRestore.add(u);
            persist();
        }
        frozen.remove(u);
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

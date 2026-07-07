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
import org.bukkit.event.entity.EntityDamageEvent;
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
 * Staff mode pour Optaland. A mettre dans : SuperShop/java/fr/staffmode/StaffMode.java
 *
 * Permission :  staffmode.use.optaland
 *
 * /staff                 active / desactive le mode staff (god + fly + items + inventaire indropable)
 * /staff chat <message>  parle dans le chat staff (visible uniquement par ceux qui ont la perm)
 * /tp <joueur>           EN MODE STAFF : te teleporte au joueur (sinon = /tp vanilla normal)
 * /survie                repasse en survie
 *
 * ROLE (LuckPerms) :
 *   - Quand un staff N'EST PAS en mode staff  -> il a le role "default" (configurable).
 *   - Quand il RE-active le mode staff        -> il retrouve le role qu'il avait avant.
 *   Necessite LuckPerms. Si LuckPerms est absent, cette partie est simplement ignoree.
 *   Config : plugins/.../config.yml  ->  role.enabled / role.default
 *
 * Items donnes en staff :
 *   - Blaze Rod   -> taper un joueur (aucun degat) ouvre son inventaire
 *   - Breeze Rod  -> taper un joueur = freeze / unfreeze
 *   - Gray Dye    -> clic droit = vanish ON  (devient Lime Dye)
 *     Lime Dye    -> clic droit = vanish OFF (redevient Gray Dye)
 *
 * En vanish, les staff se voient entre eux mais sont invisibles pour les autres.
 * L'inventaire "normal" est mis de cote et rendu quand on quitte le mode staff.
 */
public class StaffMode extends LoadedPlugin implements Listener {

    static final String PERM = "staffmode.use.optaland";

    // Etat runtime
    private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished  = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen    = ConcurrentHashMap.newKeySet();

    // Inventaires "normaux" mis de cote pendant le mode staff
    private final Map<UUID, ItemStack[]> savedInv   = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack>   savedOff   = new ConcurrentHashMap<>();

    // A restaurer apres un crash ou une deco en staff (charge depuis le disque au demarrage)
    private final Set<UUID> pendingRestore = ConcurrentHashMap.newKeySet();

    // ---- ROLE ----
    // Role a rendre quand on re-passe en staff (celui qu'on avait avant d'etre mis en "default").
    private final Map<UUID, String> previousRole = new ConcurrentHashMap<>();
    // Membres du staff connus : permet de garder l'acces a /staff meme quand ils sont en role "default".
    private final Set<UUID> staffMembers = ConcurrentHashMap.newKeySet();
    private boolean roleEnabled = true;
    private String  defaultRole = "default";
    private boolean luckPermsPresent = false;

    private final Set<String> myCommands = new HashSet<>();
    private File file;
    private File backupFile;
    private File configFile;

    // Slots des items staff
    private static final int SLOT_INV   = 0; // blaze rod
    private static final int SLOT_FREEZE = 1; // breeze rod
    private static final int SLOT_VANISH = 4; // gray / lime dye

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        this.file = new File(getHost().getDataFolder(), "staffmode.yml");
        this.backupFile = new File(getHost().getDataFolder(), "inv-backups.yml");
        this.configFile = new File(getHost().getDataFolder(), "configstaff.yml");
        loadConfig();
        this.luckPermsPresent = hasLuckPerms();
        loadPending();
        registerListener(this);
        registerStaffCommand();
        syncCommands();

        // Recuperation apres un crash : les joueurs en ligne recuperent leur inventaire tout de suite.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pendingRestore.contains(p.getUniqueId())) restoreFromPending(p);
        }
        getLogger().info("[StaffMode] active." + (roleEnabled && !luckPermsPresent
                ? " (role active mais LuckPerms introuvable -> gestion de role ignoree)" : ""));
    }

    @Override
    public void onDisable() {
        try {
            // On sort proprement tout le monde du mode staff pour ne perdre aucun inventaire.
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (staffMode.contains(p.getUniqueId())) exitStaff(p, true, false);
            }
            frozen.clear();
            // On rend tout le monde visible a nouveau.
            showEverything();

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

    // ===================== CONFIG ROLE =====================

    private static final String DEFAULT_CONFIG =
            "# ============================================\n" +
            "#            StaffMode - Optaland\n" +
            "# ============================================\n" +
            "# Fichier cree automatiquement au demarrage.\n" +
            "\n" +
            "role:\n" +
            "  # true  = le plugin gere le role (LuckPerms) automatiquement.\n" +
            "  # false = le plugin ne touche jamais aux roles.\n" +
            "  enabled: true\n" +
            "\n" +
            "  # Role donne au staff quand il N'EST PAS en mode staff.\n" +
            "  # Quand il repasse en mode staff, il retrouve le role qu'il avait avant.\n" +
            "  default: \"default\"\n";

    private void loadConfig() {
        roleEnabled = true;
        defaultRole = "default";
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();

            // Cree le fichier tout seul (avec commentaires) s'il n'existe pas encore.
            if (!configFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) {
                    w.write(DEFAULT_CONFIG);
                }
                getLogger().info("[StaffMode] configstaff.yml cree.");
            }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            // Auto-completion si une cle manque (ancien fichier), sans ecraser les commentaires existants.
            boolean changed = false;
            if (!cfg.contains("role.enabled")) { cfg.set("role.enabled", true); changed = true; }
            if (!cfg.contains("role.default")) { cfg.set("role.default", "default"); changed = true; }
            roleEnabled = cfg.getBoolean("role.enabled", true);
            defaultRole = cfg.getString("role.default", "default");
            if (changed) cfg.save(configFile);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] config role KO : " + t.getMessage());
        }
    }

    // ===================== ROLE (LuckPerms) =====================

    private boolean hasLuckPerms() {
        try {
            return Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lit le groupe principal du joueur via l'API LuckPerms (reflection, pas de dependance a la compilation). */
    private String getPrimaryGroup(Player p) {
        try {
            Object lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
            if (user == null) return null;
            Object grp = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return grp != null ? grp.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Change le groupe du joueur (via la commande LuckPerms cote console : simple et compatible). */
    private void setRole(Player p, String group) {
        if (group == null || group.isEmpty()) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + p.getUniqueId() + " parent set " + group);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] changement de role KO pour " + p.getName() + " : " + t.getMessage());
        }
    }

    /** A l'entree en staff : on rend le role qu'il avait avant (s'il a ete mis en "default"). */
    private void applyRoleOnEnter(Player p) {
        if (!roleEnabled || !luckPermsPresent) return;
        String prev = previousRole.get(p.getUniqueId());
        if (prev != null && !prev.isEmpty()) {
            setRole(p, prev);
        }
        // Sinon : on garde son role actuel, il sera memorise a la sortie.
    }

    /** A la sortie du staff : on memorise son role actuel puis on le passe en "default". */
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

    /**
     * /tp <joueur> :
     *  - staff EN mode staff + un seul argument (nom joueur) -> teleporte au joueur.
     *  - sinon -> on laisse la commande /tp vanilla faire son travail normal
     *    (coordonnees, tp d'autres joueurs, etc.), avec les permissions vanilla.
     */
    private boolean handleTp(CommandSender s, String[] a) {
        if (s instanceof Player p && isStaff(p) && staffMode.contains(p.getUniqueId()) && a.length == 1) {
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) { send(p, "Joueur introuvable : " + a[0], NamedTextColor.RED); return true; }
            if (target.equals(p)) { send(p, "Tu ne peux pas te teleporter a toi-meme.", NamedTextColor.RED); return true; }
            p.teleport(target.getLocation());
            send(p, "Teleporte a " + target.getName() + ".", NamedTextColor.GREEN);
            return true;
        }
        // Comportement /tp vanilla normal (namespace minecraft: pour ne pas boucler sur notre commande).
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

    // ===================== MESSAGES =====================

    static final Component PREFIX = Component.text("Staff ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    static void send(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(PREFIX.append(Component.text(text, color)));
    }

    /**
     * Est consideree "staff" toute personne qui a la permission,
     * OU qui est un membre staff connu (utile quand on l'a passe en role "default",
     * ce qui peut lui retirer temporairement la permission).
     */
    boolean isStaff(CommandSender s) {
        if (s.hasPermission(PERM)) return true;
        if (s instanceof Player p) return staffMembers.contains(p.getUniqueId());
        return false;
    }

    /** Version pour la visibilite : un staff (meme hors service en "default") voit les vanish. */
    private boolean isStaffPlayer(Player p) {
        return p.hasPermission(PERM) || staffMembers.contains(p.getUniqueId());
    }

    // ===================== COMMANDE =====================

    private boolean handleCommand(CommandSender sender, String[] args) {
        // Le chat staff peut aussi etre utilise par la console.
        if (args.length >= 1 && args[0].equalsIgnoreCase("chat")) {
            if (!(sender instanceof Player) && !isStaff(sender)) { send(sender, "Pas la permission.", NamedTextColor.RED); return true; }
            if (sender instanceof Player && !isStaff(sender)) { send(sender, "Pas la permission.", NamedTextColor.RED); return true; }
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
            List<String> subs = new ArrayList<>(List.of("chat"));
            List<String> out = new ArrayList<>();
            String x = a[0].toLowerCase(Locale.ROOT);
            for (String o : subs) if (o.startsWith(x)) out.add(o);
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

        // Ce joueur est un membre staff connu (garde l'acces a /staff meme en role "default").
        staffMembers.add(u);
        // Il retrouve le role qu'il avait avant d'etre passe en "default".
        applyRoleOnEnter(p);

        persist();
        // Sauvegarde de secours sur le disque (filet de securite).
        writeBackup(p, contents, armorC, off);

        // On vide et on donne les items staff.
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        giveStaffItems(p);

        staffMode.add(u);
        vanished.remove(u); // vanish OFF a l'entree

        // God + fly
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFireTicks(0);

        p.updateInventory();
        refreshVisibility(); // il peut maintenant voir les staff en vanish
        send(p, "Mode staff ACTIVE. (god + fly)", NamedTextColor.GREEN);
    }

    /**
     * @param restore  true = on rend l'inventaire normal
     * @param announce true = on envoie le message au joueur
     */
    private void exitStaff(Player p, boolean restore, boolean announce) {
        UUID u = p.getUniqueId();
        staffMode.remove(u);
        vanished.remove(u);

        p.setFlying(false);
        p.setAllowFlight(false);

        if (restore) restoreInventory(p);

        // On repasse le joueur en role "default" (et on memorise son role actuel).
        applyRoleOnExit(p);

        savedInv.remove(u);
        savedArmor.remove(u);
        savedOff.remove(u);
        pendingRestore.remove(u);
        clearBackup(u);
        persist();

        p.updateInventory();
        refreshVisibility();
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
                "Tape un joueur pour ouvrir son inventaire.", "(aucun degat)"));
        p.getInventory().setItem(SLOT_FREEZE, named(Material.BREEZE_ROD, "Freeze / Unfreeze", NamedTextColor.AQUA,
                "Tape un joueur pour le freeze ou le unfreeze."));
        p.getInventory().setItem(SLOT_VANISH, dyeItem(false));
        p.getInventory().setHeldItemSlot(SLOT_INV);
    }

    private ItemStack dyeItem(boolean vanishOn) {
        if (vanishOn) {
            return named(Material.LIME_DYE, "Vanish : ON", NamedTextColor.GREEN,
                    "Clic droit pour redevenir visible.");
        }
        return named(Material.GRAY_DYE, "Vanish : OFF", NamedTextColor.GRAY,
                "Clic droit pour passer en vanish.");
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

    /** Clone profond d'un tableau d'items (le tableau ET chaque item). */
    private ItemStack[] deepClone(ItemStack[] src) {
        if (src == null) return null;
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (src[i] == null) ? null : src[i].clone();
        }
        return out;
    }

    // ===================== VANISH =====================

    private void setVanish(Player p, boolean on) {
        UUID u = p.getUniqueId();
        if (on) vanished.add(u); else vanished.remove(u);
        // Met a jour l'item en main.
        p.getInventory().setItemInMainHand(dyeItem(on));
        refreshVisibility();
        send(p, on ? "Tu es maintenant en VANISH." : "Tu n'es plus en vanish.",
                on ? NamedTextColor.GREEN : NamedTextColor.GRAY);
    }

    /** Les vanish ne sont visibles que par les staff. */
    @SuppressWarnings("deprecation")
    private void refreshVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean viewerStaff = isStaffPlayer(viewer);
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(target)) continue;
                if (vanished.contains(target.getUniqueId()) && !viewerStaff) {
                    viewer.hidePlayer(target);
                } else {
                    viewer.showPlayer(target);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void showEverything() {
        for (Player viewer : Bukkit.getOnlinePlayers())
            for (Player target : Bukkit.getOnlinePlayers())
                if (!viewer.equals(target)) viewer.showPlayer(target);
    }

    // ===================== EVENTS =====================

    // ---- God (staff invincibles en mode staff) ----
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---- Blaze rod (ouvrir inventaire) + Breeze rod (freeze) en tapant ----
    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a)) return;
        if (!staffMode.contains(a.getUniqueId())) return;
        if (!(e.getEntity() instanceof Player victim)) {
            // Un staff en mode staff ne fait jamais de degat.
            e.setCancelled(true);
            return;
        }
        Material hand = a.getInventory().getItemInMainHand().getType();
        if (hand == Material.BLAZE_ROD) {
            e.setCancelled(true);
            a.openInventory(victim.getInventory());
        } else if (hand == Material.BREEZE_ROD) {
            e.setCancelled(true);
            toggleFreeze(a, victim);
        } else {
            e.setCancelled(true); // aucun degat quel que soit l'item
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

    // ---- Clic droit sur la teinture = vanish ----
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!staffMode.contains(p.getUniqueId())) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // evite le double declenchement
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (item == null) return;
        Material m = item.getType();
        if (m == Material.GRAY_DYE) {
            e.setCancelled(true);
            setVanish(p, true);
        } else if (m == Material.LIME_DYE) {
            e.setCancelled(true);
            setVanish(p, false);
        } else if (m == Material.BLAZE_ROD || m == Material.BREEZE_ROD) {
            e.setCancelled(true); // pas d'interaction bloc avec ces outils
        }
    }

    // ---- Inventaire indropable en mode staff ----
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (staffMode.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ---- On ne peut RIEN bouger / donner / ajouter en mode staff : on garde juste les batons ----
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true); // verrouille le propre inventaire ET tout inventaire ouvert (lecture seule)
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---- Pas de ramassage d'items en mode staff ----
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---- Freeze : bloque les deplacements (mais laisse tourner la tete) ----
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
        if (frozen.contains(e.getPlayer().getUniqueId())) { e.setCancelled(true); }
    }

    @EventHandler
    public void onFrozenCommand(PlayerCommandPreprocessEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            send(e.getPlayer(), "Tu es freeze, tu ne peux pas faire de commande.", NamedTextColor.RED);
        }
    }

    // ---- Join / Quit ----
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (pendingRestore.contains(p.getUniqueId())) restoreFromPending(p);
        // Applique la visibilite (cache les staff vanish au nouveau venu, etc.)
        refreshVisibility();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Si un staff se deconnecte EN mode staff : on ne restaure PAS l'inventaire en direct.
        // Une modif d'inventaire dans QuitEvent n'est pas toujours ecrite par le serveur,
        // ce qui fait perdre le stuff. On garde tout sur le disque et on le rend a la
        // reconnexion (onJoin -> restoreFromPending), exactement comme apres un crash.
        UUID u = p.getUniqueId();
        if (staffMode.contains(u)) {
            staffMode.remove(u);
            vanished.remove(u);
            p.setFlying(false);
            p.setAllowFlight(false);
            // Il quitte le mode staff -> il repasse en role "default" (le role est bien ecrit,
            // contrairement a l'inventaire, car c'est LuckPerms qui le gere).
            applyRoleOnExit(p);
            pendingRestore.add(u);   // <- restauration inventaire a la prochaine connexion
            persist();               // <- garde savedInv / savedArmor / savedOff sur le disque
            refreshVisibility();
        }
        frozen.remove(u);
    }

    // ===================== PERSISTANCE (secours anti-crash) =====================

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

        // Roles memorises (a rendre a la prochaine activation du mode staff).
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

        // Membres staff connus (pour garder l'acces a /staff meme en role "default").
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

        for (Map.Entry<UUID, String> e : previousRole.entrySet()) {
            cfg.set("roles." + e.getKey(), e.getValue());
        }

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

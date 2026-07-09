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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
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
 * Staff mode pour Optaland + systeme de backup d'inventaire.
 *
 * Permissions :
 *   staffmode.use.optaland        -> acces au mode staff.
 *   staffmode.inventorybackup     -> acces a /inventorybackup (OP aussi autorise).
 *
 * Commandes :
 *   /staff                 -> active / desactive (god + fly, inventaire mis de cote).
 *   /staff chat <message>  -> chat staff.
 *   /grade <nom>           -> (EN STAFF) active/desactive un grade temporaire (perm grade.<nom>).
 *   /tp <joueur>           -> tp au joueur (en mode staff) sinon /tp vanilla.
 *   /survie                -> survie.
 *   /inventorybackup <joueur> [numero|latest|undo]  (OP) -> restaure un inventaire sauvegarde.
 *
 * GESTION DES ROLES (LuckPerms) :
 *   /staff (entree) -> retire UNIQUEMENT tes grades "staff" (role.staff-groups) et les
 *                      memorise. TOUS tes autres grades (default + grade boutique) restent
 *                      INTACTS et VISIBLES. Tu apparais donc comme un joueur normal (avec
 *                      ton grade boutique si tu en as un).
 *   /staff (sortie) -> te remet tes grades staff. Ton grade boutique n'est JAMAIS touche.
 *   Ex : tu es "admin" + "vip", /staff -> tu apparais "vip", /staff -> "admin" + "vip".
 *
 *   Si aucun grade de role.staff-groups ne correspond, le plugin cache automatiquement
 *   ton grade PRINCIPAL actuel (souvent ton grade staff) et garde tout le reste.
 *
 *   >>> CORRECTIF IMPORTANT <<<
 *   L'ancienne version lisait l'API LuckPerms via le classloader du plugin. Avec un
 *   loader custom (fr.loader.api.LoadedPlugin), ce classloader ne voit PAS les classes
 *   de LuckPerms -> la lecture des grades echouait silencieusement -> le grade n'etait
 *   ni change a l'entree, ni restaure a la sortie. On charge maintenant l'API via le
 *   classloader de LuckPerms lui-meme (voir lpClassLoader / lpApiOrNull), ce qui repare
 *   completement la gestion des grades.
 *
 * BACKUP UNIVERSEL : toutes les X secondes, l'inventaire COMPLET de chaque joueur en
 * ligne (inventaire + armure + main gauche + ender chest) est serialise en base64 et
 * sauvegarde sur le disque, avec un historique par joueur. Un backup est aussi pris a
 * la connexion, a la deconnexion, a la mort (items droppes) et avant chaque action
 * destructrice (entree en staff, restauration). => on peut toujours remonter le temps.
 *
 * SECURITE ANTI-PERTE : on n'efface JAMAIS un inventaire tant qu'on n'a pas confirme
 * qu'on a des donnees valides a remettre. Toutes les erreurs sont loggees (plus de
 * catch silencieux sur les chemins critiques).
 */
public class StaffMode extends LoadedPlugin implements Listener {

    static final String PERM        = "staffmode.use.optaland";
    static final String BACKUP_PERM = "staffmode.inventorybackup";

    private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();

    // Inventaires mis de cote pendant le mode staff (storage 36 / armor 4 / offhand 1).
    private final Map<UUID, ItemStack[]> savedInv   = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack>   savedOff   = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRestore = ConcurrentHashMap.newKeySet();

    // Grades STAFF caches pendant le service (retires a l'entree, remis a la sortie).
    // On NE TOUCHE JAMAIS aux autres grades (default + grades boutique).
    private final Map<UUID, List<String>> previousRoles   = new ConcurrentHashMap<>();
    private final Map<UUID, String>       previousPrimary = new ConcurrentHashMap<>(); // grade principal d'avant
    private final Set<UUID> fallbackApplied = ConcurrentHashMap.newKeySet();           // fallback ajoute par le plugin
    private final Set<UUID> staffMembers = ConcurrentHashMap.newKeySet();
    private boolean roleEnabled = true;
    private List<String> staffGroups = new ArrayList<>(); // grades staff a cacher (config)
    private String  fallbackGroup = "joueur";             // applique seulement si plus aucun grade
    private boolean luckPermsPresent = false;

    // /grade : grades temporaires actives PENDANT le service (retires en quittant le staff).
    private List<String> gradeList = new ArrayList<>(); // liste indicative (menu + tab)
    private final Map<UUID, Set<String>> gradeToggled = new ConcurrentHashMap<>();

    // Backup universel.
    private boolean backupEnabled = true;
    private int backupIntervalSeconds = 300;
    private int backupMaxPerPlayer = 30;
    private File backupDir;
    private int backupTaskId = -1;
    private final Map<UUID, String> lastSig = new ConcurrentHashMap<>();
    private final Object diskLock = new Object();

    private final Set<String> myCommands = new HashSet<>();
    private File file;
    private File backupFile;   // backup staff (redondance) : inv-backups.yml
    private File configFile;

    @Override
    public void onEnable() {
        this.file = new File(getHost().getDataFolder(), "staffmode.yml");
        this.backupFile = new File(getHost().getDataFolder(), "inv-backups.yml");
        this.configFile = new File(getHost().getDataFolder(), "configstaff.yml");
        this.backupDir = new File(getHost().getDataFolder(), "inventory-backups");
        if (!backupDir.exists()) backupDir.mkdirs();

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

        // Tache de backup automatique + baseline immediate pour les joueurs deja connectes.
        startBackupTask();
        snapshotAll("startup", true, true);

        getLogger().info("[StaffMode] active." + (roleEnabled && !luckPermsPresent
                ? " (role active mais LuckPerms introuvable -> gestion de role ignoree)" : ""));
    }

    @Override
    public void onDisable() {
        try {
            if (backupTaskId != -1) {
                try { Bukkit.getScheduler().cancelTask(backupTaskId); } catch (Throwable ignored) {}
                backupTaskId = -1;
            }
            // Backup final (synchrone pour garantir l'ecriture avant l'arret).
            snapshotAll("shutdown", true, false);

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
            "  # true  = /staff cache tes grades staff (et les remet quand tu ressors).\n" +
            "  # false = le plugin ne touche jamais aux grades.\n" +
            "  enabled: true\n" +
            "  # Grades STAFF a CACHER pendant le service (retires a l'entree, remis a la sortie).\n" +
            "  # Tes AUTRES grades (default + grade boutique) restent INTACTS et VISIBLES.\n" +
            "  # >>> Mets ici le nom EXACT de tes grades staff LuckPerms. <<<\n" +
            "  # Astuce : si tu laisses la liste vide, le plugin cache automatiquement ton\n" +
            "  # grade PRINCIPAL du moment (souvent ton grade staff) et garde tout le reste.\n" +
            "  staff-groups:\n" +
            "    - admin\n" +
            "    - moderateur\n" +
            "    - modo\n" +
            "    - helpeur\n" +
            "    - guide\n" +
            "  # Grade applique UNIQUEMENT si, apres avoir cache tes grades staff, il ne te\n" +
            "  # reste plus AUCUN grade (filet de securite). Laisse vide (\"\") pour ne rien forcer.\n" +
            "  fallback-group: \"joueur\"\n" +
            "\n" +
            "# /grade <nom> : active/desactive un grade TEMPORAIRE, uniquement quand tu es en /staff.\n" +
            "# Le vrai controle se fait par la permission grade.<nom> (ex: grade.mod, grade.admin).\n" +
            "# Cette liste sert juste au menu /grade et a l'auto-completion.\n" +
            "# Les grades ajoutes via /grade sont RETIRES automatiquement quand tu quittes le staff.\n" +
            "grades:\n" +
            "  - mod\n" +
            "  - admin\n" +
            "  - super_admin\n" +
            "  - super_modo\n" +
            "\n" +
            "backup:\n" +
            "  # Sauvegarde automatique de TOUS les inventaires sur le disque.\n" +
            "  enabled: true\n" +
            "  # Intervalle entre deux sauvegardes automatiques (en secondes).\n" +
            "  interval-seconds: 300\n" +
            "  # Nombre maximum de sauvegardes conservees par joueur.\n" +
            "  max-per-player: 30\n";

    private void loadConfig() {
        roleEnabled = true; staffGroups = new ArrayList<>(); fallbackGroup = "joueur";
        gradeList = new ArrayList<>();
        backupEnabled = true; backupIntervalSeconds = 300; backupMaxPerPlayer = 30;
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (!configFile.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(configFile)) { w.write(DEFAULT_CONFIG); }
                getLogger().info("[StaffMode] configstaff.yml cree.");
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            boolean changed = false;
            if (!cfg.contains("role.enabled"))           { cfg.set("role.enabled", true); changed = true; }
            if (!cfg.contains("role.staff-groups")) {
                cfg.set("role.staff-groups", new ArrayList<>(List.of("admin", "moderateur", "modo", "helpeur", "guide")));
                changed = true;
            }
            if (!cfg.contains("role.fallback-group")) {
                // Reprend l'ancien player-group s'il existait, sinon "joueur".
                cfg.set("role.fallback-group", cfg.getString("role.player-group", "joueur"));
                changed = true;
            }
            if (!cfg.contains("grades")) {
                cfg.set("grades", new ArrayList<>(List.of("mod", "admin", "super_admin", "super_modo")));
                changed = true;
            }
            if (!cfg.contains("backup.enabled"))         { cfg.set("backup.enabled", true); changed = true; }
            if (!cfg.contains("backup.interval-seconds")) { cfg.set("backup.interval-seconds", 300); changed = true; }
            if (!cfg.contains("backup.max-per-player"))  { cfg.set("backup.max-per-player", 30); changed = true; }

            roleEnabled = cfg.getBoolean("role.enabled", true);
            staffGroups = new ArrayList<>(cfg.getStringList("role.staff-groups"));
            fallbackGroup = cfg.getString("role.fallback-group", cfg.getString("role.player-group", "joueur"));
            gradeList = new ArrayList<>(cfg.getStringList("grades"));
            backupEnabled = cfg.getBoolean("backup.enabled", true);
            backupIntervalSeconds = Math.max(10, cfg.getInt("backup.interval-seconds", 300));
            backupMaxPerPlayer = Math.max(3, cfg.getInt("backup.max-per-player", 30));

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

    /**
     * Classloader capable de charger les classes net.luckperms.api.*.
     * On prend celui de LuckPerms lui-meme : c'est LA correction du bug de grade.
     * Avec le loader custom (fr.loader), le classloader du plugin ne voit pas ces
     * classes, donc la reflection echouait et le grade n'etait jamais touche.
     */
    private ClassLoader lpClassLoader() {
        try {
            org.bukkit.plugin.Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp != null) return lp.getClass().getClassLoader();
        } catch (Throwable ignored) {}
        return getClass().getClassLoader();
    }

    /** Recupere l'instance de l'API LuckPerms, ou null si indisponible. */
    private Object lpApiOrNull() {
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, lpClassLoader());
            return provider.getMethod("get").invoke(null);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] API LuckPerms inaccessible : " + t);
            return null;
        }
    }

    private void lpRun(String args) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp " + args);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] commande LuckPerms KO (" + args + ") : " + t.getMessage());
        }
    }

    /**
     * Lit la liste COMPLETE des groupes LuckPerms directement attribues au joueur
     * (pas seulement le "groupe principal"). Ex: [admin, default].
     */
    @SuppressWarnings("unchecked")
    private List<String> getParentGroups(Player p) {
        try {
            Object lp = lpApiOrNull();
            if (lp == null) return null;
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
            if (user == null) return null;
            Object nodes = user.getClass().getMethod("getNodes").invoke(user);
            List<String> groups = new ArrayList<>();
            for (Object node : (Iterable<Object>) nodes) {
                Object key = node.getClass().getMethod("getKey").invoke(node);
                if (key instanceof String k && k.startsWith("group.")) {
                    String g = k.substring("group.".length());
                    if (!g.isEmpty() && !groups.contains(g)) groups.add(g);
                }
            }
            return groups;
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] lecture des groupes KO pour " + p.getName() + " : " + t);
            return null;
        }
    }

    /** Grade PRINCIPAL LuckPerms (celui qui pilote l'affichage du grade/prefix). */
    private String getPrimaryGroup(Player p) {
        try {
            Object lp = lpApiOrNull();
            if (lp == null) return null;
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = um.getClass().getMethod("getUser", UUID.class).invoke(um, p.getUniqueId());
            if (user == null) return null;
            Object grp = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return grp != null ? grp.toString() : null;
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] lecture du grade principal KO pour " + p.getName() + " : " + t);
            return null;
        }
    }

    private boolean containsIgnoreCase(List<String> list, String v) {
        if (list == null || v == null) return false;
        for (String s : list) if (v.equalsIgnoreCase(s)) return true;
        return false;
    }

    private boolean isDefaultish(String g) {
        return g == null || g.isEmpty()
                || g.equalsIgnoreCase("default")
                || (fallbackGroup != null && g.equalsIgnoreCase(fallbackGroup));
    }

    /**
     * ENTREE en staff : on retire UNIQUEMENT les grades "staff" du joueur et on les
     * memorise. TOUS ses autres grades (default + grade boutique) restent INTACTS et
     * VISIBLES -> il apparait comme un joueur normal (avec son grade boutique s'il en a un).
     *
     * Choix des grades a cacher :
     *   1) tous ses grades qui figurent dans role.staff-groups ;
     *   2) si aucun ne correspond, on cache son grade PRINCIPAL du moment (sauf si c'est
     *      deja default / le fallback).
     */
    private void applyRoleOnEnter(Player p) {
        if (!roleEnabled || !luckPermsPresent) return;
        UUID u = p.getUniqueId();

        List<String> live = getParentGroups(p);
        if (live == null) {
            // Lecture impossible -> on NE change RIEN (pour ne rien casser).
            getLogger().warning("[StaffMode] grades introuvables pour " + p.getName()
                    + " -> grade NON change (LuckPerms pas encore charge ?).");
            return;
        }
        String primary = getPrimaryGroup(p);

        // 1) Grades staff a cacher = intersection(live, staff-groups).
        List<String> hidden = new ArrayList<>();
        for (String g : live) if (containsIgnoreCase(staffGroups, g)) hidden.add(g);

        // 2) Fallback : si rien ne correspond, on cache le grade principal (s'il n'est pas default/fallback).
        if (hidden.isEmpty() && primary != null && !isDefaultish(primary) && containsIgnoreCase(live, primary)) {
            hidden.add(primary);
        }

        if (hidden.isEmpty()) {
            getLogger().info("[StaffMode] aucun grade staff a cacher pour " + p.getName()
                    + " (grades: " + live + ") -> grade laisse tel quel. "
                    + "Ajoute ton grade staff dans role.staff-groups si besoin.");
            return;
        }

        // Grades qui RESTENT (default + boutique) -> on n'y touche jamais.
        List<String> remaining = new ArrayList<>();
        for (String g : live) if (!containsIgnoreCase(hidden, g)) remaining.add(g);

        // Memoriser pour la sortie.
        previousRoles.put(u, hidden);
        if (primary != null && !primary.isEmpty()) previousPrimary.put(u, primary);

        // Retirer UNIQUEMENT les grades staff.
        for (String g : hidden) lpRun("user " + u + " parent remove " + g);

        // Filet de securite : s'il ne reste plus AUCUN grade, appliquer le fallback.
        if (remaining.isEmpty() && fallbackGroup != null && !fallbackGroup.isEmpty()) {
            lpRun("user " + u + " parent add " + fallbackGroup);
            lpRun("user " + u + " parent switchprimarygroup " + fallbackGroup);
            fallbackApplied.add(u);
        } else if (containsIgnoreCase(hidden, primary)) {
            // Le grade principal etait un grade staff -> basculer l'affichage sur un grade
            // restant non-default (typiquement le grade boutique), sinon default.
            String disp = null;
            for (String g : remaining) { if (!isDefaultish(g)) { disp = g; break; } }
            if (disp == null && !remaining.isEmpty()) disp = remaining.get(0);
            if (disp != null && !disp.isEmpty()) lpRun("user " + u + " parent switchprimarygroup " + disp);
        }

        persist();
        getLogger().info("[StaffMode] " + p.getName() + " en service -> grades staff caches " + hidden
                + " | grades gardes: " + remaining + ".");
    }

    /**
     * SORTIE de staff : on remet les grades staff caches et le grade principal d'avant.
     * On retire le fallback qu'on avait eventuellement ajoute. On NE TOUCHE JAMAIS au
     * grade boutique (il n'a jamais ete retire).
     */
    private void restoreStaffGrades(Player p) {
        UUID u = p.getUniqueId();
        List<String> hidden = previousRoles.remove(u);
        String primary = previousPrimary.remove(u);
        boolean fb = fallbackApplied.remove(u);

        if ((hidden == null || hidden.isEmpty()) && primary == null && !fb) {
            getLogger().info("[StaffMode] aucun grade staff memorise pour " + p.getName()
                    + " -> grade laisse tel quel (rien touche).");
            return;
        }

        // Remettre les grades staff.
        if (hidden != null) {
            for (String g : hidden) if (g != null && !g.isEmpty()) lpRun("user " + u + " parent add " + g);
        }
        // Retirer le fallback qu'on avait ajoute (sauf s'il fait partie des grades staff remis).
        if (fb && fallbackGroup != null && !fallbackGroup.isEmpty()
                && (hidden == null || !containsIgnoreCase(hidden, fallbackGroup))) {
            lpRun("user " + u + " parent remove " + fallbackGroup);
        }
        // Remettre le grade principal d'avant (l'affichage revient).
        if (primary != null && !primary.isEmpty()) {
            lpRun("user " + u + " parent switchprimarygroup " + primary);
        }
        getLogger().info("[StaffMode] " + p.getName() + " -> grades staff remis " + hidden
                + " (principal: " + primary + ").");
    }

    private void applyRoleOnExit(Player p) {
        if (!roleEnabled || !luckPermsPresent) return;
        restoreStaffGrades(p);
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
        registerOne(map, "grade", "Active/desactive un grade temporaire (en staff)", "/grade <nom>", List.of("grades"),
                (s, a) -> handleGrade(s, a), (s, a) -> tabGrade(s, a));
        registerOne(map, "tp", "Teleportation staff", "/tp <joueur>", List.of(),
                (s, a) -> handleTp(s, a), (s, a) -> tabPlayers(a));
        registerOne(map, "survie", "Passe en survie", "/survie", List.of("survival", "surv"),
                (s, a) -> handleSurvie(s), null);
        registerOne(map, "inventorybackup", "Restaure un inventaire sauvegarde",
                "/inventorybackup <joueur> [numero|latest|undo]", List.of("invbackup", "invrestore"),
                (s, a) -> handleInventoryBackup(s, a), (s, a) -> tabBackup(a));
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

    // ===================== /grade (uniquement en staff) =====================

    private boolean handleGrade(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { send(s, "Joueurs uniquement.", NamedTextColor.RED); return true; }
        UUID u = p.getUniqueId();

        // Regle demandee : il faut D'ABORD etre en /staff.
        if (!staffMode.contains(u)) {
            send(p, "Tu dois d'abord etre en /staff pour utiliser /grade.", NamedTextColor.RED);
            return true;
        }
        if (!luckPermsPresent) {
            send(p, "LuckPerms introuvable -> /grade indisponible.", NamedTextColor.RED);
            return true;
        }

        // Sans argument : on affiche les grades disponibles + leur etat.
        if (a.length == 0) {
            send(p, "Grades (/grade <nom>) :", NamedTextColor.YELLOW);
            List<String> shown = new ArrayList<>();
            for (String g : gradeList) if (p.hasPermission("grade." + g) && !containsIgnoreCase(shown, g)) shown.add(g);
            Set<String> active = gradeToggled.get(u);
            if (active != null) for (String g : active) if (!containsIgnoreCase(shown, g)) shown.add(g);
            if (shown.isEmpty()) {
                send(p, "Aucun grade disponible (il faut la permission grade.<nom>).", NamedTextColor.GRAY);
                return true;
            }
            for (String g : shown) {
                boolean on = playerHasGroup(p, g);
                p.sendMessage(Component.text("  • ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(g, on ? NamedTextColor.GREEN : NamedTextColor.GRAY))
                        .append(Component.text(on ? "  [ACTIF]" : "  [inactif]",
                                on ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_GRAY)));
            }
            return true;
        }

        String name = a[0].toLowerCase(Locale.ROOT);
        if (!p.hasPermission("grade." + name)) {
            send(p, "Tu n'as pas la permission grade." + name + ".", NamedTextColor.RED);
            return true;
        }

        boolean has = playerHasGroup(p, name);
        if (has) {
            lpRun("user " + u + " parent remove " + name);
            Set<String> set = gradeToggled.get(u);
            if (set != null) { set.remove(name); if (set.isEmpty()) gradeToggled.remove(u); }
            persist();
            send(p, "Grade " + name + " RETIRE.", NamedTextColor.GRAY);
            getLogger().info("[StaffMode] " + p.getName() + " a retire le grade temporaire " + name + ".");
        } else {
            lpRun("user " + u + " parent add " + name);
            gradeToggled.computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet()).add(name);
            persist();
            send(p, "Grade " + name + " AJOUTE.", NamedTextColor.GREEN);
            getLogger().info("[StaffMode] " + p.getName() + " a ajoute le grade temporaire " + name + ".");
        }
        return true;
    }

    /** true si le joueur possede actuellement le groupe (lecture live, fallback = grades toggle memorises). */
    private boolean playerHasGroup(Player p, String name) {
        List<String> live = getParentGroups(p);
        if (live != null) return containsIgnoreCase(live, name);
        Set<String> set = gradeToggled.get(p.getUniqueId());
        return set != null && containsIgnoreCase(new ArrayList<>(set), name);
    }

    /** Retire tous les grades ajoutes via /grade (appele en quittant le staff). */
    private void clearToggledGrades(Player p) {
        UUID u = p.getUniqueId();
        Set<String> set = gradeToggled.remove(u);
        if (set == null || set.isEmpty()) return;
        for (String g : set) {
            if (g != null && !g.isEmpty()) lpRun("user " + u + " parent remove " + g);
        }
        getLogger().info("[StaffMode] grades temporaires retires pour " + p.getName() + " : " + set + ".");
    }

    private List<String> tabGrade(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return List.of();
        if (a.length == 1) {
            String x = a[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String g : gradeList) {
                if (p.hasPermission("grade." + g) && g.toLowerCase(Locale.ROOT).startsWith(x) && !out.contains(g)) out.add(g);
            }
            Set<String> active = gradeToggled.get(p.getUniqueId());
            if (active != null) for (String g : active) {
                if (g.toLowerCase(Locale.ROOT).startsWith(x) && !out.contains(g)) out.add(g);
            }
            return out;
        }
        return List.of();
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

    private String senderName(CommandSender s) { return (s instanceof Player pl) ? pl.getName() : "Console"; }

    private boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("chat")) {
            if (!isStaff(sender)) { send(sender, "Pas la permission.", NamedTextColor.RED); return true; }
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

        // SECURITE : ne jamais ecraser une sauvegarde deja existante par un inventaire vide.
        if (staffMode.contains(u) || savedInv.containsKey(u) || pendingRestore.contains(u)) {
            send(p, "Un inventaire est deja sauvegarde. Fais /staff pour sortir avant de reactiver.", NamedTextColor.RED);
            getLogger().warning("[StaffMode] enterStaff bloque pour " + p.getName() + " : sauvegarde deja existante.");
            return;
        }

        // Backup universel juste avant de vider (double filet de securite).
        snapshot(p, "pre-staff", true, false);

        // Sauvegarde de l'inventaire normal (storage 36 / armor 4 / offhand 1).
        ItemStack[] contents = deepClone(p.getInventory().getStorageContents());
        ItemStack[] armorC   = deepClone(p.getInventory().getArmorContents());
        ItemStack off = p.getInventory().getItemInOffHand();
        off = (off == null ? new ItemStack(Material.AIR) : off.clone());

        savedInv.put(u, contents);
        savedArmor.put(u, armorC);
        savedOff.put(u, off);

        staffMembers.add(u);
        applyRoleOnEnter(p);

        persist();
        writeBackup(u, p.getName(), contents, armorC, off);

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

        clearToggledGrades(p);
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

    /** Restaure l'inventaire mis de cote. NE TOUCHE PAS a l'inventaire si aucune donnee valide. */
    private void restoreInventory(Player p) {
        UUID u = p.getUniqueId();
        ItemStack[] inv = savedInv.get(u), armor = savedArmor.get(u);
        ItemStack off = savedOff.get(u);
        boolean hasData = (inv != null && !allNull(inv))
                       || (armor != null && !allNull(armor))
                       || (off != null && off.getType() != Material.AIR);
        if (!hasData) {
            getLogger().warning("[StaffMode] rien a restaurer pour " + p.getName()
                    + " (sauvegarde vide/introuvable, inventaire actuel laisse INTACT).");
            return;
        }
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        if (inv != null) applyStorage(p, inv);
        if (armor != null) p.getInventory().setArmorContents(fit4(armor));
        if (off != null) p.getInventory().setItemInOffHand(off);
        p.updateInventory();
    }

    private ItemStack[] deepClone(ItemStack[] src) {
        if (src == null) return null;
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (src[i] == null) ? null : src[i].clone();
        return out;
    }

    private boolean allNull(ItemStack[] arr) {
        if (arr == null) return true;
        for (ItemStack it : arr) if (it != null && it.getType() != Material.AIR) return false;
        return true;
    }

    private ItemStack[] fit4(ItemStack[] armor) {
        ItemStack[] a = new ItemStack[4];
        for (int i = 0; i < 4 && i < armor.length; i++) a[i] = armor[i];
        return a;
    }

    /** Applique un tableau de storage : taille 36 = pose directe, sinon addItem (ex: drops de mort). */
    private void applyStorage(Player p, ItemStack[] storage) {
        if (storage.length == 36) { p.getInventory().setStorageContents(storage); return; }
        for (ItemStack it : storage) {
            if (it != null && it.getType() != Material.AIR) {
                for (ItemStack leftover : p.getInventory().addItem(it.clone()).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), leftover);
                }
            }
        }
    }

    // ===================== GOD =====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && staffMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ===================== JOIN / QUIT / DEATH =====================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (pendingRestore.contains(p.getUniqueId())) restoreFromPending(p);
        snapshot(p, "join", true, true);
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
            clearToggledGrades(p);
            applyRoleOnExit(p);
            pendingRestore.add(u);
            persist();
        } else {
            // Backup de securite avant deconnexion (synchrone).
            snapshot(p, "quit", true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        List<ItemStack> drops = e.getDrops();
        // On sauvegarde ce qui est perdu a la mort pour pouvoir le rendre plus tard.
        if (drops != null && !drops.isEmpty()) {
            List<ItemStack> copy = new ArrayList<>();
            for (ItemStack it : drops) copy.add(it == null ? null : it.clone());
            snapshotArrays(p.getUniqueId(), p.getName(), p.getGameMode().name(), p.getLevel(), "death",
                    copy.toArray(new ItemStack[0]), new ItemStack[0], new ItemStack[0],
                    p.getEnderChest().getContents(), true, true);
        } else {
            snapshot(p, "death", true, true);
        }
    }

    // ===================== BACKUP UNIVERSEL =====================

    private void startBackupTask() {
        if (!backupEnabled) return;
        if (getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            long ticks = Math.max(200L, backupIntervalSeconds * 20L);
            try {
                backupTaskId = Bukkit.getScheduler()
                        .runTaskTimer(plugin, () -> snapshotAll("auto", false, true), ticks, ticks)
                        .getTaskId();
            } catch (Throwable t) {
                getLogger().warning("[StaffMode] impossible de lancer la tache de backup : " + t);
            }
        } else {
            getLogger().warning("[StaffMode] getHost() n'est pas un Plugin -> backup automatique desactive.");
        }
    }

    private void snapshotAll(String reason, boolean force, boolean async) {
        for (Player p : Bukkit.getOnlinePlayers()) snapshot(p, reason, force, async);
    }

    private void snapshot(Player p, String reason, boolean force, boolean async) {
        snapshotArrays(p.getUniqueId(), p.getName(), p.getGameMode().name(), p.getLevel(), reason,
                p.getInventory().getStorageContents(), p.getInventory().getArmorContents(),
                new ItemStack[]{ p.getInventory().getItemInOffHand() }, p.getEnderChest().getContents(),
                force, async);
    }

    private void snapshotArrays(UUID u, String name, String gm, int level, String reason,
                                ItemStack[] storage, ItemStack[] armor, ItemStack[] offArr, ItemStack[] ender,
                                boolean force, boolean async) {
        if (!backupEnabled && !force) return;
        String sStorage, sArmor, sOff, sEnder;
        try {
            sStorage = toBase64(storage == null ? new ItemStack[0] : storage);
            sArmor   = toBase64(armor   == null ? new ItemStack[0] : armor);
            sOff     = toBase64(offArr  == null ? new ItemStack[0] : offArr);
            sEnder   = toBase64(ender   == null ? new ItemStack[0] : ender);
        } catch (Throwable t) {
            getLogger().warning("[StaffMode] snapshot serialisation KO pour " + name + " : " + t);
            return;
        }
        String sig = Integer.toHexString((sStorage + "|" + sArmor + "|" + sOff + "|" + sEnder).hashCode());
        if (!force && sig.equals(lastSig.get(u))) return; // rien de change depuis le dernier
        lastSig.put(u, sig);

        long time = System.currentTimeMillis();
        Runnable write = () -> writeSnapshot(u, name, time, reason, gm, level, sStorage, sArmor, sOff, sEnder);
        if (async && getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            try { Bukkit.getScheduler().runTaskAsynchronously(plugin, write); return; } catch (Throwable ignored) {}
        }
        write.run();
    }

    private File backupFileFor(UUID u) { return new File(backupDir, u.toString() + ".yml"); }

    private void writeSnapshot(UUID u, String name, long time, String reason, String gm, int level,
                               String storage, String armor, String off, String ender) {
        synchronized (diskLock) {
            try {
                if (backupDir != null && !backupDir.exists()) backupDir.mkdirs();
                File f = backupFileFor(u);
                YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

                String id = String.format(Locale.ROOT, "%020d", time);
                while (cfg.contains("snapshots." + id)) id = String.format(Locale.ROOT, "%020d", ++time);

                String base = "snapshots." + id;
                cfg.set(base + ".time", time);
                cfg.set(base + ".reason", reason);
                cfg.set(base + ".name", name);
                cfg.set(base + ".gamemode", gm);
                cfg.set(base + ".level", level);
                cfg.set(base + ".storage", storage);
                cfg.set(base + ".armor", armor);
                cfg.set(base + ".offhand", off);
                cfg.set(base + ".enderchest", ender);

                // On limite l'historique.
                ConfigurationSection sec = cfg.getConfigurationSection("snapshots");
                if (sec != null) {
                    List<String> keys = new ArrayList<>(sec.getKeys(false));
                    if (keys.size() > backupMaxPerPlayer) {
                        keys.sort(Comparator.naturalOrder()); // ids = temps zero-paddes => chronologique
                        int remove = keys.size() - backupMaxPerPlayer;
                        for (int i = 0; i < remove; i++) cfg.set("snapshots." + keys.get(i), null);
                    }
                }

                cfg.set("lastName", name);
                cfg.set("uuid", u.toString());
                cfg.save(f);
            } catch (Throwable t) {
                getLogger().warning("[StaffMode] ecriture backup KO pour " + name + " : " + t);
            }
        }
    }

    private static final class Snapshot {
        String id, reason, name, gamemode;
        long time;
        int level;
        String storage, armor, offhand, ender;
    }

    private List<Snapshot> readSnapshots(UUID u) {
        List<Snapshot> out = new ArrayList<>();
        File f = backupFileFor(u);
        if (!f.exists()) return out;
        YamlConfiguration cfg;
        synchronized (diskLock) { cfg = YamlConfiguration.loadConfiguration(f); }
        ConfigurationSection sec = cfg.getConfigurationSection("snapshots");
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            String b = "snapshots." + id;
            Snapshot s = new Snapshot();
            s.id = id;
            s.time = cfg.getLong(b + ".time");
            s.reason = cfg.getString(b + ".reason", "?");
            s.name = cfg.getString(b + ".name", "?");
            s.gamemode = cfg.getString(b + ".gamemode", "?");
            s.level = cfg.getInt(b + ".level", 0);
            s.storage = cfg.getString(b + ".storage");
            s.armor = cfg.getString(b + ".armor");
            s.offhand = cfg.getString(b + ".offhand");
            s.ender = cfg.getString(b + ".enderchest");
            out.add(s);
        }
        out.sort((a, bb) -> Long.compare(bb.time, a.time)); // plus recent d'abord
        return out;
    }

    private UUID resolveUuid(String target) {
        Player on = Bukkit.getPlayerExact(target);
        if (on != null) return on.getUniqueId();
        try { UUID u = UUID.fromString(target); if (backupFileFor(u).exists()) return u; } catch (Exception ignored) {}
        if (backupDir != null && backupDir.isDirectory()) {
            File[] files = backupDir.listFiles((d, n) -> n.endsWith(".yml"));
            if (files != null) {
                synchronized (diskLock) {
                    for (File f : files) {
                        try {
                            YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
                            String nm = c.getString("lastName");
                            if (nm != null && nm.equalsIgnoreCase(target)) {
                                String base = f.getName().substring(0, f.getName().length() - 4);
                                return UUID.fromString(base);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return null;
    }

    private boolean handleInventoryBackup(CommandSender s, String[] a) {
        if (!(s.isOp() || s.hasPermission(BACKUP_PERM))) {
            send(s, "Commande reservee aux OP.", NamedTextColor.RED);
            return true;
        }
        if (a.length == 0) {
            send(s, "Usage: /inventorybackup <joueur> [numero|latest|undo]", NamedTextColor.GRAY);
            return true;
        }
        String target = a[0];
        UUID u = resolveUuid(target);
        if (u == null) { send(s, "Aucun joueur / backup trouve pour : " + target, NamedTextColor.RED); return true; }

        List<Snapshot> snaps = readSnapshots(u);
        if (snaps.isEmpty()) { send(s, "Aucun backup enregistre pour " + target + ".", NamedTextColor.RED); return true; }

        // Liste.
        if (a.length == 1) {
            send(s, "Backups de " + target + " (" + snaps.size() + ") — du plus recent au plus ancien :", NamedTextColor.YELLOW);
            int i = 1;
            for (Snapshot snap : snaps) {
                if (i > 15) {
                    s.sendMessage(Component.text("  ... " + (snaps.size() - 15) + " de plus.", NamedTextColor.DARK_GRAY));
                    break;
                }
                s.sendMessage(Component.text("  #" + i + " ", NamedTextColor.GOLD)
                        .append(Component.text(ago(snap.time), NamedTextColor.GRAY))
                        .append(Component.text("  [" + snap.reason + "] ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(countItems(snap) + " items", NamedTextColor.AQUA)));
                i++;
            }
            send(s, "Restaurer : /inventorybackup " + target + " <numero>   (ou latest / undo)", NamedTextColor.GRAY);
            return true;
        }

        // Restauration -> le joueur doit etre en ligne.
        Player p = Bukkit.getPlayer(u);
        if (p == null) { send(s, "Le joueur doit etre connecte pour restaurer son inventaire.", NamedTextColor.RED); return true; }

        String arg = a[1].toLowerCase(Locale.ROOT);
        Snapshot chosen;
        if (arg.equals("latest")) {
            chosen = snaps.get(0);
        } else if (arg.equals("undo")) {
            chosen = null;
            for (Snapshot snap : snaps) { if ("pre-restore".equals(snap.reason)) { chosen = snap; break; } }
            if (chosen == null) { send(s, "Aucun point d'annulation (pre-restore) trouve.", NamedTextColor.RED); return true; }
        } else {
            int idx;
            try { idx = Integer.parseInt(arg); } catch (Exception ex) { send(s, "Numero invalide : " + a[1], NamedTextColor.RED); return true; }
            if (idx < 1 || idx > snaps.size()) { send(s, "Numero hors limites (1-" + snaps.size() + ").", NamedTextColor.RED); return true; }
            chosen = snaps.get(idx - 1);
        }

        // Point d'annulation : on sauvegarde l'inventaire actuel AVANT d'ecraser.
        snapshot(p, "pre-restore", true, false);

        try {
            restoreSnapshotToPlayer(p, chosen);
            send(s, "Inventaire de " + p.getName() + " restaure (" + ago(chosen.time) + ", [" + chosen.reason + "]).", NamedTextColor.GREEN);
            send(p, "Un OP a restaure ton inventaire (sauvegarde " + ago(chosen.time) + ").", NamedTextColor.YELLOW);
            getLogger().info("[StaffMode] " + senderName(s) + " a restaure l'inventaire de " + p.getName()
                    + " (snapshot " + chosen.id + ", reason=" + chosen.reason + ").");
        } catch (Throwable t) {
            send(s, "Echec de la restauration : " + t.getMessage(), NamedTextColor.RED);
            getLogger().warning("[StaffMode] restauration KO pour " + p.getName() + " : " + t);
        }
        return true;
    }

    private void restoreSnapshotToPlayer(Player p, Snapshot s) throws Exception {
        ItemStack[] storage = s.storage != null ? fromBase64(s.storage) : new ItemStack[0];
        ItemStack[] armor   = s.armor   != null ? fromBase64(s.armor)   : new ItemStack[0];
        ItemStack[] off     = s.offhand != null ? fromBase64(s.offhand) : new ItemStack[0];
        ItemStack[] ender   = s.ender   != null ? fromBase64(s.ender)   : null;

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);

        applyStorage(p, storage);
        p.getInventory().setArmorContents(fit4(armor));
        p.getInventory().setItemInOffHand(off.length > 0 ? off[0] : null);
        if (ender != null) { try { p.getEnderChest().setContents(ender); } catch (Throwable ignored) {} }
        p.updateInventory();
    }

    private int countItems(Snapshot s) {
        int c = 0;
        c += countNonNull(fromBase64Safe(s.storage));
        c += countNonNull(fromBase64Safe(s.armor));
        c += countNonNull(fromBase64Safe(s.offhand));
        return c;
    }

    private int countNonNull(ItemStack[] arr) {
        if (arr == null) return 0;
        int c = 0;
        for (ItemStack it : arr) if (it != null && it.getType() != Material.AIR) c++;
        return c;
    }

    private String ago(long time) {
        long d = Math.max(0, System.currentTimeMillis() - time);
        long sec = d / 1000, min = sec / 60, h = min / 60, day = h / 24;
        if (day > 0) return "il y a " + day + "j " + (h % 24) + "h";
        if (h > 0)   return "il y a " + h + "h " + (min % 60) + "m";
        if (min > 0) return "il y a " + min + "m";
        return "il y a " + sec + "s";
    }

    private List<String> tabBackup(String[] a) {
        if (a.length == 1) return tabPlayers(a);
        if (a.length == 2) {
            List<String> base = new ArrayList<>(List.of("latest", "undo", "1", "2", "3", "5", "10"));
            String x = a[1].toLowerCase(Locale.ROOT);
            base.removeIf(o -> !o.startsWith(x));
            return base;
        }
        return List.of();
    }

    // ===================== SERIALISATION (base64 robuste) =====================

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
        catch (Throwable t) { getLogger().warning("[StaffMode] lecture backup KO : " + t); return null; }
    }

    private String toBase64Safe(ItemStack[] arr) {
        try { return toBase64(arr == null ? new ItemStack[0] : arr); }
        catch (Throwable t) { getLogger().warning("[StaffMode] serialisation KO : " + t); return null; }
    }

    // ===================== PERSISTANCE (staff : pending + roles) =====================

    private void restoreFromPending(Player p) {
        UUID u = p.getUniqueId();

        // Si des grades staff avaient ete caches mais pas encore remis (ex: crash pendant
        // le mode staff), on les remet ici a la reconnexion.
        if (roleEnabled && luckPermsPresent) {
            clearToggledGrades(p);
            restoreStaffGrades(p);
        }

        ItemStack[] inv = savedInv.get(u), armor = savedArmor.get(u);
        ItemStack off = savedOff.get(u);

        boolean hasData = (inv != null && !allNull(inv))
                       || (armor != null && !allNull(armor))
                       || (off != null && off.getType() != Material.AIR);

        if (!hasData) {
            // Aucune donnee valide -> on NE TOUCHE PAS a l'inventaire reel du joueur.
            pendingRestore.remove(u);
            staffMode.remove(u);
            savedInv.remove(u); savedArmor.remove(u); savedOff.remove(u);
            getLogger().warning("[StaffMode] restauration ignoree pour " + p.getName()
                    + " : aucune donnee valide (inventaire actuel laisse INTACT). "
                    + "Utilise /inventorybackup " + p.getName() + " pour recuperer une sauvegarde.");
            persist();
            return;
        }

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        if (inv != null) applyStorage(p, inv);
        if (armor != null) p.getInventory().setArmorContents(fit4(armor));
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

    /** Redondance staff : inv-backups.yml (base64). */
    private void writeBackup(UUID u, String name, ItemStack[] storage, ItemStack[] armor, ItemStack off) {
        if (backupFile == null) return;
        synchronized (diskLock) {
            YamlConfiguration cfg = backupFile.exists()
                    ? YamlConfiguration.loadConfiguration(backupFile) : new YamlConfiguration();
            String path = "backup." + u;
            cfg.set(path + ".name", name);
            cfg.set(path + ".time", System.currentTimeMillis());
            cfg.set(path + ".storage", toBase64Safe(storage));
            cfg.set(path + ".armor", toBase64Safe(armor));
            cfg.set(path + ".offhand", toBase64Safe(new ItemStack[]{ off }));
            try {
                File dir = backupFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                cfg.save(backupFile);
            } catch (IOException e) {
                getLogger().warning("[StaffMode] backup staff KO : " + e.getMessage());
            }
        }
    }

    private void clearBackup(UUID u) {
        if (backupFile == null || !backupFile.exists()) return;
        synchronized (diskLock) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(backupFile);
            if (cfg.contains("backup." + u)) {
                cfg.set("backup." + u, null);
                try { cfg.save(backupFile); } catch (IOException ignored) {}
            }
        }
    }

    private void loadPending() {
        savedInv.clear(); savedArmor.clear(); savedOff.clear(); pendingRestore.clear();
        previousRoles.clear(); previousPrimary.clear(); fallbackApplied.clear(); staffMembers.clear();
        gradeToggled.clear();
        if (file == null || !file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection cs = cfg.getConfigurationSection("pending");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    ItemStack[] storage = fromBase64Safe(cfg.getString("pending." + key + ".storage"));
                    ItemStack[] armor   = fromBase64Safe(cfg.getString("pending." + key + ".armor"));
                    ItemStack[] offArr  = fromBase64Safe(cfg.getString("pending." + key + ".offhand"));

                    boolean any = false;
                    if (storage != null) { savedInv.put(u, storage); any = true; }
                    if (armor != null)   { savedArmor.put(u, armor); any = true; }
                    if (offArr != null && offArr.length > 0 && offArr[0] != null) { savedOff.put(u, offArr[0]); any = true; }

                    if (any) pendingRestore.add(u);
                    else getLogger().warning("[StaffMode] entree pending illisible pour " + key
                            + " -> ignoree (aucun inventaire ne sera efface).");
                } catch (Throwable t) {
                    getLogger().warning("[StaffMode] pending KO pour " + key + " : " + t);
                }
            }
        }

        ConfigurationSection rs = cfg.getConfigurationSection("roles");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    List<String> groups = cfg.getStringList("roles." + key);
                    if (groups != null && !groups.isEmpty()) {
                        previousRoles.put(u, new ArrayList<>(groups));
                    } else {
                        // Compat ancien format (un seul groupe stocke en String).
                        String single = cfg.getString("roles." + key);
                        if (single != null && !single.isEmpty()) {
                            List<String> one = new ArrayList<>();
                            one.add(single);
                            previousRoles.put(u, one);
                        }
                    }
                } catch (Throwable t) { getLogger().warning("[StaffMode] role KO pour " + key + " : " + t); }
            }
        }

        ConfigurationSection ps = cfg.getConfigurationSection("primary");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    String g = cfg.getString("primary." + key);
                    if (g != null && !g.isEmpty()) previousPrimary.put(u, g);
                } catch (Throwable ignored) {}
            }
        }

        for (String s : cfg.getStringList("fallbackApplied")) {
            try { fallbackApplied.add(UUID.fromString(s)); } catch (Throwable ignored) {}
        }

        ConfigurationSection gt = cfg.getConfigurationSection("gradeToggled");
        if (gt != null) {
            for (String key : gt.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    List<String> gs = cfg.getStringList("gradeToggled." + key);
                    if (gs != null && !gs.isEmpty()) {
                        Set<String> set = ConcurrentHashMap.newKeySet();
                        set.addAll(gs);
                        gradeToggled.put(u, set);
                    }
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
        keys.addAll(savedOff.keySet());
        for (UUID u : keys) {
            String path = "pending." + u;
            if (savedInv.get(u) != null)   cfg.set(path + ".storage", toBase64Safe(savedInv.get(u)));
            if (savedArmor.get(u) != null) cfg.set(path + ".armor",   toBase64Safe(savedArmor.get(u)));
            if (savedOff.get(u) != null)   cfg.set(path + ".offhand", toBase64Safe(new ItemStack[]{ savedOff.get(u) }));
        }

        for (Map.Entry<UUID, List<String>> e : previousRoles.entrySet()) cfg.set("roles." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, String> e : previousPrimary.entrySet()) cfg.set("primary." + e.getKey(), e.getValue());

        List<String> fb = new ArrayList<>();
        for (UUID u : fallbackApplied) fb.add(u.toString());
        cfg.set("fallbackApplied", fb);

        for (Map.Entry<UUID, Set<String>> e : gradeToggled.entrySet()) {
            cfg.set("gradeToggled." + e.getKey(), new ArrayList<>(e.getValue()));
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

package fr.clan.command;

import fr.clan.ClanPlugin;
import fr.clan.gui.ClanGUI;
import fr.clan.manager.ClanManager;
import fr.clan.model.Clan;
import fr.clan.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ClanCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "&6&lClans &8» &r";

    private final ClanPlugin plugin;
    private final ClanManager manager;

    public ClanCommand(ClanPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getClanManager();
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(Text.c(PREFIX + m));
    }

    private void broadcast(Clan clan, String message) {
        for (UUID u : clan.getMembers()) {
            Player online = Bukkit.getPlayer(u);
            if (online != null) online.sendMessage(Text.c(PREFIX + message));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                msg(sender, "&cCette commande est reservee aux joueurs.");
                return true;
            }
            Clan clan = manager.getClanOf(p.getUniqueId());
            if (clan == null) {
                msg(p, "&cTu n'es dans aucun clan. Utilise &e/clan create <nom> &cpour en creer un.");
                return true;
            }
            ClanGUI.openMain(plugin, p, clan);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create":
            case "creer":
                return handleCreate(sender, args);
            case "invite":
            case "inviter":
                return handleInvite(sender, args);
            case "accept":
            case "accepter":
                return handleAccept(sender, args);
            case "deny":
            case "refuser":
                return handleDeny(sender, args);
            case "leave":
            case "quitter":
                return handleLeave(sender);
            case "dissoudre":
            case "disband":
                return handleDisband(sender, args);
            case "info":
                return handleInfo(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&cCette commande est reservee aux joueurs.");
            return true;
        }
        if (args.length < 2) {
            msg(p, "&cUsage: &e/clan create <nom>");
            return true;
        }
        String name = args[1];
        switch (manager.createClan(p, name)) {
            case SUCCESS -> msg(p, "&aClan &e" + name + " &acree avec succes ! Tu en es le chef.");
            case ALREADY_IN_CLAN -> msg(p, "&cTu es deja dans un clan.");
            case NAME_TAKEN -> msg(p, "&cCe nom de clan est deja pris.");
            case INVALID_NAME -> msg(p, "&cNom invalide (3 a 16 caracteres : lettres, chiffres, _).");
        }
        return true;
    }

    private boolean handleInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&cCette commande est reservee aux joueurs.");
            return true;
        }
        if (args.length < 2) {
            msg(p, "&cUsage: &e/clan invite <joueur>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(p, "&cCe joueur n'est pas connecte.");
            return true;
        }
        Clan clan = manager.getClanOf(p.getUniqueId());
        switch (manager.invite(p, target)) {
            case SUCCESS -> {
                msg(p, "&aTu as invite &e" + target.getName() + " &adans le clan.");
                msg(target, "&eTu as ete invite dans le clan &6" + clan.getName() + "&e !");
                target.sendMessage(Text.c(PREFIX + "&7Tape &a/clan accept " + clan.getName()
                        + " &7pour rejoindre, ou &c/clan deny " + clan.getName() + "&7."));
            }
            case NO_CLAN -> msg(p, "&cTu n'es dans aucun clan.");
            case NO_PERMISSION -> msg(p, "&cSeul le chef ou un bras droit peut inviter.");
            case CLAN_FULL -> msg(p, "&cLe clan est plein (" + ClanManager.MAX_MEMBERS + " membres max).");
            case TARGET_IN_CLAN -> msg(p, "&cCe joueur est deja dans un clan.");
            case ALREADY_INVITED -> msg(p, "&cCe joueur a deja une invitation en attente.");
            case TARGET_SELF -> msg(p, "&cTu ne peux pas t'inviter toi-meme.");
        }
        return true;
    }

    private boolean handleAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&cCette commande est reservee aux joueurs.");
            return true;
        }
        String name = args.length >= 2 ? args[1] : null;
        java.util.Set<String> pending = manager.getInvites(p.getUniqueId());
        if (pending.isEmpty()) {
            msg(p, "&cTu n'as aucune invitation en attente.");
            return true;
        }
        if (name == null && pending.size() > 1) {
            msg(p, "&cTu as plusieurs invitations. Precise: &e/clan accept <nom>");
            msg(p, "&7Invitations: &f" + String.join(", ", pending));
            return true;
        }
        switch (manager.accept(p, name)) {
            case SUCCESS -> {
                Clan clan = manager.getClanOf(p.getUniqueId());
                msg(p, "&aTu as rejoint le clan &e" + clan.getName() + " &a!");
                broadcast(clan, "&e" + p.getName() + " &aa rejoint le clan !");
            }
            case NO_INVITE -> msg(p, "&cAucune invitation pour ce clan.");
            case CLAN_GONE -> msg(p, "&cCe clan n'existe plus.");
            case CLAN_FULL -> msg(p, "&cCe clan est desormais plein.");
            case ALREADY_IN_CLAN -> msg(p, "&cTu es deja dans un clan.");
        }
        return true;
    }

    private boolean handleDeny(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&cCette commande est reservee aux joueurs.");
            return true;
        }
        String name = args.length >= 2 ? args[1] : null;
        if (manager.deny(p, name)) {
            msg(p, "&7Invitation(s) refusee(s).");
        } else {
            msg(p, "&cAucune invitation a refuser.");
        }
        return true;
    }

    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&cCette commande est reservee aux joueurs.");
            return true;
        }
        Clan clan = manager.getClanOf(p.getUniqueId());
        if (clan == null) {
            msg(p, "&cTu n'es dans aucun clan.");
            return true;
        }
        if (clan.isLeader(p.getUniqueId())) {
            msg(p, "&cTu es le chef. Utilise &e/clan dissoudre " + clan.getName()
                    + " &cpour dissoudre le clan.");
            return true;
        }
        manager.leaveClan(clan, p.getUniqueId());
        msg(p, "&7Tu as quitte le clan &e" + clan.getName() + "&7.");
        broadcast(clan, "&e" + p.getName() + " &7a quitte le clan.");
        return true;
    }

    private boolean handleDisband(CommandSender sender, String[] args) {
        boolean staff = sender.hasPermission("clan.staff");
        Clan target;

        if (args.length >= 2) {
            target = manager.getClan(args[1]);
            if (target == null) {
                msg(sender, "&cAucun clan nomme &e" + args[1] + "&c.");
                return true;
            }
            if (!staff) {
                if (!(sender instanceof Player p) || !target.isLeader(p.getUniqueId())) {
                    msg(sender, "&cTu n'as pas la permission de dissoudre ce clan.");
                    return true;
                }
            }
        } else {
            if (!(sender instanceof Player p)) {
                msg(sender, "&cUsage: &e/clan dissoudre <nom>");
                return true;
            }
            target = manager.getClanOf(p.getUniqueId());
            if (target == null || !target.isLeader(p.getUniqueId())) {
                msg(sender, "&cUsage: &e/clan dissoudre <nom>");
                return true;
            }
        }

        String name = target.getName();
        broadcast(target, "&cLe clan &e" + name + " &ca ete dissous.");
        manager.disband(target);
        msg(sender, "&aLe clan &e" + name + " &aa ete dissous.");
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("clan.staff")) {
            msg(sender, "&cTu n'as pas la permission (&eclan.staff&c).");
            return true;
        }
        if (args.length < 2) {
            msg(sender, "&cUsage: &e/clan info <joueur|clan>");
            return true;
        }
        String arg = args[1];

        Clan clan = manager.getClan(arg);
        if (clan != null) {
            sendClanInfo(sender, clan);
            return true;
        }

        UUID uuid = manager.getUUIDByName(arg);
        if (uuid == null) {
            msg(sender, "&cAucun clan ni joueur connu nomme &e" + arg + "&c.");
            return true;
        }
        Clan pClan = manager.getClanOf(uuid);
        if (pClan == null) {
            msg(sender, "&e" + manager.getName(uuid) + " &7n'est dans aucun clan.");
            return true;
        }
        msg(sender, "&e" + manager.getName(uuid) + " &7est dans le clan &6" + pClan.getName()
                + " &7(" + roleName(pClan.getRole(uuid)) + "&7).");
        sendClanInfo(sender, pClan);
        return true;
    }

    private void sendClanInfo(CommandSender s, Clan clan) {
        s.sendMessage(Text.c("&8&m--------------------------------"));
        s.sendMessage(Text.c("&6&lClan: &e" + clan.getName() + " &7(" + clan.size()
                + "/" + ClanManager.MAX_MEMBERS + ")"));
        s.sendMessage(Text.c("&7Chef: &f" + manager.getName(clan.getLeader())));

        List<String> officers = new ArrayList<>();
        for (UUID u : clan.getOfficers()) officers.add(manager.getName(u));
        s.sendMessage(Text.c("&7Bras droits: &f" + (officers.isEmpty() ? "aucun" : String.join(", ", officers))));

        List<String> members = new ArrayList<>();
        for (UUID u : clan.getMembers()) {
            if (clan.isLeader(u) || clan.isOfficer(u)) continue;
            members.add(manager.getName(u));
        }
        s.sendMessage(Text.c("&7Membres: &f" + (members.isEmpty() ? "aucun" : String.join(", ", members))));
        s.sendMessage(Text.c("&8&m--------------------------------"));
    }

    private String roleName(Clan.Role role) {
        if (role == null) return "&7?";
        return switch (role) {
            case LEADER -> "&6Chef";
            case OFFICER -> "&bBras droit";
            case MEMBER -> "&fMembre";
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.c("&8&m--------------------------------"));
        sender.sendMessage(Text.c("&6&lClans &7- Aide"));
        sender.sendMessage(Text.c("&e/clan &7- Ouvre le menu de ton clan"));
        sender.sendMessage(Text.c("&e/clan create <nom> &7- Cree un clan"));
        sender.sendMessage(Text.c("&e/clan invite <joueur> &7- Invite un joueur (max " + ClanManager.MAX_MEMBERS + ")"));
        sender.sendMessage(Text.c("&e/clan accept [nom] &7- Accepte une invitation"));
        sender.sendMessage(Text.c("&e/clan deny [nom] &7- Refuse une invitation"));
        sender.sendMessage(Text.c("&e/clan leave &7- Quitte ton clan"));
        if (sender.hasPermission("clan.staff")) {
            sender.sendMessage(Text.c("&c/clan dissoudre <nom> &7- (staff) Dissout un clan"));
            sender.sendMessage(Text.c("&c/clan info <joueur|clan> &7- (staff) Infos"));
        }
        sender.sendMessage(Text.c("&8&m--------------------------------"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "create", "invite", "accept", "deny", "leave", "help"));
            if (sender.hasPermission("clan.staff")) {
                subs.add("dissoudre");
                subs.add("info");
            } else if (sender instanceof Player p) {
                Clan c = manager.getClanOf(p.getUniqueId());
                if (c != null && c.isLeader(p.getUniqueId())) subs.add("dissoudre");
            }
            return filter(subs, args[0]);
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "invite":
                    return filter(onlineNames(), args[1]);
                case "accept":
                case "deny":
                    if (sender instanceof Player p) {
                        return filter(new ArrayList<>(manager.getInvites(p.getUniqueId())), args[1]);
                    }
                    return Collections.emptyList();
                case "dissoudre":
                    if (sender.hasPermission("clan.staff")) return filter(clanNames(), args[1]);
                    if (sender instanceof Player p) {
                        Clan c = manager.getClanOf(p.getUniqueId());
                        if (c != null && c.isLeader(p.getUniqueId())) {
                            return filter(List.of(c.getName()), args[1]);
                        }
                    }
                    return Collections.emptyList();
                case "info":
                    if (sender.hasPermission("clan.staff")) {
                        List<String> all = new ArrayList<>(clanNames());
                        all.addAll(onlineNames());
                        return filter(all, args[1]);
                    }
                    return Collections.emptyList();
                default:
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private List<String> onlineNames() {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    private List<String> clanNames() {
        List<String> l = new ArrayList<>();
        for (Clan c : manager.getClans()) l.add(c.getName());
        return l;
    }

    private List<String> filter(List<String> options, String start) {
        String s = start.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(s)) out.add(o);
        }
        return out;
    }
}

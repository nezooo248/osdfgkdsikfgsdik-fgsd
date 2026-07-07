package fr.clan.manager;

import fr.clan.ClanPlugin;
import fr.clan.model.Clan;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClanManager {

    public static final int MAX_MEMBERS = 5;

    private final ClanPlugin plugin;
    private final File file;

    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, String> playerClan = new HashMap<>();
    private final Map<UUID, Set<String>> invites = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public ClanManager(ClanPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clans.yml");
    }

    public enum CreateResult { SUCCESS, ALREADY_IN_CLAN, NAME_TAKEN, INVALID_NAME }
    public enum InviteResult { SUCCESS, NO_CLAN, NO_PERMISSION, CLAN_FULL, TARGET_IN_CLAN, ALREADY_INVITED, TARGET_SELF }
    public enum JoinResult { SUCCESS, NO_INVITE, CLAN_GONE, CLAN_FULL, ALREADY_IN_CLAN }

    public Clan getClan(String name) {
        if (name == null) return null;
        return clans.get(name.toLowerCase(Locale.ROOT));
    }

    public Clan getClanOf(UUID uuid) {
        String key = playerClan.get(uuid);
        return key == null ? null : clans.get(key);
    }

    public boolean hasClan(UUID uuid) { return playerClan.containsKey(uuid); }

    public Collection<Clan> getClans() { return clans.values(); }

    public Set<String> getInvites(UUID uuid) {
        return invites.getOrDefault(uuid, Collections.emptySet());
    }

    public String getName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    public void updateName(Player player) {
        names.put(player.getUniqueId(), player.getName());
    }

    public UUID getUUIDByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    public CreateResult createClan(Player player, String name) {
        if (hasClan(player.getUniqueId())) return CreateResult.ALREADY_IN_CLAN;
        if (!isValidName(name)) return CreateResult.INVALID_NAME;
        if (clans.containsKey(name.toLowerCase(Locale.ROOT))) return CreateResult.NAME_TAKEN;

        Clan clan = new Clan(name, player.getUniqueId());
        clans.put(name.toLowerCase(Locale.ROOT), clan);
        playerClan.put(player.getUniqueId(), name.toLowerCase(Locale.ROOT));
        updateName(player);
        save();
        return CreateResult.SUCCESS;
    }

    public InviteResult invite(Player inviter, Player target) {
        Clan clan = getClanOf(inviter.getUniqueId());
        if (clan == null) return InviteResult.NO_CLAN;
        if (!clan.canInvite(inviter.getUniqueId())) return InviteResult.NO_PERMISSION;
        if (inviter.getUniqueId().equals(target.getUniqueId())) return InviteResult.TARGET_SELF;
        if (clan.size() >= MAX_MEMBERS) return InviteResult.CLAN_FULL;
        if (hasClan(target.getUniqueId())) return InviteResult.TARGET_IN_CLAN;

        Set<String> set = invites.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>());
        if (!set.add(clan.getName().toLowerCase(Locale.ROOT))) return InviteResult.ALREADY_INVITED;

        updateName(inviter);
        updateName(target);
        return InviteResult.SUCCESS;
    }

    public JoinResult accept(Player player, String clanName) {
        if (hasClan(player.getUniqueId())) return JoinResult.ALREADY_IN_CLAN;

        Set<String> set = invites.get(player.getUniqueId());
        if (set == null || set.isEmpty()) return JoinResult.NO_INVITE;

        String key;
        if (clanName == null) {
            if (set.size() == 1) key = set.iterator().next();
            else return JoinResult.NO_INVITE;
        } else {
            key = clanName.toLowerCase(Locale.ROOT);
            if (!set.contains(key)) return JoinResult.NO_INVITE;
        }

        Clan clan = clans.get(key);
        if (clan == null) {
            set.remove(key);
            return JoinResult.CLAN_GONE;
        }
        if (clan.size() >= MAX_MEMBERS) return JoinResult.CLAN_FULL;

        clan.addMember(player.getUniqueId());
        playerClan.put(player.getUniqueId(), key);
        invites.remove(player.getUniqueId());
        updateName(player);
        save();
        return JoinResult.SUCCESS;
    }

    public boolean deny(Player player, String clanName) {
        Set<String> set = invites.get(player.getUniqueId());
        if (set == null || set.isEmpty()) return false;
        if (clanName == null) {
            invites.remove(player.getUniqueId());
            return true;
        }
        boolean removed = set.remove(clanName.toLowerCase(Locale.ROOT));
        if (set.isEmpty()) invites.remove(player.getUniqueId());
        return removed;
    }

    public boolean kick(Clan clan, UUID actor, UUID target) {
        if (!clan.isMember(target)) return false;
        if (clan.isLeader(target)) return false;
        if (target.equals(actor)) return false;

        if (clan.isLeader(actor)) {
            // ok
        } else if (clan.isOfficer(actor)) {
            if (clan.isOfficer(target)) return false;
        } else {
            return false;
        }

        clan.removeMember(target);
        playerClan.remove(target);
        save();
        return true;
    }

    public boolean promote(Clan clan, UUID actor, UUID target) {
        if (!clan.canManageRoles(actor)) return false;
        if (!clan.isMember(target)) return false;
        if (clan.isLeader(target) || clan.isOfficer(target)) return false;
        clan.promote(target);
        save();
        return true;
    }

    public boolean demote(Clan clan, UUID actor, UUID target) {
        if (!clan.canManageRoles(actor)) return false;
        if (!clan.isOfficer(target)) return false;
        clan.demote(target);
        save();
        return true;
    }

    public void leaveClan(Clan clan, UUID uuid) {
        clan.removeMember(uuid);
        playerClan.remove(uuid);
        save();
    }

    public void disband(Clan clan) {
        String key = clan.getName().toLowerCase(Locale.ROOT);
        for (UUID u : clan.getMembers()) {
            playerClan.remove(u);
        }
        clans.remove(key);
        for (Set<String> set : invites.values()) {
            set.remove(key);
        }
        save();
    }

    public void load() {
        clans.clear();
        playerClan.clear();
        names.clear();

        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection clansSection = cfg.getConfigurationSection("clans");
        if (clansSection != null) {
            for (String name : clansSection.getKeys(false)) {
                String path = "clans." + name;
                String leaderStr = cfg.getString(path + ".leader");
                if (leaderStr == null) continue;
                try {
                    UUID leader = UUID.fromString(leaderStr);
                    Clan clan = new Clan(name, leader);
                    for (String m : cfg.getStringList(path + ".members")) {
                        clan.addMember(UUID.fromString(m));
                    }
                    for (String o : cfg.getStringList(path + ".officers")) {
                        clan.getOfficers().add(UUID.fromString(o));
                    }
                    clans.put(name.toLowerCase(Locale.ROOT), clan);
                    for (UUID u : clan.getMembers()) {
                        playerClan.put(u, name.toLowerCase(Locale.ROOT));
                    }
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Clan invalide ignore : " + name);
                }
            }
        }

        ConfigurationSection namesSection = cfg.getConfigurationSection("names");
        if (namesSection != null) {
            for (String key : namesSection.getKeys(false)) {
                try {
                    names.put(UUID.fromString(key), namesSection.getString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        plugin.getLogger().info("Charges : " + clans.size() + " clan(s).");
    }

    public void save() {
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

        for (Map.Entry<UUID, String> e : names.entrySet()) {
            cfg.set("names." + e.getKey(), e.getValue());
        }

        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder clans.yml : " + e.getMessage());
        }
    }
}

package fr.clan;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public class ClanExpansion extends PlaceholderExpansion {
    private final ClanPlugin plugin;
    public ClanExpansion(ClanPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "clan"; }
    @Override public String getAuthor() { return "ClanPlugin"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        Clan clan = plugin.getClanManager().getClanOf(player.getUniqueId());
        switch (params.toLowerCase()) {
            case "name": return clan == null ? "" : clan.getName();
            case "size": return clan == null ? "0" : String.valueOf(clan.size());
            case "hasclan": return clan == null ? "false" : "true";
            default: return null;
        }
    }
}

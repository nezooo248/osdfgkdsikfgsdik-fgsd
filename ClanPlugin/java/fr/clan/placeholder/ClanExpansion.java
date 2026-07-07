package fr.clan.placeholder;

import fr.clan.ClanPlugin;
import fr.clan.model.Clan;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public class ClanExpansion extends PlaceholderExpansion {

    private final ClanPlugin plugin;

    public ClanExpansion(ClanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "clan"; }

    @Override
    public String getAuthor() { return "ClanPlugin"; }

    @Override
    public String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";

        Clan clan = plugin.getClanManager().getClanOf(player.getUniqueId());

        switch (params.toLowerCase()) {
            case "name":
                return clan == null ? "" : clan.getName();
            case "role":
                if (clan == null) return "";
                Clan.Role role = clan.getRole(player.getUniqueId());
                if (role == null) return "";
                return switch (role) {
                    case LEADER -> "Chef";
                    case OFFICER -> "Bras droit";
                    case MEMBER -> "Membre";
                };
            case "size":
                return clan == null ? "0" : String.valueOf(clan.size());
            case "hasclan":
                return clan == null ? "false" : "true";
            default:
                return null;
        }
    }
}

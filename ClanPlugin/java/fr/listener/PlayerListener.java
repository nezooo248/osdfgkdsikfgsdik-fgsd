package fr.clan.listener;

import fr.clan.ClanPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Met a jour le pseudo connu du joueur a la connexion (pour /clan info hors ligne).
 */
public class PlayerListener implements Listener {

    private final ClanPlugin plugin;

    public PlayerListener(ClanPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getClanManager().updateName(e.getPlayer());
    }
}

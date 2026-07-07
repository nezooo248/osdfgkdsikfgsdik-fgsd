package fr.clan;

import fr.clan.command.ClanCommand;
import fr.clan.gui.GUIListener;
import fr.clan.listener.PlayerListener;
import fr.clan.manager.ClanManager;
import fr.clan.placeholder.ClanExpansion;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ClanPlugin extends JavaPlugin {

    private ClanManager clanManager;

    @Override
    public void onEnable() {
        this.clanManager = new ClanManager(this);
        this.clanManager.load();

        PluginCommand cmd = getCommand("clan");
        if (cmd != null) {
            ClanCommand executor = new ClanCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ClanExpansion(this).register();
            getLogger().info("Hook PlaceholderAPI active (%clan_name%, %clan_role%, %clan_size%).");
        } else {
            getLogger().info("PlaceholderAPI absent : placeholders desactives.");
        }

        getLogger().info("ClanPlugin active.");
    }

    @Override
    public void onDisable() {
        if (clanManager != null) {
            clanManager.save();
        }
        getLogger().info("ClanPlugin desactive.");
    }

    public ClanManager getClanManager() {
        return clanManager;
    }
}

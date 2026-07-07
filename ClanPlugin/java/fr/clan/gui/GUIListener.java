package fr.clan.gui;

import fr.clan.ClanPlugin;
import fr.clan.manager.ClanManager;
import fr.clan.model.Clan;
import fr.clan.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class GUIListener implements Listener {

    private static final String PREFIX = "&6&lClans &8» &r";
    private final ClanPlugin plugin;

    public GUIListener(ClanPlugin plugin) {
        this.plugin = plugin;
    }

    private void msg(Player p, String m) {
        p.sendMessage(Text.c(PREFIX + m));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof ClanMenuHolder || holder instanceof MemberMenuHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof ClanMenuHolder) && !(holder instanceof MemberMenuHolder)) {
            return;
        }
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) {
            return;
        }

        ClanManager manager = plugin.getClanManager();

        if (holder instanceof ClanMenuHolder h) {
            handleMainMenu(p, manager, h, e.getSlot());
        } else if (holder instanceof MemberMenuHolder h) {
            handleMemberMenu(p, manager, h, e.getSlot());
        }
    }

    private void handleMainMenu(Player p, ClanManager manager, ClanMenuHolder h, int slot) {
        Clan clan = manager.getClan(h.getClanName());
        if (clan == null) {
            p.closeInventory();
            return;
        }
        UUID target = h.getSlotToMember().get(slot);
        if (target == null) return;
        if (!ClanGUI.canManage(clan, p.getUniqueId(), target)) return;

        ClanGUI.openMember(plugin, p, clan, target);
    }

    private void handleMemberMenu(Player p, ClanManager manager, MemberMenuHolder h, int slot) {
        Clan clan = manager.getClan(h.getClanName());
        if (clan == null) {
            p.closeInventory();
            return;
        }
        UUID target = h.getTarget();

        if (slot == ClanGUI.BACK_SLOT) {
            ClanGUI.openMain(plugin, p, clan);
            return;
        }

        if (slot == ClanGUI.KICK_SLOT) {
            String targetName = manager.getName(target);
            if (manager.kick(clan, p.getUniqueId(), target)) {
                msg(p, "&aTu as exclu &e" + targetName + " &adu clan.");
                Player kicked = Bukkit.getPlayer(target);
                if (kicked != null) {
                    kicked.sendMessage(Text.c(PREFIX + "&cTu as ete exclu du clan &e" + clan.getName() + "&c."));
                }
                broadcast(clan, "&e" + targetName + " &7a ete exclu du clan.");
                ClanGUI.openMain(plugin, p, clan);
            } else {
                msg(p, "&cImpossible d'exclure ce membre.");
            }
            return;
        }

        if (slot == ClanGUI.ROLE_SLOT) {
            Clan.Role role = clan.getRole(target);
            String targetName = manager.getName(target);
            if (role == Clan.Role.MEMBER) {
                if (manager.promote(clan, p.getUniqueId(), target)) {
                    msg(p, "&aTu as promu &e" + targetName + " &abras droit.");
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) t.sendMessage(Text.c(PREFIX + "&aTu es maintenant &bbras droit &adu clan !"));
                }
            } else if (role == Clan.Role.OFFICER) {
                if (manager.demote(clan, p.getUniqueId(), target)) {
                    msg(p, "&eTu as repasse &f" + targetName + " &emembre simple.");
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) t.sendMessage(Text.c(PREFIX + "&7Tu es de nouveau &fmembre &7du clan."));
                }
            }
            ClanGUI.openMember(plugin, p, clan, target);
        }
    }

    private void broadcast(Clan clan, String message) {
        for (UUID u : clan.getMembers()) {
            Player online = Bukkit.getPlayer(u);
            if (online != null) online.sendMessage(Text.c(PREFIX + message));
        }
    }
}

package fr.clan.gui;

import fr.clan.ClanPlugin;
import fr.clan.manager.ClanManager;
import fr.clan.model.Clan;
import fr.clan.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClanGUI {

    private static final int INFO_SLOT = 4;
    private static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23};

    public static final int HEAD_SLOT = 4;
    public static final int KICK_SLOT = 11;
    public static final int ROLE_SLOT = 15;
    public static final int BACK_SLOT = 22;

    private ClanGUI() {}

    public static void openMain(ClanPlugin plugin, Player viewer, Clan clan) {
        ClanManager manager = plugin.getClanManager();

        ClanMenuHolder holder = new ClanMenuHolder(clan.getName());
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&6Clan: &e" + clan.getName()));
        holder.setInventory(inv);

        fill(inv);
        inv.setItem(INFO_SLOT, infoItem(clan, manager));

        boolean canManage = clan.isLeader(viewer.getUniqueId()) || clan.isOfficer(viewer.getUniqueId());

        List<UUID> ordered = new ArrayList<>();
        ordered.add(clan.getLeader());
        for (UUID u : clan.getOfficers()) if (!ordered.contains(u)) ordered.add(u);
        for (UUID u : clan.getMembers()) if (!ordered.contains(u)) ordered.add(u);

        int i = 0;
        for (UUID u : ordered) {
            if (i >= MEMBER_SLOTS.length) break;
            int slot = MEMBER_SLOTS[i];

            List<String> lore = new ArrayList<>();
            lore.add(Text.c("&7Grade: " + roleColored(clan.getRole(u))));
            if (canManage && canManage(clan, viewer.getUniqueId(), u)) {
                lore.add(Text.c("&8"));
                lore.add(Text.c("&eClic &7pour gerer ce membre"));
            }

            inv.setItem(slot, head(manager.getName(u), roleColored(clan.getRole(u)), lore, u));
            holder.getSlotToMember().put(slot, u);
            i++;
        }

        viewer.openInventory(inv);
    }

    private static ItemStack infoItem(Clan clan, ClanManager manager) {
        List<String> lore = new ArrayList<>();
        lore.add(Text.c("&7Chef: &f" + manager.getName(clan.getLeader())));
        lore.add(Text.c("&7Membres: &f" + clan.size() + "&7/&f" + ClanManager.MAX_MEMBERS));
        lore.add(Text.c("&7Bras droits: &f" + clan.getOfficers().size()));
        return item(Material.OAK_SIGN, "&6&lClan " + clan.getName(), lore);
    }

    public static void openMember(ClanPlugin plugin, Player viewer, Clan clan, UUID target) {
        ClanManager manager = plugin.getClanManager();

        MemberMenuHolder holder = new MemberMenuHolder(clan.getName(), target);
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&6Gerer: &e" + manager.getName(target)));
        holder.setInventory(inv);

        fill(inv);

        Clan.Role targetRole = clan.getRole(target);
        boolean isLeader = clan.isLeader(viewer.getUniqueId());
        boolean isOfficer = clan.isOfficer(viewer.getUniqueId());

        List<String> headLore = new ArrayList<>();
        headLore.add(Text.c("&7Grade: " + roleColored(targetRole)));
        inv.setItem(HEAD_SLOT, head(manager.getName(target), roleColored(targetRole), headLore, target));

        boolean canKick = (isLeader && targetRole != Clan.Role.LEADER)
                || (isOfficer && targetRole == Clan.Role.MEMBER);
        if (canKick) {
            inv.setItem(KICK_SLOT, item(Material.BARRIER, "&c&lExclure",
                    List.of(Text.c("&7Retire ce joueur du clan."))));
        }

        if (isLeader) {
            if (targetRole == Clan.Role.MEMBER) {
                inv.setItem(ROLE_SLOT, item(Material.EMERALD, "&a&lPromouvoir bras droit",
                        List.of(Text.c("&7Le bras droit peut inviter"),
                                Text.c("&7et exclure des membres."))));
            } else if (targetRole == Clan.Role.OFFICER) {
                inv.setItem(ROLE_SLOT, item(Material.GRAY_DYE, "&e&lRepasser membre",
                        List.of(Text.c("&7Retire le grade de bras droit."))));
            }
        }

        inv.setItem(BACK_SLOT, item(Material.ARROW, "&7Retour", List.of()));

        viewer.openInventory(inv);
    }

    public static boolean canManage(Clan clan, UUID viewer, UUID target) {
        if (viewer.equals(target)) return false;
        Clan.Role vr = clan.getRole(viewer);
        Clan.Role tr = clan.getRole(target);
        if (vr == null || tr == null) return false;
        if (tr == Clan.Role.LEADER) return false;
        if (vr == Clan.Role.LEADER) return true;
        if (vr == Clan.Role.OFFICER) return tr == Clan.Role.MEMBER;
        return false;
    }

    private static void fill(Inventory inv) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&r", List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack is = new ItemStack(material);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.c(name));
            if (!lore.isEmpty()) meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static ItemStack head(String playerName, String roleColored, List<String> lore, UUID owner) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) is.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            meta.setDisplayName(Text.c("&e" + playerName + " &7- " + roleColored));
            if (!lore.isEmpty()) meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static String roleColored(Clan.Role role) {
        if (role == null) return "&7?";
        return switch (role) {
            case LEADER -> "&6Chef";
            case OFFICER -> "&bBras droit";
            case MEMBER -> "&fMembre";
        };
    }
}

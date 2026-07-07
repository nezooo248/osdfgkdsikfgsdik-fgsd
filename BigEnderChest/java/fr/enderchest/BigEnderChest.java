package fr.enderchest;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * BigEnderChest - a mettre dans : fr/enderchest/BigEnderChest.java
 *
 * Remplace le coffre de l'Ender vanilla (27 slots) par un menu DOUBLE COFFRE (54 slots),
 * propre a chaque joueur et sauvegarde sur le disque (un fichier .yml par joueur).
 *
 *  - Clic droit sur un bloc Ender Chest -> ouvre le menu 54 slots.
 *  - /enderchest (alias /ec)            -> ouvre ton menu 54 slots.
 *  - Sauvegarde a la fermeture, a la deconnexion et a l'arret du serveur.
 *
 * Tout le monde peut l'utiliser.
 */
public class BigEnderChest extends LoadedPlugin implements Listener {

    private static final int SIZE = 54; // double coffre
    private static final String PERM_OTHERS = "staff.ec"; // pour voir l'ender chest d'un autre
    private static final Component TITLE =
            Component.text("Coffre de l'Ender", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);

    private File dataFolder;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // evite les listeners fantomes apres /plreload
        dataFolder = new File(getHost().getDataFolder(), "enderchests");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        registerListener(this);
        registerCommand("enderchest", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                open(p); // son propre ender chest
                return true;
            }
            // Ouvrir l'ender chest d'un autre joueur : reserve a staff.ec
            if (!p.hasPermission(PERM_OTHERS)) {
                p.sendMessage(Component.text("Tu n'as pas la permission " + PERM_OTHERS + ".", NamedTextColor.RED));
                return true;
            }
            String targetName = args[0];
            Player online = Bukkit.getPlayerExact(targetName);
            UUID targetId;
            String resolvedName;
            if (online != null) {
                targetId = online.getUniqueId();
                resolvedName = online.getName();
            } else {
                OfflinePlayer off = Bukkit.getOfflinePlayer(targetName);
                if (off == null || off.getUniqueId() == null
                        || (!off.hasPlayedBefore() && !off.isOnline())) {
                    p.sendMessage(Component.text("Joueur introuvable : " + targetName, NamedTextColor.RED));
                    return true;
                }
                targetId = off.getUniqueId();
                resolvedName = off.getName() != null ? off.getName() : targetName;
            }
            open(p, targetId, resolvedName, false);
            p.sendMessage(Component.text("Ouverture de l'ender chest de " + resolvedName + ".", NamedTextColor.LIGHT_PURPLE));
            return true;
        });

        getLogger().info("BigEnderChest active.");
    }

    @Override
    public void onDisable() {
        // On sauvegarde tous les menus encore ouverts avant l'arret.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = topInventory(p);
            if (top != null && top.getHolder() instanceof EnderHolder h) {
                saveContents(h.owner, top.getContents());
                p.closeInventory();
            }
        }
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("BigEnderChest desactive.");
    }

    /** Supprime tout listener BigEnderChest fantome d'un ancien chargement (compare par nom de classe). */
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

    // ===================== OUVERTURE =====================

    /** Ouvre le menu 54 slots du joueur (son propre ender chest). */
    private void open(Player viewer) {
        open(viewer, viewer.getUniqueId(), viewer.getName(), true);
    }

    /** Ouvre le menu 54 slots de 'owner' pour 'viewer' (le contenu est charge depuis le disque). */
    private void open(Player viewer, UUID owner, String ownerName, boolean self) {
        EnderHolder holder = new EnderHolder(owner);
        Component title = self ? TITLE
                : Component.text("Ender de " + ownerName, NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inventory = inv;

        ItemStack[] saved = loadContents(owner);
        if (saved != null) inv.setContents(saved);

        // Migration : une seule fois par joueur, on recupere l'ancien ender chest vanilla (27 slots).
        Player ownerOnline = Bukkit.getPlayer(owner);
        if (ownerOnline != null && !isMigrated(owner)) {
            mergeVanilla(inv, ownerOnline);
            ownerOnline.getEnderChest().clear(); // evite la duplication
            markMigrated(owner, inv.getContents());
        }

        viewer.openInventory(inv);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
    }

    // ---- Clic droit sur le bloc Ender Chest ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // evite le double declenchement
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENDER_CHEST) return;

        Player p = e.getPlayer();
        // Si le joueur est accroupi avec un objet en main, on laisse le comportement vanilla
        // (placer un bloc contre l'ender chest, etc.).
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && hand != null && !hand.getType().isAir()) return;

        e.setCancelled(true); // on annule l'ouverture vanilla (27 slots)
        open(p);
    }

    // ---- Filet : si un autre plugin / une autre source ouvre l'ender chest vanilla ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (e.getInventory().getHolder() instanceof EnderHolder) return; // deja le notre
        if (!(e.getPlayer() instanceof Player p)) return;

        e.setCancelled(true);
        // On ouvre le notre au tick suivant (ouvrir un inventaire pendant InventoryOpenEvent est deconseille).
        try {
            Bukkit.getScheduler().runTask((Plugin) getHost(), () -> open(p));
        } catch (Throwable t) {
            open(p); // repli si le scheduler n'est pas dispo
        }
    }

    // ===================== SAUVEGARDE =====================

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof EnderHolder h) {
            saveContents(h.owner, e.getInventory().getContents());
            if (e.getPlayer() instanceof Player p) {
                p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1f, 1f);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Inventory top = topInventory(p);
        if (top != null && top.getHolder() instanceof EnderHolder h) {
            saveContents(h.owner, top.getContents());
        }
    }

    // ===================== PERSISTANCE (un fichier par joueur) =====================

    private File fileOf(UUID u) {
        return new File(dataFolder, u.toString() + ".yml");
    }

    @SuppressWarnings("unchecked")
    private ItemStack[] loadContents(UUID u) {
        File f = fileOf(u);
        if (!f.exists()) return null;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            List<ItemStack> list = (List<ItemStack>) (List<?>) cfg.getList("contents");
            if (list == null) return null;
            ItemStack[] arr = new ItemStack[SIZE];
            for (int i = 0; i < SIZE && i < list.size(); i++) arr[i] = list.get(i);
            return arr;
        } catch (Throwable t) {
            getLogger().warning("[BigEnderChest] chargement KO pour " + u + " : " + t.getMessage());
            return null;
        }
    }

    private void saveContents(UUID u, ItemStack[] contents) {
        try {
            File f = fileOf(u);
            // On charge l'existant pour ne pas ecraser le marqueur "migrated".
            YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cfg.set("contents", new ArrayList<>(Arrays.asList(contents)));
            if (!dataFolder.exists()) dataFolder.mkdirs();
            cfg.save(f);
        } catch (IOException e) {
            getLogger().warning("[BigEnderChest] sauvegarde KO pour " + u + " : " + e.getMessage());
        }
    }

    /** A deja migre son ancien ender chest vanilla ? */
    private boolean isMigrated(UUID u) {
        File f = fileOf(u);
        if (!f.exists()) return false;
        return YamlConfiguration.loadConfiguration(f).getBoolean("migrated", false);
    }

    /** Marque le joueur comme migre et enregistre le contenu. */
    private void markMigrated(UUID u, ItemStack[] contents) {
        try {
            File f = fileOf(u);
            YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cfg.set("contents", new ArrayList<>(Arrays.asList(contents)));
            cfg.set("migrated", true);
            if (!dataFolder.exists()) dataFolder.mkdirs();
            cfg.save(f);
        } catch (IOException e) {
            getLogger().warning("[BigEnderChest] marquage migration KO pour " + u + " : " + e.getMessage());
        }
    }

    /** Verse les objets de l'ender chest vanilla (27 slots) dans les emplacements libres du menu. */
    private void mergeVanilla(Inventory inv, Player owner) {
        for (ItemStack it : owner.getEnderChest().getContents()) {
            if (it != null && !it.getType().isAir()) inv.addItem(it.clone());
        }
    }

    // ===================== UTILITAIRES =====================

    private Inventory topInventory(Player p) {
        try { return p.getOpenInventory().getTopInventory(); }
        catch (Throwable t) { return null; }
    }

    /** Identifie nos menus + retient le proprietaire pour la sauvegarde. */
    private static final class EnderHolder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        EnderHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}

package fr.bottlexp;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BottleXP - a mettre dans : fr/bottlexp/BottleXP.java
 *
 * /bottlexp  (tout le monde peut l'utiliser)
 *   -> retire TOUTE ton XP et te donne une bouteille contenant ces points.
 *   -> clic droit sur la bouteille = tu recuperes l'XP stockee.
 *
 * L'XP de chaque bouteille est sauvegardee sur le disque dans bottlexp.yml
 * (source de verite), avec une copie dans l'item lui-meme (PDC) en secours.
 */
public class BottleXP extends LoadedPlugin implements Listener {

    // Cle de l'identifiant unique de la bouteille + copie de secours des points.
    private static final NamespacedKey KEY_ID     = new NamespacedKey("bottlexp", "id");
    private static final NamespacedKey KEY_POINTS = new NamespacedKey("bottlexp", "points");

    private File file;
    private YamlConfiguration data;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // evite les listeners fantomes apres /plreload
        loadData();
        registerListener(this);

        registerCommand("bottlexp", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            handleBottle(p);
            return true;
        });

        getLogger().info("BottleXP active.");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        saveData();
        getLogger().info("BottleXP desactive.");
    }

    /** Supprime tout listener BottleXP fantome d'un ancien chargement (compare par nom de classe). */
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

    // ===================== COMMANDE /bottlexp =====================

    private void handleBottle(Player p) {
        int points = getTotalExperiencePoints(p);
        if (points <= 0) {
            p.sendMessage(msg("Tu n'as aucune XP a mettre en bouteille.", NamedTextColor.RED));
            return;
        }
        int level = p.getLevel();

        // On retire TOUTE l'XP du joueur.
        p.setExp(0f);
        p.setLevel(0);
        p.setTotalExperience(0);

        // On cree la bouteille (identifiant unique -> pas de stack entre elles).
        String id = UUID.randomUUID().toString();
        ItemStack bottle = createBottle(id, points, level, p.getName());

        // Sauvegarde disque (source de verite).
        data.set("bottles." + id + ".points", points);
        data.set("bottles." + id + ".level", level);
        data.set("bottles." + id + ".owner", p.getName());
        data.set("bottles." + id + ".time", System.currentTimeMillis());
        saveData();

        giveItem(p, bottle);
        p.playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        p.sendMessage(msg("Tu as mis ", NamedTextColor.GREEN)
                .append(Component.text(points + " XP", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" (niveau " + level + ") en bouteille !", NamedTextColor.GREEN)));
    }

    // ===================== CLIC DROIT : boire la bouteille =====================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(KEY_ID, PersistentDataType.STRING);
        if (id == null) return; // pas une de nos bouteilles -> comportement normal

        // C'est notre bouteille : on annule le lancer vanilla.
        e.setCancelled(true);

        Player p = e.getPlayer();
        int points = lookupPoints(id, pdc);
        if (points <= 0) {
            p.sendMessage(msg("Cette bouteille est vide ou invalide.", NamedTextColor.RED));
            return;
        }

        // On rend l'XP.
        p.giveExp(points);

        // On consomme une bouteille dans la bonne main.
        EquipmentSlot hand = e.getHand();
        int newAmount = item.getAmount() - 1;
        ItemStack result = null;
        if (newAmount > 0) {
            result = item.clone();
            result.setAmount(newAmount);
        }
        if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(result);
        else p.getInventory().setItemInMainHand(result);
        p.updateInventory();

        // On retire l'entree du disque (bouteille consommee).
        data.set("bottles." + id, null);
        saveData();

        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        p.sendMessage(msg("Tu as bu la bouteille et recupere ", NamedTextColor.GREEN)
                .append(Component.text(points + " XP", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" !", NamedTextColor.GREEN)));
    }

    private int lookupPoints(String id, PersistentDataContainer pdc) {
        // 1) la source de verite : le yml.
        if (data.contains("bottles." + id + ".points")) {
            return data.getInt("bottles." + id + ".points");
        }
        // 2) secours : la copie stockee dans l'item.
        Integer backup = pdc.get(KEY_POINTS, PersistentDataType.INTEGER);
        return backup == null ? 0 : backup;
    }

    // ===================== CREATION DE L'ITEM =====================

    private ItemStack createBottle(String id, int points, int level, String owner) {
        ItemStack is = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Bouteille d'XP", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(line("Niveau : ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(level), NamedTextColor.YELLOW)));
            lore.add(line("XP : ", NamedTextColor.GRAY)
                    .append(Component.text(points + " points", NamedTextColor.YELLOW)));
            lore.add(line("Proprietaire : ", NamedTextColor.GRAY)
                    .append(Component.text(owner, NamedTextColor.AQUA)));
            lore.add(Component.empty());
            lore.add(line("Clic droit pour recuperer l'XP.", NamedTextColor.DARK_GRAY));
            meta.lore(lore);

            // Petit effet brillant (Paper 1.20.5+).
            try { meta.setEnchantmentGlintOverride(true); } catch (Throwable ignored) {}

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_ID, PersistentDataType.STRING, id);
            pdc.set(KEY_POINTS, PersistentDataType.INTEGER, points);

            is.setItemMeta(meta);
        }
        return is;
    }

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack leftover : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
            p.sendMessage(msg("Inventaire plein : la bouteille est tombee a tes pieds.", NamedTextColor.YELLOW));
        }
    }

    // ===================== CALCUL DE L'XP =====================

    /** XP totale (en points) du joueur, calculee depuis son niveau + sa barre. */
    private int getTotalExperiencePoints(Player p) {
        int level = p.getLevel();
        int total = xpAtLevel(level);
        total += Math.round(p.getExp() * xpToNextLevel(level));
        return total;
    }

    /** Points d'XP necessaires pour atteindre 'level' depuis 0. */
    private int xpAtLevel(int level) {
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) Math.round(2.5 * level * level - 40.5 * level + 360);
        return (int) Math.round(4.5 * level * level - 162.5 * level + 2220);
    }

    /** Points d'XP pour passer de 'level' a 'level+1'. */
    private int xpToNextLevel(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    // ===================== PERSISTANCE =====================

    private void loadData() {
        try {
            File dir = getHost().getDataFolder();
            if (dir != null && !dir.exists()) dir.mkdirs();
            file = new File(dir, "bottlexp.yml");
            data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        } catch (Throwable t) {
            data = new YamlConfiguration();
            getLogger().warning("[BottleXP] chargement KO : " + t.getMessage());
        }
    }

    private void saveData() {
        if (file == null || data == null) return;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            data.save(file);
        } catch (IOException e) {
            getLogger().warning("[BottleXP] sauvegarde KO : " + e.getMessage());
        }
    }

    // ===================== MESSAGES =====================

    static final Component PREFIX = Component.text("XP ", NamedTextColor.GREEN, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}

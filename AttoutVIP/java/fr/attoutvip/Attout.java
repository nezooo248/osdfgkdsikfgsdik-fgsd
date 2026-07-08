package fr.attout;

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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Attout - a mettre dans : fr/attout/Attout.java
 *
 * /attout  (permission : vip.attout)
 *   -> ouvre un menu double coffre (54 slots) avec les "atouts" VIP.
 *   -> clic sur un atout = ACTIVE, re-clic = DESACTIVE (toggle).
 *
 * Les atouts sont des effets infinis (Force II, Vitesse II, Regen II, Resistance II,
 * Saut II, Celerite II) + Anti-chute, Resistance au feu, Respiration, Vision nocturne.
 *
 * L'etat de chaque joueur est sauvegarde sur le disque dans attout.yml, et les effets
 * sont re-appliques a la connexion, au respawn et via une tache periodique.
 */
public class Attout extends LoadedPlugin implements Listener {

    // Cle PDC posee sur les items du menu pour savoir sur quoi le joueur a clique.
    private static final NamespacedKey KEY_ATOUT = new NamespacedKey("attout", "atout");

    // Duree "infinie" : -1 sur les serveurs recents (Paper 1.19.4+), sinon Integer.MAX_VALUE.
    private static final int INFINITE = resolveInfinite();

    private static final Component TITLE =
            Component.text("Atouts VIP", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false);

    // Etat en memoire : joueur -> ensemble des atouts actifs.
    private final Map<UUID, Set<String>> active = new HashMap<>();
    // Cache de resolution des PotionEffectType (les noms changent selon les versions).
    private final Map<String, PotionEffectType> effectCache = new HashMap<>();

    private File file;
    private YamlConfiguration data;
    private int refreshTaskId = -1;

    // ===================== LISTE DES ATOUTS =====================

    /** Definition d'un atout. effectNames == null => atout "custom" (ex : anti-chute). */
    private static final class Atout {
        final String key;            // identifiant interne
        final String name;           // nom affiche
        final Material icon;         // icone dans le menu
        final NamedTextColor color;  // couleur du nom
        final String desc;           // description (lore)
        final String[] effectNames;  // noms candidats du PotionEffectType (null = custom)
        final int amplifier;         // 0 = niveau I, 1 = niveau II

        Atout(String key, String name, Material icon, NamedTextColor color,
              String desc, String[] effectNames, int amplifier) {
            this.key = key; this.name = name; this.icon = icon; this.color = color;
            this.desc = desc; this.effectNames = effectNames; this.amplifier = amplifier;
        }
    }

    // Tous les atouts. "tout en 2 infini" => amplifier 1 (= niveau II) pour ceux ou ca a du sens.
    private static final Atout[] ATOUTS = {
            new Atout("force",    "Force",             Material.BLAZE_POWDER,    NamedTextColor.RED,          "Inflige plus de degats",   new String[]{"STRENGTH", "INCREASE_DAMAGE"}, 1),
            new Atout("vitesse",  "Vitesse",           Material.SUGAR,           NamedTextColor.AQUA,         "Deplace-toi plus vite",    new String[]{"SPEED"},                       1),
            new Atout("regen",    "Regeneration",      Material.GHAST_TEAR,      NamedTextColor.LIGHT_PURPLE, "Regagne des coeurs",       new String[]{"REGENERATION"},                1),
            new Atout("resist",   "Resistance",        Material.IRON_CHESTPLATE, NamedTextColor.GRAY,         "Reduit les degats subis",  new String[]{"RESISTANCE", "DAMAGE_RESISTANCE"}, 1),
            new Atout("nofall",   "Anti-chute",        Material.FEATHER,         NamedTextColor.WHITE,        "Aucun degat de chute",     null,                                        0),
            new Atout("saut",     "Saut",              Material.RABBIT_FOOT,     NamedTextColor.GREEN,        "Saute plus haut",          new String[]{"JUMP_BOOST", "JUMP"},          1),
            new Atout("haste",    "Celerite",          Material.GOLDEN_PICKAXE,  NamedTextColor.YELLOW,       "Mine plus vite",           new String[]{"HASTE", "FAST_DIGGING"},       1),
            new Atout("fireres",  "Resistance au feu", Material.MAGMA_CREAM,     NamedTextColor.GOLD,         "Immunise contre le feu",   new String[]{"FIRE_RESISTANCE"},             0),
            new Atout("water",    "Respiration",       Material.PUFFERFISH,      NamedTextColor.BLUE,         "Respire sous l'eau",       new String[]{"WATER_BREATHING"},             0),
            new Atout("nightvis", "Vision nocturne",   Material.ENDER_EYE,       NamedTextColor.DARK_AQUA,    "Vois dans le noir",        new String[]{"NIGHT_VISION"},                0),
    };

    // Emplacements des atouts dans le menu (2 rangees centrees).
    private static final int[] ATOUT_SLOTS = {11, 12, 13, 14, 15, 29, 30, 31, 32, 33};

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // evite les listeners fantomes apres /plreload
        loadData();
        registerListener(this);

        registerCommand("attout", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!p.hasPermission("vip.attout")) {
                p.sendMessage(msg("Tu dois etre VIP pour utiliser les atouts.", NamedTextColor.RED));
                return true;
            }
            openMenu(p);
            return true;
        });

        // Tache periodique (toutes les 10s) : garde les effets bien appliques.
        if (getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            refreshTaskId = Bukkit.getScheduler()
                    .runTaskTimer(plugin, this::reapplyAll, 200L, 200L)
                    .getTaskId();
        }

        // Reapplique pour les joueurs deja connectes (cas d'un /plreload).
        reapplyAll();

        getLogger().info("Attout active.");
    }

    @Override
    public void onDisable() {
        if (refreshTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(refreshTaskId); } catch (Throwable ignored) {}
            refreshTaskId = -1;
        }
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        saveData();
        getLogger().info("Attout desactive.");
    }

    /** Supprime tout listener Attout fantome d'un ancien chargement (compare par nom de classe). */
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

    // ===================== MENU (double coffre) =====================

    /** Holder qui identifie NOTRE menu (evite de comparer des titres). */
    private static final class Menu implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void openMenu(Player p) {
        Menu holder = new Menu();
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE);
        holder.inv = inv;

        // Decoration : bordure noire + fond gris.
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack gray  = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            inv.setItem(i, (row == 0 || row == 5 || col == 0 || col == 8) ? black : gray);
        }

        // Les atouts.
        for (int i = 0; i < ATOUTS.length; i++) {
            inv.setItem(ATOUT_SLOTS[i], buildAtoutItem(p, ATOUTS[i]));
        }

        // Les boutons de controle (rangee du bas).
        inv.setItem(38, control("tout_on",  Material.LIME_DYE,  "Tout activer",     NamedTextColor.GREEN));
        inv.setItem(40, control("close",    Material.BARRIER,   "Fermer",           NamedTextColor.RED));
        inv.setItem(42, control("tout_off", Material.RED_DYE,   "Tout desactiver",  NamedTextColor.RED));

        p.openInventory(inv);
    }

    private ItemStack buildAtoutItem(Player p, Atout a) {
        boolean on = isActive(p.getUniqueId(), a.key);
        ItemStack is = new ItemStack(a.icon);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(a.name, a.color, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(line(a.desc, NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(line("Statut : ", NamedTextColor.GRAY)
                    .append(Component.text(on ? "ACTIVE" : "DESACTIVE",
                            on ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
            lore.add(line(on ? "Clic pour desactiver" : "Clic pour activer", NamedTextColor.DARK_GRAY));
            meta.lore(lore);

            if (on) { try { meta.setEnchantmentGlintOverride(true); } catch (Throwable ignored) {} }

            meta.getPersistentDataContainer().set(KEY_ATOUT, PersistentDataType.STRING, a.key);
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack control(String action, Material m, String name, NamedTextColor color) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(KEY_ATOUT, PersistentDataType.STRING, action);
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack pane(Material m) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            is.setItemMeta(meta);
        }
        return is;
    }

    // ===================== CLICS DANS LE MENU =====================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true); // on verrouille tout le menu
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String key = it.getItemMeta().getPersistentDataContainer().get(KEY_ATOUT, PersistentDataType.STRING);
        if (key == null) return; // verre ou slot vide

        switch (key) {
            case "close" -> p.closeInventory();

            case "tout_on" -> {
                for (Atout a : ATOUTS) setAtout(p, a, true);
                saveData();
                refreshAtouts(p, e.getInventory());
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                p.sendMessage(msg("Tous les atouts ont ete actives.", NamedTextColor.GREEN));
            }

            case "tout_off" -> {
                for (Atout a : ATOUTS) setAtout(p, a, false);
                saveData();
                refreshAtouts(p, e.getInventory());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
                p.sendMessage(msg("Tous les atouts ont ete desactives.", NamedTextColor.RED));
            }

            default -> {
                Atout a = findAtout(key);
                if (a == null) return;
                boolean on = toggle(p, a);
                refreshAtouts(p, e.getInventory());
                p.playSound(p.getLocation(),
                        on ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS,
                        1f, on ? 1.4f : 0.8f);
                p.sendMessage(msg(on ? "Atout active : " : "Atout desactive : ",
                        on ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .append(Component.text(a.name, NamedTextColor.YELLOW, TextDecoration.BOLD)));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void refreshAtouts(Player p, Inventory inv) {
        for (int i = 0; i < ATOUTS.length; i++) {
            inv.setItem(ATOUT_SLOTS[i], buildAtoutItem(p, ATOUTS[i]));
        }
    }

    private Atout findAtout(String key) {
        for (Atout a : ATOUTS) if (a.key.equals(key)) return a;
        return null;
    }

    // ===================== ETAT + EFFETS =====================

    private Set<String> getActive(UUID id) {
        return active.computeIfAbsent(id, k -> new HashSet<>());
    }

    private boolean isActive(UUID id, String key) {
        Set<String> s = active.get(id);
        return s != null && s.contains(key);
    }

    /** Active ou desactive un atout (sans sauvegarder ; le save est fait par l'appelant). */
    private void setAtout(Player p, Atout a, boolean on) {
        Set<String> s = getActive(p.getUniqueId());
        if (on) { if (s.add(a.key))    applyEffect(p, a); }
        else    { if (s.remove(a.key)) removeEffect(p, a); }
    }

    /** Inverse l'etat d'un atout et sauvegarde. Renvoie true si desormais actif. */
    private boolean toggle(Player p, Atout a) {
        boolean newState = !isActive(p.getUniqueId(), a.key);
        setAtout(p, a, newState);
        saveData();
        return newState;
    }

    private void applyEffect(Player p, Atout a) {
        if (a.effectNames == null) return; // atout custom (anti-chute) -> gere par onDamage
        PotionEffectType type = resolveEffect(a.effectNames);
        if (type == null) return;
        // ambient=false, particules=false, icone=true
        p.addPotionEffect(new PotionEffect(type, INFINITE, a.amplifier, false, false, true));
    }

    private void removeEffect(Player p, Atout a) {
        if (a.effectNames == null) return;
        PotionEffectType type = resolveEffect(a.effectNames);
        if (type != null) p.removePotionEffect(type);
    }

    private void reapply(Player p) {
        Set<String> s = active.get(p.getUniqueId());
        if (s == null || s.isEmpty()) return;
        for (Atout a : ATOUTS) if (s.contains(a.key)) applyEffect(p, a);
    }

    private void reapplyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) reapply(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        reapply(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // Le respawn efface les effets : on les remet 1 tick plus tard.
        if (getHost() instanceof org.bukkit.plugin.Plugin plugin) {
            Bukkit.getScheduler().runTask(plugin, () -> reapply(p));
        } else {
            reapply(p);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (e.getEntity() instanceof Player p && isActive(p.getUniqueId(), "nofall")) {
            e.setCancelled(true);
        }
    }

    /** Resout un PotionEffectType robustement (les noms varient selon la version). */
    private PotionEffectType resolveEffect(String[] names) {
        String cacheKey = names[0];
        if (effectCache.containsKey(cacheKey)) return effectCache.get(cacheKey);

        PotionEffectType found = null;
        for (String n : names) {
            try {
                PotionEffectType t = PotionEffectType.getByName(n);
                if (t != null) { found = t; break; }
            } catch (Throwable ignored) {}
        }
        if (found == null) {
            for (String n : names) {
                try {
                    PotionEffectType t = org.bukkit.Registry.EFFECT.get(NamespacedKey.minecraft(n.toLowerCase()));
                    if (t != null) { found = t; break; }
                } catch (Throwable ignored) {}
            }
        }
        if (found == null) getLogger().warning("[Attout] effet introuvable : " + cacheKey);
        effectCache.put(cacheKey, found);
        return found;
    }

    private static int resolveInfinite() {
        try {
            java.lang.reflect.Field f = PotionEffect.class.getField("INFINITE_DURATION");
            return f.getInt(null); // -1 sur les versions recentes
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    // ===================== PERSISTANCE =====================

    private void loadData() {
        try {
            File dir = getHost().getDataFolder();
            if (dir != null && !dir.exists()) dir.mkdirs();
            file = new File(dir, "attout.yml");
            data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();

            active.clear();
            if (data.isConfigurationSection("players")) {
                for (String uid : data.getConfigurationSection("players").getKeys(false)) {
                    List<String> keys = data.getStringList("players." + uid);
                    try { active.put(UUID.fromString(uid), new HashSet<>(keys)); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            data = new YamlConfiguration();
            getLogger().warning("[Attout] chargement KO : " + t.getMessage());
        }
    }

    private void saveData() {
        if (file == null || data == null) return;
        try {
            data.set("players", null); // on reecrit tout proprement
            for (Map.Entry<UUID, Set<String>> en : active.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                data.set("players." + en.getKey(), new ArrayList<>(en.getValue()));
            }
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            data.save(file);
        } catch (IOException e) {
            getLogger().warning("[Attout] sauvegarde KO : " + e.getMessage());
        }
    }

    // ===================== MESSAGES =====================

    static final Component PREFIX = Component.text("Atouts ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("» ", NamedTextColor.DARK_GRAY));

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}

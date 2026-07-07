package fr.combattag;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combat Tag.
 *   - Un coup PvP marque l'attaquant ET la victime en combat pendant 15 secondes.
 *   - Chaque coup relance le compte a rebours a 15 s.
 *   - Pendant le combat, seules /shop et /tag sont autorisees.
 *   - /tag  -> affiche le temps de combat restant.
 *   - Se deconnecter en combat = mort (anti combat-log).
 */
public class CombatTag extends LoadedPlugin implements Listener {

    private static final long TAG_MS = 15_000L; // 15 secondes
    private static final List<String> ALLOWED = List.of("shop", "tag"); // commandes autorisees en combat

    private final Map<UUID, Long> tagged = new HashMap<>(); // uuid -> instant de fin (ms)

    @Override
    public void onEnable() {
        registerListener(this);

        registerCommand("tag", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            long remain = remainingSeconds(player.getUniqueId());
            if (remain > 0) {
                player.sendMessage(msg("Tu es en combat encore " + remain + " seconde(s).", NamedTextColor.RED));
            } else {
                player.sendMessage(msg("Tu n'es pas en combat.", NamedTextColor.GREEN));
            }
            return true;
        });

        // Rafraichissement chaque seconde : barre d'action + fin de combat.
        Bukkit.getScheduler().runTaskTimer(getHost(), this::tick, 20L, 20L);

        getLogger().info("CombatTag active.");
    }

    // ---------- Marquage combat ----------

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        tag(attacker);
        tag(victim);
    }

    private void tag(Player player) {
        boolean wasTagged = remainingSeconds(player.getUniqueId()) > 0;
        tagged.put(player.getUniqueId(), System.currentTimeMillis() + TAG_MS);
        if (!wasTagged) {
            player.sendMessage(msg("Tu es maintenant en combat ! (" + (TAG_MS / 1000) + "s)", NamedTextColor.RED));
        }
    }

    private long remainingSeconds(UUID uuid) {
        Long end = tagged.get(uuid);
        if (end == null) return 0;
        long ms = end - System.currentTimeMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000; // arrondi au superieur
    }

    // ---------- Blocage des commandes ----------

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (remainingSeconds(player.getUniqueId()) <= 0) return;

        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        String base = message.split(" ", 2)[0].toLowerCase();
        // gere les prefixes type "minecraft:tag" ou "plugin:shop"
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);

        if (!ALLOWED.contains(base)) {
            event.setCancelled(true);
            player.sendMessage(msg("Interdit en combat ! Seules /shop et /tag sont autorisees.", NamedTextColor.RED));
        }
    }

    // ---------- Anti combat-log ----------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (remainingSeconds(player.getUniqueId()) > 0) {
            player.setHealth(0.0); // mort : les objets sont drop, punition du combat-log
        }
        tagged.remove(player.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        tagged.remove(event.getEntity().getUniqueId());
    }

    // ---------- Boucle d'affichage ----------

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(tagged.keySet())) {
            Long end = tagged.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (end == null || now >= end) {
                tagged.remove(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(msg("Tu n'es plus en combat.", NamedTextColor.GREEN));
                    player.sendActionBar(Component.empty());
                }
                continue;
            }
            if (player != null && player.isOnline()) {
                long s = (end - now + 999) / 1000;
                player.sendActionBar(msg("En combat : " + s + "s", NamedTextColor.RED));
            }
        }
    }

    private Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    @Override
    public void onDisable() {
        getLogger().info("CombatTag desactive.");
    }
}

package fr.rtp;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Teleportation aleatoire (RTP).
 *   /rtp  -> teleporte le joueur a une position aleatoire.
 *
 * Portee (distance depuis le centre 0,0) :
 *   - basique       : entre 10 000 et 25 000 blocks   (permission rtp.use)
 *   - rtp.vip       : entre 30 000 et 50 000 blocks
 *
 * Cooldown : 5 minutes (contournable avec rtp.cooldown.bypass).
 */
public class RandomTeleport extends LoadedPlugin {

    private static final long COOLDOWN_MS = 5L * 60L * 1000L; // 5 minutes
    private static final int BASIC_MIN = 10_000, BASIC_MAX = 25_000;
    private static final int VIP_MIN   = 30_000, VIP_MAX   = 50_000;
    private static final int MAX_ATTEMPTS = 40;

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        registerCommand("rtp", (sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("Commande reservee aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("rtp.use")) {
                player.sendMessage(msg("Vous n'avez pas la permission d'utiliser le RTP.", NamedTextColor.RED));
                return true;
            }

            // Cooldown
            long now = System.currentTimeMillis();
            if (!player.hasPermission("rtp.cooldown.bypass")) {
                Long last = cooldowns.get(player.getUniqueId());
                if (last != null && now - last < COOLDOWN_MS) {
                    long remain = (COOLDOWN_MS - (now - last)) / 1000L;
                    player.sendMessage(msg("Patiente encore " + formatTime(remain) + " avant un nouveau /rtp.", NamedTextColor.RED));
                    return true;
                }
            }

            int min, max;
            if (player.hasPermission("rtp.vip")) { min = VIP_MIN;   max = VIP_MAX;   }
            else                                 { min = BASIC_MIN; max = BASIC_MAX; }

            player.sendMessage(msg("Recherche d'un endroit sur...", NamedTextColor.GRAY));
            tryTeleport(player, min, max, MAX_ATTEMPTS);
            return true;
        });
        getLogger().info("RandomTeleport active.");
    }

    /** Tente une teleportation ; recommence (async) tant qu'aucun endroit sur n'est trouve. */
    private void tryTeleport(Player player, int min, int max, int attemptsLeft) {
        if (!player.isOnline()) return;
        if (attemptsLeft <= 0) {
            player.sendMessage(msg("Impossible de trouver un endroit sur, reessaie.", NamedTextColor.RED));
            return;
        }

        World world = player.getWorld();
        double angle = Math.random() * Math.PI * 2;
        int dist = min + (int) (Math.random() * (max - min + 1));
        int x = (int) Math.round(Math.cos(angle) * dist);
        int z = (int) Math.round(Math.sin(angle) * dist);

        // Chargement asynchrone du chunk (genere le terrain sans freeze).
        world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk ->
            Bukkit.getScheduler().runTask(getHost(), () -> {
                if (!player.isOnline()) return;
                Integer y = findSafeY(world, x, z);
                if (y != null) {
                    Location loc = new Location(world, x + 0.5, y, z + 0.5);
                    player.teleport(loc);
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                    player.sendMessage(msg("Teleporte a " + x + ", " + y + ", " + z
                            + "  (" + dist + " blocks du centre).", NamedTextColor.GREEN));
                } else {
                    tryTeleport(player, min, max, attemptsLeft - 1);
                }
            })
        );
    }

    /** Trouve une hauteur sure a (x,z), ou null si rien de sur. */
    private Integer findSafeY(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            // Le nether a un plafond de bedrock : on scanne sous le plafond.
            for (int y = 120; y > 10; y--) {
                if (isSafe(world, x, y, z)) return y;
            }
            return null;
        }
        int top = world.getHighestBlockYAt(x, z);
        return isSafe(world, x, top + 1, z) ? top + 1 : null;
    }

    /** Vrai si (x,y,z) est un endroit ou le joueur peut se tenir sans danger. */
    private boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);

        if (!ground.getType().isSolid()) return false;
        Material g = ground.getType();
        if (g == Material.LAVA || g == Material.WATER || g == Material.MAGMA_BLOCK
                || g == Material.CACTUS || g == Material.FIRE || g == Material.CAMPFIRE) return false;
        if (!feet.isPassable() || !head.isPassable()) return false;
        Material f = feet.getType();
        return f != Material.LAVA && f != Material.WATER && f != Material.FIRE;
    }

    private String formatTime(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m > 0) return m + " min " + s + " s";
        return s + " s";
    }

    private Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    @Override
    public void onDisable() {
        getLogger().info("RandomTeleport desactive.");
    }
}

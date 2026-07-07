package fr.clanplugin;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Enregistre le placeholder %clan_name% SANS importer PlaceholderAPI.
 * Tout passe par reflection -> compile meme si PlaceholderAPI est absent.
 */
public class ClanExpansion {

    private final ClanPlugin plugin;

    public ClanExpansion(ClanPlugin plugin) {
        this.plugin = plugin;
    }

    /** Retourne la valeur du placeholder pour un joueur. params = "name", "size", "hasclan". */
    String value(UUID uuid, String params) {
        Clan clan = plugin.getClanManager().getClanOf(uuid);
        switch (params.toLowerCase()) {
            case "name":    return clan == null ? "" : clan.getName();
            case "size":    return clan == null ? "0" : String.valueOf(clan.size());
            case "hasclan": return clan == null ? "false" : "true";
            default:        return null;
        }
    }

    /** Cree une sous-classe de PlaceholderExpansion a la volee et l'enregistre. */
    public void register() throws Exception {
        ClassLoader papiLoader = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin").getClassLoader();

        Class<?> expansionClass = Class.forName(
                "me.clip.placeholderapi.expansion.PlaceholderExpansion", true, papiLoader);

        // On genere dynamiquement une instance d'expansion via ByteBuddy ? Non : on utilise
        // l'API "relational"/"simple" en creant une classe anonyme cote PAPI n'est pas possible
        // par Proxy (classe abstraite). On passe donc par la classe utilitaire ci-dessous.
        Object expansion = ExpansionFactory.build(this, expansionClass, papiLoader);

        expansionClass.getMethod("register").invoke(expansion);
        plugin.getLogger().info("Placeholder %clan_name% active.");
    }
}

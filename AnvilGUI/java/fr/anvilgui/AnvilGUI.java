package fr.anvigui;

import fr.loader.api.LoadedPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;

/**
 * AnvilGUI - a mettre dans : fr/anvigui/AnvilGUI.java
 *
 * Retire la limite vanilla de l'enclume :
 *   - plus jamais de "Too Expensive!" (le plafond des 40 niveaux est supprime) ;
 *   - le cout reel paye par le joueur est plafonne a COUT_MAX pour que les
 *     objets full-enchant restent accessibles.
 *
 * Aucune commande, aucune sauvegarde : juste un listener sur l'enclume.
 * Meme socle que BottleXP (fr.loader.api.LoadedPlugin).
 */
public class AnvilGUI extends LoadedPlugin implements Listener {

    // Cout maximum (en niveaux) que le joueur devra reellement payer.
    // Mets Integer.MAX_VALUE si tu veux garder le cout vanilla exact (sans plafond).
    private static final int COUT_MAX = 30;

    // ===================== CYCLE DE VIE =====================

    @Override
    public void onEnable() {
        unregisterStaleListeners(); // evite les listeners fantomes apres /plreload
        registerListener(this);
        getLogger().info("AnvilGUI active - limite d'enclume retiree.");
    }

    @Override
    public void onDisable() {
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        getLogger().info("AnvilGUI desactive.");
    }

    /** Supprime tout listener AnvilGUI fantome d'un ancien chargement (compare par nom de classe). */
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

    // ===================== ENCLUME =====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();

        // 1) On retire le plafond des 40 niveaux -> plus jamais de "Too Expensive!".
        inv.setMaximumRepairCost(Integer.MAX_VALUE);

        // 2) On plafonne le cout reellement paye pour que ca reste jouable.
        if (COUT_MAX != Integer.MAX_VALUE) {
            int cout = inv.getRepairCost();
            if (cout > COUT_MAX) {
                inv.setRepairCost(COUT_MAX);
            }
        }
    }
}

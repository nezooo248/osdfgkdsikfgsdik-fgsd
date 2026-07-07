package fr.clan.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Holder du sous-menu de gestion d'un membre (kick / promouvoir / retrograder).
 */
public class MemberMenuHolder implements InventoryHolder {

    private final String clanName;
    private final UUID target;
    private Inventory inventory;

    public MemberMenuHolder(String clanName, UUID target) {
        this.clanName = clanName;
        this.target = target;
    }

    public String getClanName() {
        return clanName;
    }

    public UUID getTarget() {
        return target;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

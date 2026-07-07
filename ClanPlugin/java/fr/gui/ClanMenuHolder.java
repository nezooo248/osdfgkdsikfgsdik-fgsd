package fr.clan.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holder du menu principal du clan. Sert a identifier le GUI et a savoir
 * quel membre correspond a quel slot cliquable.
 */
public class ClanMenuHolder implements InventoryHolder {

    private final String clanName;
    private final Map<Integer, UUID> slotToMember = new HashMap<>();
    private Inventory inventory;

    public ClanMenuHolder(String clanName) {
        this.clanName = clanName;
    }

    public String getClanName() {
        return clanName;
    }

    public Map<Integer, UUID> getSlotToMember() {
        return slotToMember;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

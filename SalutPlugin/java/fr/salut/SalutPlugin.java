package fr.salut;

import fr.loader.api.LoadedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class SalutPlugin extends LoadedPlugin {

    @Override
    public void onEnable() {
        getLogger().info("SalutPlugin active !");

        registerCommand("salut", (sender, cmd, label, args) -> {
            String nom = sender.getName();
            sender.sendMessage(
                    Component.text("Salut " + nom + " !").color(NamedTextColor.GREEN));
            return true;
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("SalutPlugin desactive.");
    }
}

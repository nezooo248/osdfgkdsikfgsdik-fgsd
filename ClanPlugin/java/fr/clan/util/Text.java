package fr.clan.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private Text() {}

    public static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static List<String> c(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(c(l));
        return out;
    }
}

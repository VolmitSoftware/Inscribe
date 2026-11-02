package com.volmit.inscribe.presentation;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class InscribePresentation {
    private static final String AMBER = "#E8B65B";
    private static final String TEAL = "#4AA89F";
    private static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme(
            AMBER, TEAL, "#263D39", TEAL, "#ECE6D8", "#FF7777", AMBER, "#A8B9B4");
    private static final List<String> BOOK = List.of(
            "   ______ ______   ",
            "  /      V      \\  ",
            " /  ===  |  ===  \\ ",
            " |  ===  |  ===  | ",
            " |_______|_______| ",
            "  \\_____ V _____/  ");

    private InscribePresentation() {
    }

    public static DirectorMiniMenu.Theme theme() {
        return THEME;
    }

    public static void splash(JavaPlugin plugin, long startedNanos) {
        List<String> details = details(plugin.getDescription().getVersion(),
                SplashScreenSupport.serverVersionWithoutMcSuffix(plugin.getServer().getVersion()),
                SplashScreenSupport.javaMajorVersion(), SplashScreenSupport.startupDate(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        ComponentText output = ComponentText.literal("\n");
        for (int row = 0; row < BOOK.size(); row++) {
            String art = BOOK.get(row);
            int middle = art.length() / 2;
            output = output.append(ComponentText.literal(art.substring(0, middle)).colorIfAbsent(AMBER))
                    .append(ComponentText.literal(art.substring(middle)).colorIfAbsent(TEAL))
                    .append(ComponentText.literal("   " + details.get(row)).colorIfAbsent(row == 0 ? AMBER : "#ECE6D8"))
                    .append(ComponentText.literal("\n"));
        }
        ComponentLog.log(plugin, plugin.getLogger(), "", Level.INFO, output, null);
    }

    static List<String> details(String version, String server, int javaVersion, String date, long elapsedMillis) {
        return List.of("Inscribe | Enchantment extraction and application",
                "Version: " + version + " | By VolmitSoftware",
                "Server: " + server,
                "Minecraft: 26.1-26.3 | Java: " + javaVersion + " (minimum 25)",
                "Started: " + date + " | Ready in " + elapsedMillis + " ms",
                "https://volmitsoftware.com | /inscribe");
    }
}

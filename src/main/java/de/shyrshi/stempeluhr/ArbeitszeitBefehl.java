package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /arbeitszeit rangliste                         – Top 10 nach Gesamtarbeitszeit (alle Spieler)
 * /arbeitszeit set <spieler> <dd> <hh> <mm> <ss> – Gesamtarbeitszeit setzen
 * /arbeitszeit get <spieler>                     – Gesamtarbeitszeit anzeigen
 *
 * set/get benötigen die Permission stempeluhr.admin (Standard: nur OPs, per LuckPerms vergebbar).
 */
final class ArbeitszeitBefehl implements TabExecutor {

    static final String PERMISSION = "stempeluhr.admin";
    static final int RANGLISTE_GROESSE = 10;

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Stempeluhr</gold>]</dark_gray> ";

    /** Tage max. 4-stellig, Stunden/Minuten/Sekunden max. 2-stellig. */
    private static final Pattern TAGE = Pattern.compile("^\\d{1,4}$");
    private static final Pattern TEIL = Pattern.compile("^\\d{1,2}$");

    /** Ein Platz in der Rangliste. */
    record Platz(UUID uuid, long zeit) {
    }

    private final Stempeluhr plugin;

    ArbeitszeitBefehl(Stempeluhr plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            hilfe(sender, label);
            return true;
        }

        // Bewusst exakter Vergleich: toLowerCase/equalsIgnoreCase würde z. B. "ſet" als "set" akzeptieren
        String unterbefehl = args[0];
        if (unterbefehl.equals("rangliste")) {
            rangliste(sender);
            return true;
        }
        if (!unterbefehl.equals("set") && !unterbefehl.equals("get")) {
            hilfe(sender, label);
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            senden(sender, "<red>Dafür fehlt dir die Berechtigung.");
            return true;
        }

        switch (unterbefehl) {
            case "set" -> {
                if (args.length != 6) {
                    hilfe(sender, label);
                    return true;
                }
                OfflinePlayer ziel = finde(args[1]);
                if (ziel == null) {
                    senden(sender, "<red>Spieler <white><name></white> ist unbekannt.",
                            Placeholder.unparsed("name", args[1]));
                    return true;
                }
                long ms = parse(args[2], args[3], args[4], args[5]);
                if (ms < 0) {
                    senden(sender, "<red>Ungültiges Format. Erwartet: <white>dd hh mm ss</white> "
                            + "<gray>(z. B. 01 08 30 00 = 1 Tag 8 Std 30 Min)");
                    return true;
                }

                StempelDaten.Eintrag e = plugin.daten().get(ziel.getUniqueId());
                long alt = e.gesamt;
                e.gesamt = ms;
                plugin.daten().speichern();

                String zielName = ziel.getName();
                String name = zielName != null ? zielName : args[1];
                senden(sender, "<green>Gesamtarbeitszeit von <white><name></white> auf <gold><zeit></gold> gesetzt.",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("zeit", Stempeluhr.formatiere(ms)));
                if (e.istEingestempelt()) {
                    senden(sender, "<gray>Hinweis: <white><name></white> ist eingestempelt, "
                            + "die laufende Schicht wird beim Ausstempeln noch dazugerechnet.",
                            Placeholder.unparsed("name", name));
                }

                // Nachvollziehbarkeit: jede manuelle Änderung landet im Server-Log.
                // Zeilenumbrüche entfernen, damit keine gefälschten Log-Zeilen entstehen (CRLF-Injection).
                plugin.getLogger().info(sender.getName().replaceAll("[\r\n]", "")
                        + " hat die Gesamtarbeitszeit von " + name.replaceAll("[\r\n]", "")
                        + " (" + ziel.getUniqueId() + ") von " + alt + " ms auf " + ms + " ms gesetzt.");

                Player online = ziel.getPlayer();
                if (online != null && online != sender) {
                    senden(online, "<gray>Deine Gesamtarbeitszeit wurde von einem Admin auf <gold><zeit></gold> gesetzt.",
                            Placeholder.unparsed("zeit", Stempeluhr.formatiere(ms)));
                }
            }
            case "get" -> {
                if (args.length != 2) {
                    hilfe(sender, label);
                    return true;
                }
                OfflinePlayer ziel = finde(args[1]);
                if (ziel == null) {
                    senden(sender, "<red>Spieler <white><name></white> ist unbekannt.",
                            Placeholder.unparsed("name", args[1]));
                    return true;
                }
                StempelDaten.Eintrag e = plugin.daten().get(ziel.getUniqueId());
                senden(sender, "<gray>Gesamtarbeitszeit von <white><name></white>: <gold><zeit></gold>",
                        Placeholder.unparsed("name", ziel.getName() != null ? ziel.getName() : args[1]),
                        Placeholder.unparsed("zeit", Stempeluhr.formatiere(e.gesamt)));
            }
            default -> hilfe(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        boolean admin = sender.hasPermission(PERMISSION);
        if (args.length == 1) {
            return filter(admin ? List.of("rangliste", "set", "get") : List.of("rangliste"), args[0]);
        }
        if (!admin || args[0].equals("rangliste")) return List.of();
        if (args.length == 2) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length >= 3 && args.length <= 6 && args[0].equals("set")) {
            return List.of(new String[]{"dd", "hh", "mm", "ss"}[args.length - 3]);
        }
        return List.of();
    }

    // ------------------------------------------------------------ Rangliste

    private void rangliste(CommandSender sender) {
        plugin.pruefeInaktivitaet();
        List<Platz> plaetze = berechneRangliste(plugin.daten().alle(), plugin.jetzt());
        if (plaetze.isEmpty()) {
            senden(sender, "<gray>Noch keine Arbeitszeit erfasst.");
            return;
        }

        senden(sender, "<gold>Rangliste – Gesamtarbeitszeit");
        for (int i = 0; i < Math.min(RANGLISTE_GROESSE, plaetze.size()); i++) {
            zeile(sender, i + 1, plaetze.get(i));
        }

        // Eigene Platzierung anzeigen, falls nicht schon unter den Top 10
        if (sender instanceof Player p) {
            for (int i = RANGLISTE_GROESSE; i < plaetze.size(); i++) {
                if (plaetze.get(i).uuid().equals(p.getUniqueId())) {
                    senden(sender, "<dark_gray>...");
                    zeile(sender, i + 1, plaetze.get(i));
                    break;
                }
            }
        }
    }

    /**
     * Sortiert alle Spieler absteigend nach Gesamtzeit inklusive laufender Schicht.
     * Spieler ohne erfasste Zeit werden weggelassen.
     */
    static List<Platz> berechneRangliste(Map<UUID, StempelDaten.Eintrag> eintraege, long jetzt) {
        List<Platz> plaetze = new ArrayList<>();
        for (Map.Entry<UUID, StempelDaten.Eintrag> entry : eintraege.entrySet()) {
            StempelDaten.Eintrag e = entry.getValue();
            long zeit = e.gesamt;
            if (e.istEingestempelt()) zeit += Math.max(0, jetzt - e.schichtStart);
            if (zeit > 0) plaetze.add(new Platz(entry.getKey(), zeit));
        }
        plaetze.sort(Comparator.comparingLong(Platz::zeit).reversed().thenComparing(Platz::uuid));
        return plaetze;
    }

    private static void zeile(CommandSender sender, int rang, Platz platz) {
        String name = Bukkit.getOfflinePlayer(platz.uuid()).getName();
        // Eigener Name wird grün hervorgehoben
        String farbe = sender instanceof Player p && p.getUniqueId().equals(platz.uuid()) ? "green" : "white";
        senden(sender, "<yellow>#<rang></yellow> <" + farbe + "><name></" + farbe + "> "
                        + "<dark_gray>–</dark_gray> <gold><zeit></gold>",
                Placeholder.unparsed("rang", String.valueOf(rang)),
                Placeholder.unparsed("name", name != null ? name : "Unbekannt"),
                Placeholder.unparsed("zeit", Stempeluhr.formatiere(platz.zeit())));
    }

    // ------------------------------------------------------------ Hilfen

    /**
     * Wandelt Tage, Stunden, Minuten und Sekunden in Millisekunden um.
     * @return Millisekunden oder -1 bei ungültiger Eingabe
     */
    static long parse(String dd, String hh, String mm, String ss) {
        if (!TAGE.matcher(dd).matches() || !TEIL.matcher(hh).matches()
                || !TEIL.matcher(mm).matches() || !TEIL.matcher(ss).matches()) {
            return -1;
        }
        long tage = Long.parseLong(dd);
        long std = Long.parseLong(hh);
        long min = Long.parseLong(mm);
        long sek = Long.parseLong(ss);
        if (std > 23 || min > 59 || sek > 59) return -1;
        return ((tage * 24 + std) * 60 + min) * 60_000 + sek * 1000;
    }

    /** Online-Spieler oder bereits bekannter Offline-Spieler – ohne Mojang-Abfrage. */
    private static OfflinePlayer finde(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private static List<String> filter(List<String> optionen, String anfang) {
        String a = anfang.toLowerCase(Locale.ROOT);
        return optionen.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(a)).toList();
    }

    private static void hilfe(CommandSender sender, String label) {
        senden(sender, "<gray>Verwendung:");
        senden(sender, "<white>/" + label + " rangliste");
        if (sender.hasPermission(PERMISSION)) {
            senden(sender, "<white>/" + label + " set <spieler> <dd> <hh> <mm> <ss>");
            senden(sender, "<white>/" + label + " get <spieler>");
        }
    }

    private static void senden(CommandSender empfaenger, String nachricht, TagResolver... resolver) {
        empfaenger.sendMessage(MM.deserialize(PREFIX + nachricht, resolver));
    }
}

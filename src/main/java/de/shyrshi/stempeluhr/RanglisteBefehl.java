package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
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

/** /rangliste arbeitszeit tag|woche für die aktuelle Kalenderperiode. */
final class RanglisteBefehl implements TabExecutor {

    static final int RANGLISTE_GROESSE = 10;

    enum Zeitraum { TAG, WOCHE }

    record Platz(UUID uuid, long zeit) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Stempeluhr</gold>]</dark_gray> ";

    private final Stempeluhr plugin;

    RanglisteBefehl(Stempeluhr plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("stempeluhr.use")) {
            senden(sender, "<red>Dafür fehlt dir die Berechtigung.");
            return true;
        }
        if (args.length != 2 || !args[0].equals("arbeitszeit")) {
            hilfe(sender, label);
            return true;
        }

        Zeitraum zeitraum = switch (args[1]) {
            case "tag" -> Zeitraum.TAG;
            case "woche" -> Zeitraum.WOCHE;
            default -> null;
        };
        if (zeitraum == null) {
            hilfe(sender, label);
            return true;
        }

        // Schichten, deren AFK-Frist gerade abgelaufen ist, vor der Auswertung schließen.
        plugin.pruefeInaktivitaet();
        List<Platz> plaetze = berechneRangliste(plugin.daten().alle(), plugin.jetzt(), zeitraum);
        if (plaetze.isEmpty()) {
            senden(sender, zeitraum == Zeitraum.TAG
                    ? "<gray>Heute noch keine Arbeitszeit erfasst."
                    : "<gray>Diese Woche noch keine Arbeitszeit erfasst.");
            return true;
        }

        senden(sender, zeitraum == Zeitraum.TAG
                ? "<gold>Rangliste – Arbeitszeit heute (ab 00:00 Uhr)"
                : "<gold>Rangliste – Arbeitszeit diese Woche (ab Montag 00:00 Uhr)");
        for (int i = 0; i < Math.min(RANGLISTE_GROESSE, plaetze.size()); i++) {
            zeile(sender, i + 1, plaetze.get(i));
        }

        // Die eigene Platzierung auch dann anzeigen, wenn sie außerhalb der Top 10 liegt.
        if (sender instanceof Player p) {
            for (int i = RANGLISTE_GROESSE; i < plaetze.size(); i++) {
                if (plaetze.get(i).uuid().equals(p.getUniqueId())) {
                    senden(sender, "<dark_gray>...");
                    zeile(sender, i + 1, plaetze.get(i));
                    break;
                }
            }
        }
        return true;
    }

    static List<Platz> berechneRangliste(Map<UUID, StempelDaten.Eintrag> eintraege,
                                         long jetzt, Zeitraum zeitraum) {
        List<Platz> plaetze = new ArrayList<>();
        for (Map.Entry<UUID, StempelDaten.Eintrag> entry : eintraege.entrySet()) {
            long zeit = zeitraum == Zeitraum.TAG
                    ? ZeitAuswertung.heute(entry.getValue(), jetzt)
                    : ZeitAuswertung.woche(entry.getValue(), jetzt);
            if (zeit > 0) plaetze.add(new Platz(entry.getKey(), zeit));
        }
        plaetze.sort(Comparator.comparingLong(Platz::zeit).reversed().thenComparing(Platz::uuid));
        return plaetze;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("stempeluhr.use")) return List.of();
        if (args.length == 1) return filter(List.of("arbeitszeit"), args[0]);
        if (args.length == 2 && args[0].equals("arbeitszeit")) {
            return filter(List.of("tag", "woche"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> optionen, String anfang) {
        String a = anfang.toLowerCase(Locale.ROOT);
        return optionen.stream().filter(o -> o.startsWith(a)).toList();
    }

    private static void zeile(CommandSender sender, int rang, Platz platz) {
        String name = Bukkit.getOfflinePlayer(platz.uuid()).getName();
        String farbe = sender instanceof Player p && p.getUniqueId().equals(platz.uuid()) ? "green" : "white";
        senden(sender, "<yellow>#<rang></yellow> <" + farbe + "><name></" + farbe + "> "
                        + "<dark_gray>–</dark_gray> <gold><zeit></gold>",
                Placeholder.unparsed("rang", String.valueOf(rang)),
                Placeholder.unparsed("name", name != null ? name : "Unbekannt"),
                Placeholder.unparsed("zeit", Stempeluhr.formatiere(platz.zeit())));
    }

    private static void hilfe(CommandSender sender, String label) {
        senden(sender, "<gray>Verwendung: <white><syntax>",
                Placeholder.unparsed("syntax", "/" + label + " arbeitszeit <tag|woche>"));
    }

    private static void senden(CommandSender empfaenger, String nachricht, TagResolver... resolver) {
        empfaenger.sendMessage(MM.deserialize(PREFIX + nachricht, resolver));
    }
}

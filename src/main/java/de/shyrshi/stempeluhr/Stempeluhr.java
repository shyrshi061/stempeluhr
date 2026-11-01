package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public class Stempeluhr extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Stempeluhr</gold>]</dark_gray> ";
    private static final long INAKTIVITAET_MS = 5 * 60_000L;

    private StempelDaten daten;
    private final Set<UUID> nachAbsturzBeendet = new HashSet<>();

    /** Zeitquelle in ms. Im Plugin die Systemuhr, in Tests austauschbar. */
    private LongSupplier uhr = System::currentTimeMillis;

    void setUhr(LongSupplier uhr) {
        this.uhr = uhr;
    }

    StempelDaten daten() {
        return daten;
    }

    long jetzt() {
        return uhr.getAsLong();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    public void onEnable() {
        daten = new StempelDaten(this);
        daten.laden();
        schliesseVerwaisteSchichten();
        getServer().getPluginManager().registerEvents(this, this);

        ArbeitszeitBefehl arbeitszeit = new ArbeitszeitBefehl(this);
        var befehl = getCommand("arbeitszeit");
        if (befehl != null) {
            befehl.setExecutor(arbeitszeit);
            befehl.setTabCompleter(arbeitszeit);
        }
        RanglisteBefehl rangliste = new RanglisteBefehl(this);
        var ranglisteBefehl = getCommand("rangliste");
        if (ranglisteBefehl != null) {
            ranglisteBefehl.setExecutor(rangliste);
            ranglisteBefehl.setTabCompleter(rangliste);
        }

        // Reload-Fall: bereits online Spieler nur mit frischer Bewegung einstempeln.
        boolean geaendert = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.warteAufBewegung) continue;
            long jetzt = uhr.getAsLong();
            if (e.letzteBewegung >= 0 && jetzt - e.letzteBewegung >= INAKTIVITAET_MS) {
                e.warteAufBewegung = true;
                geaendert = true;
            } else if (!e.istEingestempelt()) {
                einstempeln(e, jetzt);
                geaendert = true;
            }
        }
        if (geaendert) daten.speichern();

        // Jede Minute ein "Lebenszeichen" speichern, damit bei einem Absturz
        // höchstens ~1 Minute Arbeitszeit verloren geht.
        Bukkit.getScheduler().runTaskTimer(this, this::lebenszeichen, 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(this, this::pruefeInaktivitaet, 20L, 20L);
    }

    @Override
    public void onDisable() {
        // Server stoppt: alle noch eingestempelten Spieler sauber ausstempeln
        long jetzt = uhr.getAsLong();
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.istEingestempelt()) {
                long letzte = e.letzteBewegung >= 0 ? e.letzteBewegung : jetzt;
                if (jetzt - letzte >= INAKTIVITAET_MS) e.warteAufBewegung = true;
                ausstempeln(e, Math.min(jetzt, letzte + INAKTIVITAET_MS));
            }
        }
        nachAbsturzBeendet.clear();
        daten.speichern();
    }

    /** Schließt nach einem Absturz gespeicherte Schichten schon beim Start. */
    void schliesseVerwaisteSchichten() {
        boolean geaendert = false;
        for (Map.Entry<UUID, StempelDaten.Eintrag> eintrag : daten.alle().entrySet()) {
            StempelDaten.Eintrag e = eintrag.getValue();
            if (!e.istEingestempelt()) continue;
            long ende = e.lebenszeichen > e.schichtStart ? e.lebenszeichen : e.schichtStart;
            ausstempeln(e, ende);
            nachAbsturzBeendet.add(eintrag.getKey());
            geaendert = true;
        }
        if (geaendert) daten.speichern();
    }

    void lebenszeichen() {
        long jetzt = uhr.getAsLong();
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.istEingestempelt() && !pruefeInaktivitaet(p, e, jetzt)) e.lebenszeichen = jetzt;
        }
        daten.speichern();
    }

    /** Beendet Schichten genau fünf Minuten nach der letzten Positionsänderung. */
    void pruefeInaktivitaet() {
        long jetzt = uhr.getAsLong();
        boolean geaendert = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.istEingestempelt() && pruefeInaktivitaet(p, e, jetzt)) geaendert = true;
        }
        if (geaendert) daten.speichern();
    }

    private boolean pruefeInaktivitaet(Player p, StempelDaten.Eintrag e, long jetzt) {
        if (e.letzteBewegung < 0) e.letzteBewegung = jetzt;
        long letzte = e.letzteBewegung;
        if (jetzt - letzte < INAKTIVITAET_MS) return false;

        ausstempeln(e, letzte + INAKTIVITAET_MS);
        e.warteAufBewegung = true;
        senden(p, "<red>Arbeitszeit betrug du wirst ausgestochen");
        return true;
    }

    // ---------------------------------------------------------------- Events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        StempelDaten.Eintrag e = daten.get(p.getUniqueId());
        long jetzt = uhr.getAsLong();

        if (nachAbsturzBeendet.remove(p.getUniqueId())) {
            senden(p, "<yellow>Deine letzte Schicht wurde nach einem Serverabsturz automatisch beendet.");
        }

        // Offene Schicht aus einem Absturz? -> beim letzten Lebenszeichen beenden
        if (e.istEingestempelt()) {
            long ende = e.lebenszeichen > e.schichtStart ? e.lebenszeichen : e.schichtStart;
            ausstempeln(e, ende);
            senden(p, "<yellow>Deine letzte Schicht wurde nach einem Serverabsturz automatisch beendet.");
        }

        if (e.letzteSchicht >= 0) {
            senden(p, "<gray>Willkommen zurück! Deine letzte Schicht dauerte <gold><dauer></gold>.",
                    Placeholder.unparsed("dauer", formatiere(e.letzteSchicht)));
        }

        // Reconnects dürfen die Fünf-Minuten-Frist nicht erneut starten.
        if (!e.warteAufBewegung && e.letzteBewegung >= 0
                && jetzt - e.letzteBewegung >= INAKTIVITAET_MS) {
            e.warteAufBewegung = true;
        }

        if (e.warteAufBewegung) {
            senden(p, "<yellow>Bewege dich, dann wirst du automatisch für eine neue Schicht eingestempelt.");
        } else {
            einstempeln(e, jetzt);
            senden(p, "<green>Du wurdest eingestempelt. <gray>Beende die Schicht mit <white>/ausstempeln</white>.");
        }
        daten.speichern();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Location von = event.getFrom();
        Location nach = event.getTo();
        if (nach == null || (von.getWorld() == nach.getWorld()
                && Double.compare(von.getX(), nach.getX()) == 0
                && Double.compare(von.getY(), nach.getY()) == 0
                && Double.compare(von.getZ(), nach.getZ()) == 0)) {
            return; // Nur Kamera-Drehungen zählen nicht als Bewegung.
        }

        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        StempelDaten.Eintrag e = daten.get(uuid);
        long jetzt = uhr.getAsLong();
        if (e.istEingestempelt()) pruefeInaktivitaet(p, e, jetzt);
        e.letzteBewegung = jetzt;
        if (!e.istEingestempelt() && e.warteAufBewegung) {
            // Falls diese Bewegung erst nach der Frist kommt, beginnt die neue
            // Schicht jetzt. Die inaktive Zeit dazwischen bleibt ungezählt.
            einstempeln(e, jetzt);
            daten.speichern();
            senden(p, "<green>Du wurdest nach deiner Bewegung automatisch eingestempelt.");
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        StempelDaten.Eintrag e = daten.get(event.getPlayer().getUniqueId());
        if (e.istEingestempelt()) {
            long jetzt = uhr.getAsLong();
            long letzte = e.letzteBewegung >= 0 ? e.letzteBewegung : jetzt;
            if (jetzt - letzte >= INAKTIVITAET_MS) e.warteAufBewegung = true;
            ausstempeln(e, Math.min(jetzt, letzte + INAKTIVITAET_MS));
        }
        daten.speichern();
    }

    // ---------------------------------------------------------------- Befehle

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }
        StempelDaten.Eintrag e = daten.get(p.getUniqueId());
        long jetzt = uhr.getAsLong();

        switch (cmd.getName()) {
            case "ausstempeln" -> {
                if (!e.istEingestempelt()) {
                    if (e.warteAufBewegung) {
                        senden(p, "<red>Du bist wegen AFK ausgestempelt. <gray>Bewege dich, dann wirst du automatisch eingestempelt.");
                    } else {
                        senden(p, "<red>Du bist gerade nicht eingestempelt. <gray>Nutze <white>/einstempeln</white>.");
                    }
                    return true;
                }
                if (pruefeInaktivitaet(p, e, jetzt)) {
                    daten.speichern();
                    return true;
                }
                long dauer = ausstempeln(e, jetzt);
                daten.speichern();
                senden(p, "<green>Ausgestempelt! <gray>Du hast <gold><dauer></gold> gearbeitet.",
                        Placeholder.unparsed("dauer", formatiere(dauer)));
                senden(p, "<gray>Gesamtarbeitszeit: <gold><gesamt></gold>",
                        Placeholder.unparsed("gesamt", formatiere(e.gesamt)));
            }
            case "einstempeln" -> {
                if (e.istEingestempelt() && pruefeInaktivitaet(p, e, jetzt)) {
                    daten.speichern();
                    return true;
                }
                if (e.warteAufBewegung) {
                    senden(p, "<red>Bewege dich zuerst; dann wirst du automatisch eingestempelt.");
                    return true;
                }
                if (e.istEingestempelt()) {
                    senden(p, "<red>Du bist bereits eingestempelt (seit <gold><dauer></gold>).",
                            Placeholder.unparsed("dauer", formatiere(jetzt - e.schichtStart)));
                    return true;
                }
                if (e.letzteBewegung >= 0 && jetzt - e.letzteBewegung >= INAKTIVITAET_MS) {
                    senden(p, "<red>Bewege dich zuerst, bevor du dich erneut einstempelst.");
                    return true;
                }
                einstempeln(e, jetzt);
                daten.speichern();
                senden(p, "<green>Du wurdest eingestempelt.");
            }
            case "schicht" -> {
                if (e.istEingestempelt() && pruefeInaktivitaet(p, e, jetzt)) daten.speichern();
                if (e.istEingestempelt()) {
                    senden(p, "<gray>Laufende Schicht: <gold><dauer></gold>",
                            Placeholder.unparsed("dauer", formatiere(jetzt - e.schichtStart)));
                } else {
                    senden(p, "<gray>Du bist aktuell <red>ausgestempelt</red>.");
                }
                if (e.letzteSchicht >= 0) {
                    senden(p, "<gray>Letzte Schicht: <gold><dauer></gold>",
                            Placeholder.unparsed("dauer", formatiere(e.letzteSchicht)));
                }
                senden(p, "<gray>Gesamtarbeitszeit: <gold><gesamt></gold>",
                        Placeholder.unparsed("gesamt", formatiere(e.gesamt)));
            }
            case "tagesschicht" -> {
                if (args.length != 0) {
                    senden(p, "<red>Verwendung: <white>/tagesschicht");
                    return true;
                }
                if (e.istEingestempelt() && pruefeInaktivitaet(p, e, jetzt)) daten.speichern();
                senden(p, "<gray>Deine Arbeitszeit heute seit 00:00 Uhr: <gold><dauer></gold>",
                        Placeholder.unparsed("dauer", formatiere(ZeitAuswertung.heute(e, jetzt))));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Logik

    private void einstempeln(StempelDaten.Eintrag e, long zeitpunkt) {
        e.schichtStart = zeitpunkt;
        e.lebenszeichen = zeitpunkt;
        if (e.letzteBewegung < 0) e.letzteBewegung = zeitpunkt;
        e.warteAufBewegung = false;
    }

    /** Beendet die laufende Schicht und gibt ihre Dauer in ms zurück. */
    private long ausstempeln(StempelDaten.Eintrag e, long ende) {
        long dauer = Math.max(0, ende - e.schichtStart);
        ZeitAuswertung.verbuche(e, e.schichtStart, ende);
        e.letzteSchicht = dauer;
        if (e.gesamt > Long.MAX_VALUE - dauer) {
            e.gesamt = Long.MAX_VALUE;
            getLogger().warning("Gesamtarbeitszeit hat den darstellbaren Höchstwert erreicht.");
        } else {
            e.gesamt += dauer;
        }
        e.schichtStart = -1;
        e.lebenszeichen = -1;
        return dauer;
    }

    // ---------------------------------------------------------------- Hilfen

    private void senden(Player p, String nachricht,
                        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolver) {
        Component c = MM.deserialize(PREFIX + nachricht, resolver);
        p.sendMessage(c);
    }

    /** Formatiert Millisekunden als z. B. "2 Std 5 Min 12 Sek" */
    static String formatiere(long ms) {
        long sek = ms / 1000;
        long tage = sek / 86_400;
        long std = (sek % 86_400) / 3600;
        long min = (sek % 3600) / 60;
        long s = sek % 60;

        StringBuilder sb = new StringBuilder();
        if (tage > 0) sb.append(tage).append(tage == 1 ? " Tag " : " Tage ");
        if (tage > 0 || std > 0) sb.append(std).append(" Std ");
        if (tage > 0 || std > 0 || min > 0) sb.append(min).append(" Min ");
        sb.append(s).append(" Sek");
        return sb.toString();
    }
}

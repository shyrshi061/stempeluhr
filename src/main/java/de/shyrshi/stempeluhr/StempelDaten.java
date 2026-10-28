package de.shyrshi.stempeluhr;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Speichert die Stempeldaten aller Spieler in plugins/Stempeluhr/daten.yml.
 *
 * Pro Spieler:
 *  - schichtStart : Zeitstempel (ms) der laufenden Schicht, fehlt wenn ausgestempelt
 *  - lebenszeichen: zuletzt bekannter Zeitpunkt online (für Absturz-Wiederherstellung)
 *  - letzteSchicht: Dauer der zuletzt beendeten Schicht (ms)
 *  - gesamt       : Summe aller beendeten Schichten (ms)
 *  - tage         : Abgeschlossene Arbeitszeit pro Datum in Europe/Berlin
 *  - warteAufBewegung: Nach AFK-Timeout erst bei Bewegung neu einstempeln
 *  - letzteBewegung: Letzte echte Bewegung auch über Quit/Reload hinweg
 */
final class StempelDaten {

    static final class Eintrag {
        long schichtStart = -1;
        long lebenszeichen = -1;
        long letzteSchicht = -1;
        long gesamt = 0;
        final Map<LocalDate, Long> tage = new TreeMap<>();
        boolean warteAufBewegung;
        long letzteBewegung = -1;

        boolean istEingestempelt() {
            return schichtStart > 0;
        }
    }

    private static final String DATEINAME = "daten.yml";
    // Jenseits von Jahr 9999 gibt es keinen plausiblen Minecraft-Zeitstempel.
    private static final long MAX_ZEITSTEMPEL_MS = 253_402_300_799_999L;

    private final JavaPlugin plugin;
    private final File datei;
    private final Map<UUID, Eintrag> eintraege = new HashMap<>();
    private boolean speichernGesperrt;

    StempelDaten(JavaPlugin plugin) {
        this.plugin = plugin;
        this.datei = new File(plugin.getDataFolder(), DATEINAME);
    }

    Eintrag get(UUID uuid) {
        return eintraege.computeIfAbsent(uuid, k -> new Eintrag());
    }

    /** Alle bekannten Einträge (nur lesend), z. B. für die Rangliste. */
    Map<UUID, Eintrag> alle() {
        return Collections.unmodifiableMap(eintraege);
    }

    void laden() {
        if (!datei.exists()) {
            eintraege.clear();
            speichernGesperrt = false;
            return;
        }

        // loadConfiguration(File) verschluckt Parsefehler und liefert dann eine
        // leere Konfiguration. Das würde beim nächsten Speichern alle Zeiten löschen.
        speichernGesperrt = true;
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(datei);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Konnte " + DATEINAME + " nicht laden; Speichern ist gesperrt.", ex);
            throw new IllegalStateException("Stempeldaten konnten nicht geladen werden", ex);
        }

        ConfigurationSection spieler = yml.getConfigurationSection("spieler");
        if (spieler == null && !yml.getKeys(false).isEmpty()) {
            plugin.getLogger().severe("Ungültige Struktur in " + DATEINAME + "; Speichern ist gesperrt.");
            throw new IllegalStateException("Ungültige Struktur in " + DATEINAME);
        }

        Map<UUID, Eintrag> geladen = new HashMap<>();
        if (spieler != null) {
            for (String key : spieler.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("Ungültige UUID in " + DATEINAME + ": " + key, ex);
                }
                if (!uuid.toString().equals(key)) {
                    throw new IllegalStateException("Nicht-kanonische UUID in " + DATEINAME + ": " + key);
                }
                ConfigurationSection s = spieler.getConfigurationSection(key);
                if (s == null) {
                    throw new IllegalStateException("Ungültiger Spielereintrag in " + DATEINAME + ": " + key);
                }
                if (!s.getKeys(false).contains("gesamt")) {
                    throw new IllegalStateException("Fehlende Gesamtzeit in " + DATEINAME + ": " + key);
                }

                Eintrag e = new Eintrag();
                e.schichtStart = leseZeitstempel(s, "schichtStart");
                e.lebenszeichen = leseZeitstempel(s, "lebenszeichen");
                e.letzteBewegung = leseZeitstempel(s, "letzteBewegung");
                e.letzteSchicht = leseZeit(s, "letzteSchicht", -1);
                e.gesamt = Math.max(0, leseZeit(s, "gesamt", 0));   // manipulierte negative Werte abfangen
                Object wartend = s.get("warteAufBewegung");
                if (s.getKeys(false).contains("warteAufBewegung")) {
                    if (!(wartend instanceof Boolean)) {
                        throw new IllegalStateException("Ungültiger AFK-Status in " + DATEINAME + ": " + key);
                    }
                    e.warteAufBewegung = (Boolean) wartend;
                }
                pruefeEintrag(e, key);

                if (s.getKeys(false).contains("tage")) {
                    ConfigurationSection tage = s.getConfigurationSection("tage");
                    if (tage == null) {
                        throw new IllegalStateException("Ungültige Tageszeiten in " + DATEINAME + ": " + key);
                    }
                    for (String datumText : tage.getKeys(false)) {
                        LocalDate datum;
                        try {
                            datum = LocalDate.parse(datumText);
                        } catch (DateTimeParseException ex) {
                            throw new IllegalStateException("Ungültiges Datum in " + DATEINAME + ": " + datumText, ex);
                        }
                        long dauer = leseZeit(tage, datumText, -1);
                        if (dauer < 0) {
                            throw new IllegalStateException("Negative Tageszeit in " + DATEINAME + ": " + datumText);
                        }
                        e.tage.put(datum, dauer);
                    }
                }
                geladen.put(uuid, e);
            }
        }
        eintraege.clear();
        eintraege.putAll(geladen);
        speichernGesperrt = false;
    }

    /** Fehlende optionale Werte sind erlaubt, falsch typisierte Werte nicht. */
    private static long leseZeit(ConfigurationSection eintrag, String feld, long standard) {
        Object wert = eintrag.get(feld);
        if (wert == null && !eintrag.getKeys(false).contains(feld)) return standard;
        if (wert instanceof Byte || wert instanceof Short || wert instanceof Integer || wert instanceof Long) {
            return ((Number) wert).longValue();
        }
        throw new IllegalStateException("Ungültiger Zeitwert in " + DATEINAME + ": "
                + eintrag.getCurrentPath() + "." + feld);
    }

    private static long leseZeitstempel(ConfigurationSection eintrag, String feld) {
        long zeitpunkt = leseZeit(eintrag, feld, -1);
        pruefeZeitstempel(feld, zeitpunkt);
        return zeitpunkt;
    }

    private static void pruefeZeitstempel(String feld, long zeitpunkt) {
        if (zeitpunkt < -1 || zeitpunkt > MAX_ZEITSTEMPEL_MS) {
            throw new IllegalStateException("Ungültiger Zeitstempel in " + DATEINAME + ": " + feld);
        }
    }

    private static void pruefeEintrag(Eintrag e, String key) {
        if (e.schichtStart == 0 || e.lebenszeichen == 0
                || (e.schichtStart == -1) != (e.lebenszeichen == -1)) {
            throw new IllegalStateException("Widersprüchliche Schichtdaten in " + DATEINAME + ": " + key);
        }
        if (e.warteAufBewegung && e.istEingestempelt()) {
            throw new IllegalStateException("Widersprüchlicher AFK-Status in " + DATEINAME + ": " + key);
        }
        if (e.letzteSchicht < -1 || e.gesamt < 0) {
            throw new IllegalStateException("Ungültige Arbeitszeit in " + DATEINAME + ": " + key);
        }
        if (e.istEingestempelt() && e.lebenszeichen > e.schichtStart) {
            ZeitAuswertung.pruefeIntervall(e.schichtStart, e.lebenszeichen);
        }
    }

    void speichern() {
        if (speichernGesperrt) {
            plugin.getLogger().severe("Speichern von " + DATEINAME + " nach Ladefehler verhindert.");
            return;
        }

        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Eintrag> entry : eintraege.entrySet()) {
            String pfad = "spieler." + entry.getKey();
            Eintrag e = entry.getValue();
            pruefeZeitstempel("schichtStart", e.schichtStart);
            pruefeZeitstempel("lebenszeichen", e.lebenszeichen);
            pruefeZeitstempel("letzteBewegung", e.letzteBewegung);
            pruefeEintrag(e, entry.getKey().toString());
            if (e.istEingestempelt()) {
                yml.set(pfad + ".schichtStart", e.schichtStart);
                yml.set(pfad + ".lebenszeichen", e.lebenszeichen);
            }
            if (e.letzteSchicht >= 0) yml.set(pfad + ".letzteSchicht", e.letzteSchicht);
            yml.set(pfad + ".gesamt", e.gesamt);
            for (Map.Entry<LocalDate, Long> tag : e.tage.entrySet()) {
                if (tag.getValue() < 0) {
                    throw new IllegalStateException("Negative Tageszeit für " + entry.getKey());
                }
                yml.set(pfad + ".tage." + tag.getKey(), tag.getValue());
            }
            if (e.warteAufBewegung) yml.set(pfad + ".warteAufBewegung", true);
            if (e.letzteBewegung >= 0) yml.set(pfad + ".letzteBewegung", e.letzteBewegung);
        }
        Path temporaer = null;
        Path backupTemporaer = null;
        try {
            Path ordner = plugin.getDataFolder().toPath();
            Files.createDirectories(ordner);
            Path ziel = datei.toPath();
            Path backup = ordner.resolve(DATEINAME + ".bak");

            temporaer = Files.createTempFile(ordner, "daten-", ".tmp");
            yml.save(temporaer.toFile());

            // Die bisherige Version behalten, bevor die neue aktiviert wird.
            if (Files.exists(ziel)) {
                backupTemporaer = Files.createTempFile(ordner, "daten-backup-", ".tmp");
                Files.copy(ziel, backupTemporaer, StandardCopyOption.REPLACE_EXISTING);
                ersetzen(backupTemporaer, backup);
                backupTemporaer = null;
            }
            ersetzen(temporaer, ziel);
            temporaer = null;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte " + DATEINAME + " nicht speichern", ex);
            throw new IllegalStateException("Stempeldaten konnten nicht gespeichert werden", ex);
        } finally {
            loeschen(temporaer);
            loeschen(backupTemporaer);
        }
    }

    private static void ersetzen(Path quelle, Path ziel) throws IOException {
        try {
            Files.move(quelle, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ex) {
            // Auf Dateisystemen ohne atomare Umbenennung bleibt die Sicherung erhalten.
            Files.move(quelle, ziel, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loeschen(Path pfad) {
        if (pfad == null) return;
        try {
            Files.deleteIfExists(pfad);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Temporäre Stempeldaten-Datei konnte nicht gelöscht werden", ex);
        }
    }
}

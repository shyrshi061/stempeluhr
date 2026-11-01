package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StempeluhrTest {

    private static final long START = 1_800_000_000_000L;

    private ServerMock server;
    private Stempeluhr plugin;
    private final AtomicLong jetzt = new AtomicLong(START);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Stempeluhr.class);
        plugin.setUhr(jetzt::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------ Hilfen

    /** Holt alle bisher empfangenen Chatnachrichten als Klartext. */
    private static List<String> nachrichten(PlayerMock p) {
        List<String> liste = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            liste.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return liste;
    }

    private static boolean enthaelt(List<String> nachrichten, String text) {
        return nachrichten.stream().anyMatch(n -> n.contains(text));
    }

    private void vergehen(long sekunden) {
        jetzt.addAndGet(sekunden * 1000);
    }

    /** Simuliert eine aktive Schicht mit Positionsänderungen spätestens alle vier Minuten. */
    private void aktivVergehen(PlayerMock p, long sekunden) {
        while (sekunden > 0) {
            long schritt = Math.min(4 * 60, sekunden);
            vergehen(schritt);
            Location von = p.getLocation();
            server.getPluginManager().callEvent(new PlayerMoveEvent(p, von, von.clone().add(1, 0, 0)));
            sekunden -= schritt;
        }
    }

    // ------------------------------------------------------------ Funktion

    @Nested
    @DisplayName("Einstempeln und Ausstempeln")
    class Funktion {

        @Test
        @DisplayName("Join stempelt automatisch ein")
        void joinStempeltEin() {
            PlayerMock p = server.addPlayer();

            assertTrue(plugin.daten().get(p.getUniqueId()).istEingestempelt());
            assertTrue(enthaelt(nachrichten(p), "eingestempelt"));
        }

        @Test
        @DisplayName("/ausstempeln zeigt die richtige Dauer")
        void ausstempelnZeigtDauer() {
            PlayerMock p = server.addPlayer();
            nachrichten(p);
            aktivVergehen(p, 2 * 3600 + 5 * 60 + 12);

            assertTrue(p.performCommand("ausstempeln"));

            List<String> n = nachrichten(p);
            assertTrue(enthaelt(n, "2 Std 5 Min 12 Sek"), n.toString());
            assertFalse(plugin.daten().get(p.getUniqueId()).istEingestempelt());
        }

        @Test
        @DisplayName("Doppeltes Ausstempeln wird abgelehnt und zählt nicht doppelt")
        void doppeltesAusstempeln() {
            PlayerMock p = server.addPlayer();
            vergehen(60);
            p.performCommand("ausstempeln");
            long gesamtVorher = plugin.daten().get(p.getUniqueId()).gesamt;
            nachrichten(p);

            vergehen(60);
            p.performCommand("ausstempeln");

            assertTrue(enthaelt(nachrichten(p), "nicht eingestempelt"));
            assertEquals(gesamtVorher, plugin.daten().get(p.getUniqueId()).gesamt);
        }

        @Test
        @DisplayName("Doppeltes Einstempeln setzt die Startzeit nicht zurück")
        void doppeltesEinstempeln() {
            PlayerMock p = server.addPlayer();
            long start = plugin.daten().get(p.getUniqueId()).schichtStart;
            vergehen(299);

            p.performCommand("einstempeln");

            assertEquals(start, plugin.daten().get(p.getUniqueId()).schichtStart);
            assertTrue(enthaelt(nachrichten(p), "bereits eingestempelt"));
        }

        @Test
        @DisplayName("Verlassen stempelt aus, Rejoin zeigt die letzte Schicht")
        void quitUndRejoin() {
            PlayerMock p = server.addPlayer();
            aktivVergehen(p, 45 * 60);

            p.disconnect();
            assertFalse(plugin.daten().get(p.getUniqueId()).istEingestempelt());

            vergehen(3600);   // Pause zählt nicht
            nachrichten(p);
            p.reconnect();

            List<String> n = nachrichten(p);
            assertTrue(enthaelt(n, "letzte Schicht dauerte 45 Min 0 Sek"), n.toString());
            assertFalse(plugin.daten().get(p.getUniqueId()).istEingestempelt());
            assertTrue(plugin.daten().get(p.getUniqueId()).warteAufBewegung);
            aktivVergehen(p, 1);
            assertTrue(plugin.daten().get(p.getUniqueId()).istEingestempelt());
        }

        @Test
        @DisplayName("Absturz: offene Schicht endet beim letzten Lebenszeichen")
        void absturzWiederherstellung() {
            PlayerMock p = new PlayerMock(server, "Anton");
            StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
            e.schichtStart = START - 10 * 60_000;     // vor 10 Min eingestempelt
            e.lebenszeichen = START - 4 * 60_000;     // letztes Lebenszeichen vor 4 Min

            server.addPlayer(p);

            assertEquals(6 * 60_000, e.letzteSchicht);
            assertTrue(enthaelt(nachrichten(p), "Serverabsturz"));
        }

        @Test
        @DisplayName("Verwaiste Schicht wird beim Start beendet und wächst in der Rangliste nicht weiter")
        void verwaisteSchichtBeimStart() {
            PlayerMock p = new PlayerMock(server, "Anton");
            StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
            e.schichtStart = START - 10 * 60_000;
            e.lebenszeichen = START - 4 * 60_000;

            plugin.schliesseVerwaisteSchichten();
            vergehen(60 * 60);

            assertFalse(e.istEingestempelt());
            assertEquals(6 * 60_000, e.gesamt);
            assertEquals(6 * 60_000, ArbeitszeitBefehl.berechneRangliste(
                    plugin.daten().alle(), plugin.jetzt()).getFirst().zeit());

            server.addPlayer(p);
            assertTrue(enthaelt(nachrichten(p), "Serverabsturz"));
        }

        @Test
        @DisplayName("Daten bleiben nach dem Speichern erhalten")
        void persistenz() {
            PlayerMock p = server.addPlayer();
            vergehen(90);
            p.performCommand("ausstempeln");

            StempelDaten neu = new StempelDaten(plugin);
            neu.laden();

            assertEquals(90_000, neu.get(p.getUniqueId()).gesamt);
            assertEquals(90_000, neu.get(p.getUniqueId()).letzteSchicht);
        }

        @Test
        @DisplayName("Gesamtzeit läuft beim Ausstempeln nicht über")
        void gesamtzeitSaettigtBeiUeberlauf() {
            PlayerMock p = server.addPlayer();
            plugin.daten().get(p.getUniqueId()).gesamt = Long.MAX_VALUE - 1;
            vergehen(2);

            p.performCommand("ausstempeln");

            assertEquals(Long.MAX_VALUE, plugin.daten().get(p.getUniqueId()).gesamt);
            StempelDaten neu = new StempelDaten(plugin);
            neu.laden();
            assertEquals(Long.MAX_VALUE, neu.get(p.getUniqueId()).gesamt);
        }
    }

    // ------------------------------------------------------------ Robustheit / Sicherheit

    @Nested
    @DisplayName("Robustheit gegen fehlerhafte oder manipulierte Eingaben")
    class Robustheit {

        @Test
        @DisplayName("Befehle von der Konsole werden sauber abgefangen")
        void konsolenBefehl() {
            assertDoesNotThrow(() -> server.dispatchCommand(server.getConsoleSender(), "ausstempeln"));
            assertDoesNotThrow(() -> server.dispatchCommand(server.getConsoleSender(), "schicht"));
        }

        @Test
        @DisplayName("Ungültige UUIDs in daten.yml sperren das Speichern")
        void ungueltigeUuid() throws Exception {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("spieler.kein-uuid.gesamt", 5000L);
            yml.set("spieler.00000000-0000-0000-0000-000000000001.gesamt", 7000L);
            plugin.getDataFolder().mkdirs();
            yml.save(new File(plugin.getDataFolder(), "daten.yml"));
            byte[] vorher = java.nio.file.Files.readAllBytes(
                    new File(plugin.getDataFolder(), "daten.yml").toPath());

            StempelDaten d = new StempelDaten(plugin);
            assertThrows(IllegalStateException.class, d::laden);
            d.speichern();
            assertArrayEquals(vorher, java.nio.file.Files.readAllBytes(
                    new File(plugin.getDataFolder(), "daten.yml").toPath()));
        }

        @Test
        @DisplayName("Negative Gesamtzeit aus manipulierter daten.yml wird auf 0 gesetzt")
        void negativeGesamtzeit() throws Exception {
            java.util.UUID id = java.util.UUID.randomUUID();
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("spieler." + id + ".gesamt", -999_999L);
            plugin.getDataFolder().mkdirs();
            yml.save(new File(plugin.getDataFolder(), "daten.yml"));

            StempelDaten d = new StempelDaten(plugin);
            d.laden();

            assertEquals(0, d.get(id).gesamt);
        }

        @Test
        @DisplayName("Uhr springt zurück: keine negative Schichtdauer")
        void uhrRueckwaerts() {
            PlayerMock p = server.addPlayer();
            jetzt.set(START - 3_600_000);   // Systemzeit 1 Std zurückgestellt

            p.performCommand("ausstempeln");

            assertEquals(0, plugin.daten().get(p.getUniqueId()).letzteSchicht);
        }
    }

    // ------------------------------------------------------------ Formatierung

    @Test
    @DisplayName("Dauer wird korrekt formatiert")
    void formatierung() {
        assertEquals("0 Sek", Stempeluhr.formatiere(0));
        assertEquals("59 Sek", Stempeluhr.formatiere(59_999));
        assertEquals("1 Min 0 Sek", Stempeluhr.formatiere(60_000));
        assertEquals("1 Std 0 Min 1 Sek", Stempeluhr.formatiere(3_601_000));
        assertEquals("1 Tag 0 Std 0 Min 0 Sek", Stempeluhr.formatiere(86_400_000));
        assertEquals("3 Tage 2 Std 0 Min 5 Sek", Stempeluhr.formatiere((3 * 86_400 + 2 * 3600 + 5) * 1000L));
    }

    // ------------------------------------------------------------ /arbeitszeit

    @Nested
    @DisplayName("/arbeitszeit")
    class Arbeitszeit {

        @Test
        @DisplayName("Format dd hh mm ss wird korrekt gelesen")
        void parseGueltig() {
            assertEquals(0, ArbeitszeitBefehl.parse("00", "00", "00", "00"));
            assertEquals(((1 * 24 + 8) * 60 + 30) * 60_000L, ArbeitszeitBefehl.parse("01", "08", "30", "00"));
            assertEquals(5_000, ArbeitszeitBefehl.parse("0", "0", "0", "5"));
        }

        @Test
        @DisplayName("Ungültige Zeitangaben werden abgelehnt")
        void parseUngueltig() {
            String[][] faelle = {
                    {"", "0", "0", "0"}, {"1", "24", "00", "00"}, {"1", "00", "60", "00"},
                    {"1", "00", "00", "60"}, {"-1", "00", "00", "00"}, {"a", "b", "c", "d"},
                    {"99999", "00", "00", "00"}, {"1", "100", "0", "0"}, {"1.0", "0", "0", "0"}
            };
            for (String[] f : faelle) {
                assertEquals(-1, ArbeitszeitBefehl.parse(f[0], f[1], f[2], f[3]),
                        "sollte ungültig sein: " + String.join(" ", f));
            }
        }

        @Test
        @DisplayName("Admin kann Gesamtzeit eines Spielers setzen")
        void adminSetzt() {
            PlayerMock admin = server.addPlayer("Admin");
            admin.setOp(true);
            PlayerMock ziel = server.addPlayer("Leon");
            nachrichten(admin);
            nachrichten(ziel);

            assertTrue(admin.performCommand("arbeitszeit set Leon 02 03 04 05"));

            long erwartet = ((2 * 24 + 3) * 60 + 4) * 60_000L + 5_000;
            assertEquals(erwartet, plugin.daten().get(ziel.getUniqueId()).gesamt);
            assertTrue(enthaelt(nachrichten(admin), "2 Tage 3 Std 4 Min 5 Sek"));
            assertTrue(enthaelt(nachrichten(ziel), "von einem Admin"));
        }

        @Test
        @DisplayName("Laufende Schicht wird nach dem Setzen weiter addiert")
        void setzenWaehrendSchicht() {
            PlayerMock admin = server.addPlayer("Admin");
            admin.setOp(true);
            PlayerMock ziel = server.addPlayer("Leon");
            admin.performCommand("arbeitszeit set Leon 00 01 00 00");
            aktivVergehen(ziel, 600);

            ziel.performCommand("ausstempeln");

            assertEquals(3_600_000 + 600_000, plugin.daten().get(ziel.getUniqueId()).gesamt);
        }

        @Test
        @DisplayName("Ohne Permission keine Änderung")
        void ohnePermission() {
            PlayerMock spieler = server.addPlayer("Normalo");
            PlayerMock ziel = server.addPlayer("Leon");
            long vorher = plugin.daten().get(ziel.getUniqueId()).gesamt;

            spieler.performCommand("arbeitszeit set Leon 99 00 00 00");

            assertEquals(vorher, plugin.daten().get(ziel.getUniqueId()).gesamt);
        }

        @Test
        @DisplayName("Unbekannter Spieler und falsches Format ändern nichts")
        void fehlerfaelle() {
            PlayerMock admin = server.addPlayer("Admin");
            admin.setOp(true);
            PlayerMock ziel = server.addPlayer("Leon");
            nachrichten(admin);

            admin.performCommand("arbeitszeit set GibtsNicht 01 00 00 00");
            assertTrue(enthaelt(nachrichten(admin), "unbekannt"));

            admin.performCommand("arbeitszeit set Leon 1 25 00 00");
            assertTrue(enthaelt(nachrichten(admin), "Ungültiges Format"));
            assertEquals(0, plugin.daten().get(ziel.getUniqueId()).gesamt);
        }

        @Test
        @DisplayName("MiniMessage-Tags im Spielernamen werden nicht ausgeführt")
        void keineTagInjection() {
            PlayerMock admin = server.addPlayer("Admin");
            admin.setOp(true);
            nachrichten(admin);

            admin.performCommand("arbeitszeit get <red><bold>x</bold>");

            List<String> n = nachrichten(admin);
            assertTrue(enthaelt(n, "<red><bold>x</bold>"), "Tags müssen als Klartext erscheinen: " + n);
        }

        @Test
        @DisplayName("Konsole darf /arbeitszeit nutzen")
        void konsole() {
            PlayerMock ziel = server.addPlayer("Leon");

            server.dispatchCommand(server.getConsoleSender(), "arbeitszeit set Leon 00 00 10 00");

            assertEquals(600_000, plugin.daten().get(ziel.getUniqueId()).gesamt);
        }
    }

    // ------------------------------------------------------------ /arbeitszeit rangliste

    @Nested
    @DisplayName("/arbeitszeit rangliste")
    class Rangliste {

        @Test
        @DisplayName("Sortiert absteigend und rechnet laufende Schichten mit ein")
        void sortierung() {
            PlayerMock anna = server.addPlayer("Anna");
            PlayerMock ben = server.addPlayer("Ben");
            anna.performCommand("ausstempeln");
            plugin.daten().get(anna.getUniqueId()).gesamt = 10 * 60_000;   // 10 Min, ausgestempelt
            plugin.daten().get(ben.getUniqueId()).gesamt = 5 * 60_000;     // 5 Min + laufende Schicht
            aktivVergehen(ben, 10 * 60);
            nachrichten(ben);

            assertTrue(ben.performCommand("arbeitszeit rangliste"));

            List<String> n = nachrichten(ben);
            assertTrue(enthaelt(n, "#1 Ben – 15 Min 0 Sek"), n.toString());
            assertTrue(enthaelt(n, "#2 Anna – 10 Min 0 Sek"), n.toString());
        }

        @Test
        @DisplayName("Eigener Platz wird außerhalb der Top 10 angehängt")
        void eigenerPlatz() {
            for (int i = 0; i < ArbeitszeitBefehl.RANGLISTE_GROESSE + 1; i++) {
                PlayerMock p = server.addPlayer("Spieler" + i);
                plugin.daten().get(p.getUniqueId()).gesamt = (i + 2) * 60_000L;
            }
            PlayerMock ich = server.addPlayer("Ich");
            plugin.daten().get(ich.getUniqueId()).gesamt = 1_000;
            nachrichten(ich);

            ich.performCommand("arbeitszeit rangliste");

            List<String> n = nachrichten(ich);
            assertFalse(enthaelt(n, "#11 "), n.toString());
            assertTrue(enthaelt(n, "#12 Ich – 1 Sek"), n.toString());
        }

        @Test
        @DisplayName("Leere Rangliste und Konsole")
        void leerUndKonsole() {
            assertDoesNotThrow(() -> server.dispatchCommand(server.getConsoleSender(), "arbeitszeit rangliste"));
        }

        @Test
        @DisplayName("Spieler ohne Zeit fehlen, zurückgestellte Uhr ergibt keine negative Zeit")
        void berechnung() {
            StempelDaten.Eintrag leer = new StempelDaten.Eintrag();
            StempelDaten.Eintrag laufend = new StempelDaten.Eintrag();
            laufend.schichtStart = START;
            laufend.gesamt = 5_000;
            java.util.UUID idLeer = java.util.UUID.randomUUID();
            java.util.UUID idLaufend = java.util.UUID.randomUUID();

            List<ArbeitszeitBefehl.Platz> plaetze = ArbeitszeitBefehl.berechneRangliste(
                    java.util.Map.of(idLeer, leer, idLaufend, laufend), START - 3_600_000);

            assertEquals(List.of(new ArbeitszeitBefehl.Platz(idLaufend, 5_000)), plaetze);
        }

        @Test
        @DisplayName("Normale Spieler dürfen die Rangliste sehen, aber nicht set/get")
        void permissions() {
            PlayerMock spieler = server.addPlayer("Normalo");
            vergehen(60);
            nachrichten(spieler);

            spieler.performCommand("arbeitszeit rangliste");
            assertTrue(enthaelt(nachrichten(spieler), "Rangliste"));

            spieler.performCommand("arbeitszeit get Normalo");
            assertTrue(enthaelt(nachrichten(spieler), "Berechtigung"));
        }
    }
}

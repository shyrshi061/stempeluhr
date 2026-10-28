package de.shyrshi.stempeluhr;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StempelDatenPersistenzTest {

    private ServerMock server;
    private Stempeluhr plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Stempeluhr.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ladefehlerBewahrtDateiUndSpeichertNicht() throws Exception {
        StempelDaten daten = plugin.daten();
        UUID uuid = UUID.randomUUID();
        daten.get(uuid).gesamt = 12_345;
        Path datei = plugin.getDataFolder().toPath().resolve("daten.yml");
        Files.createDirectories(datei.getParent());
        byte[] defekt = "spieler: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(datei, defekt);

        assertThrows(IllegalStateException.class, daten::laden);
        assertEquals(12_345, daten.get(uuid).gesamt,
                "Ein fehlgeschlagener Ladevorgang darf den Speicherstand nicht leeren");

        daten.get(uuid).gesamt = 67_890;
        daten.speichern();
        assertArrayEquals(defekt, Files.readAllBytes(datei),
                "Ein späteres Speichern darf die defekte Datei nicht überschreiben");
    }

    @Test
    void parsebareAberBeschaedigteSpielerdatenWerdenNichtUeberschrieben() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path datei = plugin.getDataFolder().toPath().resolve("daten.yml");
        Files.createDirectories(datei.getParent());
        String[] defekteDateien = {
                "spieler:\n  0-0-0-0-1:\n    gesamt: 1000\n",
                "spieler:\n  " + uuid + ": kaputt\n",
                "spieler:\n  " + uuid + ":\n    gesamt: kaputt\n",
                "spieler:\n  " + uuid + ":\n    letzteSchicht: 1000\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    tage: kaputt\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    tage:\n      falsch: 1000\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    tage:\n      2026-10-28: -1\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    warteAufBewegung: kaputt\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    schichtStart: 1000\n    warteAufBewegung: true\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    letzteBewegung: kaputt\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    letzteBewegung: 9223372036854775807\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    letzteSchicht: -2\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    schichtStart: 0\n    lebenszeichen: 1000\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    schichtStart: 1000\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    lebenszeichen: 1000\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    schichtStart: 1\n    lebenszeichen: 9223372036854775807\n",
                "spieler:\n  " + uuid + ":\n    gesamt: 1000\n    schichtStart: 1\n    lebenszeichen: 40000000000\n"
        };

        for (String inhalt : defekteDateien) {
            byte[] original = inhalt.getBytes(StandardCharsets.UTF_8);
            Files.write(datei, original);
            StempelDaten daten = new StempelDaten(plugin);
            daten.get(uuid).gesamt = 12_345;

            assertThrows(IllegalStateException.class, daten::laden);
            assertEquals(12_345, daten.get(uuid).gesamt);
            daten.speichern();
            assertArrayEquals(original, Files.readAllBytes(datei),
                    "Beschädigte Einträge dürfen nicht stillschweigend gelöscht werden");
        }
    }

    @Test
    void zweiteSpeicherungBehältVorherigeVersionAlsBackup() throws Exception {
        StempelDaten daten = plugin.daten();
        UUID uuid = UUID.randomUUID();
        StempelDaten.Eintrag eintrag = daten.get(uuid);
        Path ordner = plugin.getDataFolder().toPath();
        Path datei = ordner.resolve("daten.yml");
        Path backup = ordner.resolve("daten.yml.bak");

        eintrag.gesamt = 60_000;
        daten.speichern();
        String vorher = Files.readString(datei);

        eintrag.gesamt = 120_000;
        daten.speichern();

        assertEquals(vorher, Files.readString(backup));
        assertEquals(60_000, YamlConfiguration.loadConfiguration(backup.toFile())
                .getLong("spieler." + uuid + ".gesamt"));
        assertEquals(120_000, YamlConfiguration.loadConfiguration(datei.toFile())
                .getLong("spieler." + uuid + ".gesamt"));
    }

    @Test
    void tageszeitenUndAfkStatusBleibenNachNeuladenErhalten() {
        UUID uuid = UUID.randomUUID();
        StempelDaten.Eintrag eintrag = plugin.daten().get(uuid);
        eintrag.gesamt = 90_000;
        eintrag.tage.put(LocalDate.of(2026, 10, 28), 90_000L);
        eintrag.warteAufBewegung = true;
        eintrag.letzteBewegung = 1_800_000_000_000L;
        UUID aktiv = UUID.randomUUID();
        StempelDaten.Eintrag aktiverEintrag = plugin.daten().get(aktiv);
        aktiverEintrag.schichtStart = 1_800_000_000_000L;
        aktiverEintrag.lebenszeichen = 1_800_000_000_000L;
        aktiverEintrag.letzteBewegung = 1_800_000_000_000L;
        plugin.daten().speichern();

        StempelDaten neu = new StempelDaten(plugin);
        neu.laden();

        assertEquals(90_000L, neu.get(uuid).tage.get(LocalDate.of(2026, 10, 28)));
        assertTrue(neu.get(uuid).warteAufBewegung);
        assertEquals(1_800_000_000_000L, neu.get(uuid).letzteBewegung);
        assertEquals(1_800_000_000_000L, neu.get(aktiv).letzteBewegung);
    }

    @Test
    void alteDateiOhneLetzteBewegungBleibtLesbar() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path datei = plugin.getDataFolder().toPath().resolve("daten.yml");
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, "spieler:\n  " + uuid + ":\n    gesamt: 1000\n");

        StempelDaten neu = new StempelDaten(plugin);
        neu.laden();

        assertEquals(1000, neu.get(uuid).gesamt);
        assertEquals(-1, neu.get(uuid).letzteBewegung);
    }

    @Test
    void schreibfehlerWirdSichtbarUndAlteDateiBleibtErhalten() throws Exception {
        StempelDaten daten = plugin.daten();
        UUID uuid = UUID.randomUUID();
        daten.get(uuid).gesamt = 1000;
        daten.speichern();
        Path ordner = plugin.getDataFolder().toPath();
        Path datei = ordner.resolve("daten.yml");
        String vorher = Files.readString(datei);
        Path blockiertesBackup = ordner.resolve("daten.yml.bak");
        Files.createDirectory(blockiertesBackup);
        Files.writeString(blockiertesBackup.resolve("block"), "x");

        try {
            daten.get(uuid).gesamt = 2000;
            assertThrows(IllegalStateException.class, daten::speichern);
            assertEquals(vorher, Files.readString(datei));
        } finally {
            Files.deleteIfExists(blockiertesBackup.resolve("block"));
            Files.deleteIfExists(blockiertesBackup);
        }
    }
}

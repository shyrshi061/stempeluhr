package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RanglisteBefehlTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long MITTWOCH = zeit(2026, 10, 28, 12);

    private final AtomicLong jetzt = new AtomicLong(MITTWOCH);
    private ServerMock server;
    private Stempeluhr plugin;

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

    private static long zeit(int jahr, int monat, int tag, int stunde) {
        return ZonedDateTime.of(jahr, monat, tag, stunde, 0, 0, 0, BERLIN)
                .toInstant().toEpochMilli();
    }

    private static List<String> nachrichten(PlayerMock p) {
        List<String> liste = new ArrayList<>();
        Component nachricht;
        while ((nachricht = p.nextComponentMessage()) != null) {
            liste.add(PlainTextComponentSerializer.plainText().serialize(nachricht));
        }
        return liste;
    }

    private static void enthaelt(List<String> nachrichten, String ausschnitt) {
        assertTrue(nachrichten.stream().anyMatch(n -> n.contains(ausschnitt)), nachrichten.toString());
    }

    @Test
    void tagesUndWochenranglisteTrennenEineAbgeschlosseneSchicht() {
        PlayerMock ben = server.addPlayer("Ben");
        StempelDaten.Eintrag eintrag = plugin.daten().get(ben.getUniqueId());
        eintrag.schichtStart = zeit(2026, 10, 26, 10); // Montag 10 Uhr bis Mittwoch 12 Uhr
        nachrichten(ben);

        assertTrue(ben.performCommand("ausstempeln"));
        nachrichten(ben);

        assertTrue(ben.performCommand("rangliste arbeitszeit tag"));
        List<String> tag = nachrichten(ben);
        enthaelt(tag, "Rangliste – Arbeitszeit heute");
        enthaelt(tag, "#1 Ben – 12 Std 0 Min 0 Sek");

        assertTrue(ben.performCommand("rangliste arbeitszeit woche"));
        List<String> woche = nachrichten(ben);
        enthaelt(woche, "Rangliste – Arbeitszeit diese Woche");
        enthaelt(woche, "#1 Ben – 2 Tage 2 Std 0 Min 0 Sek");
    }

    @Test
    void ungueltigeSyntaxZeigtHilfeUndTabVervollstaendigungIstBegrenzt() {
        PlayerMock spieler = server.addPlayer("Anna");
        nachrichten(spieler);

        assertTrue(spieler.performCommand("rangliste arbeitszeit immer"));
        enthaelt(nachrichten(spieler), "/rangliste arbeitszeit <tag|woche>");

        RanglisteBefehl befehl = new RanglisteBefehl(plugin);
        var command = plugin.getCommand("rangliste");
        assertEquals(List.of("arbeitszeit"), befehl.onTabComplete(spieler, command,
                "rangliste", new String[]{"arb"}));
        assertEquals(List.of("woche"), befehl.onTabComplete(spieler, command,
                "rangliste", new String[]{"arbeitszeit", "w"}));
        assertEquals(List.of(), befehl.onTabComplete(spieler, command,
                "rangliste", new String[]{"arbeitszeit", "woche", "extra"}));
    }

    @Test
    void eigenePlatzierungWirdAusserhalbDerTopZehnAngezeigt() {
        for (int i = 0; i < 11; i++) {
            PlayerMock p = server.addPlayer("Spieler" + i);
            plugin.daten().get(p.getUniqueId()).schichtStart = MITTWOCH - (12 - i) * 1_000L;
        }
        PlayerMock ich = server.addPlayer("Ich");
        plugin.daten().get(ich.getUniqueId()).schichtStart = MITTWOCH - 1_000;
        nachrichten(ich);

        assertTrue(ich.performCommand("rangliste arbeitszeit tag"));
        List<String> meldungen = nachrichten(ich);
        enthaelt(meldungen, "#1 Spieler0 – 12 Sek");
        enthaelt(meldungen, "#12 Ich – 1 Sek");
    }
}

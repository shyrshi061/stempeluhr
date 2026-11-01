package de.shyrshi.stempeluhr;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeitAuswertungTest {

    private static long zeit(int jahr, int monat, int tag, int stunde, int minute) {
        return LocalDateTime.of(jahr, monat, tag, stunde, minute)
                .atZone(ZeitAuswertung.ZEITZONE).toInstant().toEpochMilli();
    }

    @Test
    void schichtUeberMitternachtWirdAufBeideTageVerteilt() {
        StempelDaten.Eintrag e = new StempelDaten.Eintrag();
        long start = zeit(2026, 10, 28, 23, 50);
        long ende = zeit(2026, 10, 29, 0, 20);

        ZeitAuswertung.verbuche(e, start, ende);

        assertEquals(10 * 60_000L, e.tage.get(LocalDate.of(2026, 10, 28)));
        assertEquals(20 * 60_000L, e.tage.get(LocalDate.of(2026, 10, 29)));
        assertEquals(20 * 60_000L, ZeitAuswertung.heute(e, ende));
        assertEquals(30 * 60_000L, ZeitAuswertung.woche(e, ende));
    }

    @Test
    void kalenderwocheBeginntMontagTrotzZeitumstellung() {
        StempelDaten.Eintrag e = new StempelDaten.Eintrag();
        LocalDate sonntag = LocalDate.of(2026, 10, 25);
        assertEquals(25 * 60 * 60_000L,
                ZeitAuswertung.tagesbeginn(sonntag.plusDays(1)) - ZeitAuswertung.tagesbeginn(sonntag));

        long start = zeit(2026, 10, 25, 23, 50);
        long ende = zeit(2026, 10, 26, 0, 20);
        ZeitAuswertung.verbuche(e, start, ende);

        assertEquals(20 * 60_000L, ZeitAuswertung.woche(e, ende));
    }

    @Test
    void laufendeSchichtZaehltAbMitternachtUndNachAbschlussNichtDoppelt() {
        StempelDaten.Eintrag e = new StempelDaten.Eintrag();
        e.schichtStart = zeit(2026, 10, 28, 23, 50);
        long jetzt = zeit(2026, 10, 29, 0, 20);

        assertEquals(20 * 60_000L, ZeitAuswertung.heute(e, jetzt));

        ZeitAuswertung.verbuche(e, e.schichtStart, jetzt);
        e.schichtStart = -1;
        assertEquals(20 * 60_000L, ZeitAuswertung.heute(e, jetzt));
    }

    @Test
    void mehrereSchichtenAmSelbenTagWerdenAddiert() {
        StempelDaten.Eintrag e = new StempelDaten.Eintrag();
        ZeitAuswertung.verbuche(e, zeit(2026, 10, 28, 9, 0), zeit(2026, 10, 28, 10, 0));
        ZeitAuswertung.verbuche(e, zeit(2026, 10, 28, 12, 0), zeit(2026, 10, 28, 12, 30));

        assertEquals(90 * 60_000L, ZeitAuswertung.heute(e, zeit(2026, 10, 28, 13, 0)));
    }

    @Test
    void pathologischLangesIntervallWirdVorVerbuchungAbgelehnt() {
        StempelDaten.Eintrag e = new StempelDaten.Eintrag();

        assertThrows(IllegalStateException.class, () -> ZeitAuswertung.verbuche(e, 1, Long.MAX_VALUE));
        assertTrue(e.tage.isEmpty());
    }
}

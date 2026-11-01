package de.shyrshi.stempeluhr;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** Tages- und Wochenzeiten nach Kalendertagen in der deutschen Zeitzone. */
final class ZeitAuswertung {

    static final ZoneId ZEITZONE = ZoneId.of("Europe/Berlin");
    static final long MAX_SCHICHT_DAUER_MS = 366L * 86_400_000L;

    private ZeitAuswertung() {
    }

    static LocalDate datum(long zeitpunkt) {
        return Instant.ofEpochMilli(zeitpunkt).atZone(ZEITZONE).toLocalDate();
    }

    static long tagesbeginn(LocalDate tag) {
        return tag.atStartOfDay(ZEITZONE).toInstant().toEpochMilli();
    }

    /** Verteilt eine abgeschlossene Schicht auf die betroffenen Kalendertage. */
    static void verbuche(StempelDaten.Eintrag eintrag, long start, long ende) {
        pruefeIntervall(start, ende);
        long cursor = start;
        while (cursor < ende) {
            LocalDate tag = datum(cursor);
            long naechsterTag = tagesbeginn(tag.plusDays(1));
            long abschnittEnde = Math.min(ende, naechsterTag);
            long dauer = abschnittEnde - cursor;
            eintrag.tage.merge(tag, dauer, ZeitAuswertung::addiereOhneUeberlauf);
            cursor = abschnittEnde;
        }
    }

    /** Begrenzung vor der Tag-für-Tag-Verbuchung verhindert endlose Startphasen bei defekten Daten. */
    static void pruefeIntervall(long start, long ende) {
        if (ende <= start) return;
        if (start < 0 || ende < 0 || ende - start > MAX_SCHICHT_DAUER_MS) {
            throw new IllegalStateException("Ungültige Schichtdauer: " + start + " bis " + ende);
        }
    }

    static long heute(StempelDaten.Eintrag eintrag, long jetzt) {
        LocalDate tag = datum(jetzt);
        return addiereOhneUeberlauf(eintrag.tage.getOrDefault(tag, 0L),
                laufendSeit(eintrag, tagesbeginn(tag), jetzt));
    }

    static long woche(StempelDaten.Eintrag eintrag, long jetzt) {
        LocalDate heute = datum(jetzt);
        LocalDate montag = heute.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long dauer = 0;
        for (LocalDate tag = montag; !tag.isAfter(heute); tag = tag.plusDays(1)) {
            dauer = addiereOhneUeberlauf(dauer, eintrag.tage.getOrDefault(tag, 0L));
        }
        return addiereOhneUeberlauf(dauer, laufendSeit(eintrag, tagesbeginn(montag), jetzt));
    }

    private static long laufendSeit(StempelDaten.Eintrag eintrag, long beginn, long jetzt) {
        if (!eintrag.istEingestempelt()) return 0;
        return Math.max(0, jetzt - Math.max(eintrag.schichtStart, beginn));
    }

    private static long addiereOhneUeberlauf(long links, long rechts) {
        return Long.MAX_VALUE - links < rechts ? Long.MAX_VALUE : links + rechts;
    }
}

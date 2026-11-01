package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagesschichtBefehlTest {

    private static final long START = LocalDateTime.of(2026, 10, 28, 23, 50)
            .atZone(ZeitAuswertung.ZEITZONE).toInstant().toEpochMilli();
    private final AtomicLong jetzt = new AtomicLong(START);
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

    private void bewegeAlleVierMinuten(PlayerMock spieler, int minuten) {
        for (int vergangen = 0; vergangen < minuten; vergangen += 4) {
            jetzt.addAndGet(Math.min(4, minuten - vergangen) * 60_000L);
            Location von = spieler.getLocation();
            server.getPluginManager().callEvent(new PlayerMoveEvent(spieler, von, von.clone().add(1, 0, 0)));
        }
    }

    private static List<String> nachrichten(PlayerMock spieler) {
        List<String> texte = new ArrayList<>();
        Component nachricht;
        while ((nachricht = spieler.nextComponentMessage()) != null) {
            texte.add(PlainTextComponentSerializer.plainText().serialize(nachricht));
        }
        return texte;
    }

    @Test
    void zeigtNurDieZeitSeitMitternachtAuchNachAusstempeln() {
        PlayerMock spieler = server.addPlayer("Anna");
        nachrichten(spieler);
        bewegeAlleVierMinuten(spieler, 30);

        assertTrue(spieler.performCommand("tagesschicht"));
        assertTrue(nachrichten(spieler).stream().anyMatch(text -> text.contains("20 Min 0 Sek")));

        assertTrue(spieler.performCommand("ausstempeln"));
        nachrichten(spieler);
        assertTrue(spieler.performCommand("tagesschicht"));
        assertTrue(nachrichten(spieler).stream().anyMatch(text -> text.contains("20 Min 0 Sek")));
        assertEquals(30 * 60_000L, plugin.daten().get(spieler.getUniqueId()).gesamt);
    }
}

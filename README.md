# Stempeluhr

Paper-Plugin zur Arbeitszeiterfassung auf Minecraft-Servern. Spieler werden beim Betreten automatisch eingestempelt und beim Verlassen ausgestempelt. Die gesammelte Zeit lässt sich ansehen, in einer Rangliste vergleichen und von Admins anpassen.

## Befehle

### Für alle Spieler

| Befehl | Aliase | Beschreibung |
|---|---|---|
| `/einstempeln` | `/einstechen`, `/clockin` | Startet eine neue Schicht, falls du ausgestempelt bist. |
| `/ausstempeln` | `/ausstechen`, `/clockout` | Beendet die laufende Schicht und zeigt die gearbeitete Zeit. |
| `/schicht` | – | Zeigt die laufende Schicht, die letzte Schicht und deine Gesamtarbeitszeit. |
| `/tagesschicht` | – | Zeigt deine eigene Arbeitszeit heute seit 00:00 Uhr (Europe/Berlin), einschließlich der laufenden Schicht. |
| `/rangliste arbeitszeit tag` | – | Top 10 der heutigen Arbeitszeit. |
| `/rangliste arbeitszeit woche` | – | Top 10 der aktuellen Kalenderwoche ab Montag 00:00 Uhr. |
| `/arbeitszeit rangliste` | – | Top 10 nach Gesamtarbeitszeit (laufende Schichten zählen mit). Liegst du nicht in den Top 10, wird dein eigener Platz angehängt. |

### Für Admins

| Befehl | Beschreibung |
|---|---|
| `/arbeitszeit get <spieler>` | Zeigt die Gesamtarbeitszeit eines Spielers. |
| `/arbeitszeit set <spieler> <dd> <hh> <mm> <ss>` | Setzt die Gesamtarbeitszeit, z. B. `/arbeitszeit set Leon 01 08 30 00` = 1 Tag 8 Std 30 Min. |

Hinweise:
- Unterbefehle werden kleingeschrieben (`rangliste`, nicht `Rangliste`).
- `set` und `get` funktionieren auch in der Server-Konsole. Jede Änderung mit `set` wird im Server-Log festgehalten.
- Der Spieler muss online sein oder schon einmal auf dem Server gewesen sein.

## Berechtigungen

| Permission | Standard | Erlaubt |
|---|---|---|
| `stempeluhr.use` | alle Spieler | Ein-/Ausstempeln, `/schicht`, `/tagesschicht` und Ranglisten |
| `stempeluhr.admin` | nur OPs | `/arbeitszeit get` und `/arbeitszeit set` |

Mit LuckPerms z. B.: `/lp user Leon permission set stempeluhr.admin true`

## Verhalten

- **Join:** Bei frischer Bewegung automatisch eingestempelt, mit Anzeige der letzten Schicht. Nach einer abgelaufenen Bewegungsfrist beginnt die neue Schicht erst mit der nächsten Bewegung.
- **AFK:** Nach fünf Minuten ohne Positionsänderung endet die Schicht. Die erste erneute Bewegung startet automatisch eine neue Schicht; die Pause dazwischen zählt nicht. Kameradrehungen und Teleports zählen nicht als Bewegung. Ein-/Ausstempelbefehle und erneutes Verbinden setzen die Bewegungsfrist nicht zurück.
- **Quit / Server-Stopp:** automatisch ausgestempelt.
- **Serverabsturz:** Jede Minute wird ein Zwischenstand gespeichert. Nach einem Absturz endet die offene Schicht beim letzten Zwischenstand, es geht also höchstens ca. 1 Minute verloren.
- **Daten:** `plugins/Stempeluhr/daten.yml`
- **Tages- und Wochenzeiten:** Abgeschlossene Schichten werden beim Ausstempeln auf Berliner Kalendertage verteilt; laufende Schichten werden bei der Anzeige anteilig mitgezählt. Bereits vor dieser Version abgeschlossene Schichten enthalten keine Zeitstempel und können daher nicht nachträglich in Tag und Woche eingeordnet werden. `/arbeitszeit set` ändert nur die Gesamtzeit, nicht die Tageswerte.

## Installation

Benötigt Paper 26.3 oder neuer.

**Docker (`itzg/minecraft-server`):**

```yaml
environment:
  TYPE: PAPER
  PLUGINS: https://github.com/shyrshi061/stempeluhr/releases/latest/download/Stempeluhr.jar
```

**Manuell:** `Stempeluhr.jar` aus den [Releases](https://github.com/shyrshi061/stempeluhr/releases/latest) in den `plugins`-Ordner legen und den Server neu starten.

## Entwicklung

Benötigt Java 25 (Gradle lädt es bei Bedarf selbst).

| Befehl | Zweck |
|---|---|
| `.\gradlew.bat build` | Tests, SpotBugs und Jar bauen → `build/libs/` |
| `.\gradlew.bat test` | Nur Tests |
| `.\gradlew.bat test --tests '*StempeluhrTest$Rangliste'` | Einzelne Testgruppe |
| `.\gradlew.bat spotbugsMain` | Sicherheitsanalyse → `build/reports/spotbugs/main.html` |

Unter Linux/macOS `./gradlew` statt `.\gradlew.bat`.

**Lokal testen:**

```powershell
docker run -it --rm -p 25565:25565 -e EULA=TRUE -e TYPE=PAPER -e VERSION=LATEST `
  -e ONLINE_MODE=FALSE -e OPS=DeinName -v "${PWD}\build\libs:/plugins" itzg/minecraft-server
```

Dann im Client mit `localhost` verbinden.

**Neue Version veröffentlichen:**

1. In `build.gradle.kts` die `version` erhöhen.
2. Committen und pushen.
3. `git tag v1.1.0` und `git push origin v1.1.0`. GitHub Actions baut die Jar und hängt sie an das Release.

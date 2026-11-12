# Kamera – Reolink-Viewer für das Sonoff NSPanel Pro (Gen 1)

Schlanke Android-App (ab Android 8.0) für Reolink-Kameras und die **Reolink Video Doorbell** – entwickelt für die E1 Outdoor Pro, ohne Reolink-App und ohne Cloud.

- **Livebild** per RTSP (SD-Substream als Standard, HD/4K umschaltbar), Ton optional
- **Aufnahmen** von der SD-Karte nach Tag durchblättern und abspielen (mit Suchleiste und Vorschaubildern)
- **Bewegungserkennung**: Bei Bewegung (wahlweise nur Personen bzw. Personen + Fahrzeuge) schaltet sich das Display ein und das Livebild erscheint
- **Mehrere Kameras**: Kamera oben links wählen oder alle in der **Übersicht** (Raster) anzeigen
- **Türklingel**: Beim Läuten schaltet sich der Bildschirm ein, das Bild der Klingel erscheint und das Panel gongt
- **Scheinwerfer** ein-/ausschalten
- **Schwenken/Neigen/Zoom** über ein einblendbares Steuerkreuz
- **Sirene** der Kamera per Knopf auslösen (mit Sicherheitsabfrage, schaltet sich automatisch ab)
- Startet nach einem Neustart des Panels automatisch
- Für 480×480 optimiert, dunkles Design, Farben ohne Rot/Grün (Blau = OK, Gelb = Bewegung)

---

## 1. Kamera vorbereiten

In der Reolink-App oder im Reolink-Client unter *Einstellungen → Netzwerk → Erweitert → Server-Einstellungen*:

- **RTSP** einschalten (Port 554)
- **HTTPS** einschalten (Port 443, alternativ HTTP Port 80)

Am besten gibst du der Kamera im Router eine **feste IP-Adresse** (DHCP-Reservierung).

## 2. APK herunterladen

**Neueste Version:** <https://github.com/b05at/Reolink-NsPanel-App/releases/latest/download/Kamera.apk>

Alle Versionen findest du unter [Releases](https://github.com/b05at/Reolink-NsPanel-App/releases). Jede Änderung am Code wird von GitHub Actions automatisch gebaut und als neues Release veröffentlicht.

## 3. Auf dem NSPanel Pro installieren

Für das Panel muss ADB aktiviert sein. Eine Anleitung dazu findest du hier: <https://blakadder.com/nspanel-pro-sideload/>

```bash
adb connect <IP-des-Panels>:5555
adb install -r Kamera.apk
adb shell am start -n at.reolinkpanel.viewer/.MainActivity
```

Der Startbefehl ist nötig, falls der Panel-Launcher kein App-Symbol anzeigt.

## 4. Einrichten

Beim ersten Start öffnen sich die Einstellungen:

1. IP-Adresse, Benutzer und Passwort der Kamera eintragen.
2. **Verbindung testen** antippen. Es sollte „✓ Verbunden: E1 Outdoor Pro“ erscheinen.
3. Bewegungsmodus und Anzeigedauer wählen.
4. **Von Akku-Optimierung ausnehmen** antippen.
5. **Speichern**.

## Mehrere Kameras & Türklingel

Unter ⚙ → *Kameras & Türklingeln* beliebig viele Kameras anlegen. Pro Kamera einstellbar: Name, Zugang, ob sie eine **Türklingel** ist (wird beim Verbindungstest automatisch erkannt) und ob ihre Bewegung den Bildschirm einschaltet.

- **Übersicht** (Raster-Symbol oben links): alle Kameras gleichzeitig. Standardmäßig als Standbilder, die alle 2 s aktualisiert werden – das braucht kaum CPU. Optional Livevideo für bis zu 4 Kameras.
- Antippen einer Kachel öffnet das große Livebild dieser Kamera.
- Bei Bewegung wird die Kachel gelb umrandet. Läutet die Türklingel, wechselt die Anzeige sofort auf die Klingel und kehrt danach zur Übersicht zurück.
- Die Klingel-Erkennung nutzt den API-Befehl `GetEvents` (aktuelle Doorbell-Firmware erforderlich).

## Bedienung

- **Tippen aufs Bild** blendet die Bedienleiste ein: Aufnahmen · Ton · SD/HD · Ganz/Füllen · ⚙
- **Schwenken** (rechts oben): blendet das Steuerkreuz ein. Gedrückt halten = Kamera bewegt sich, loslassen = Stopp. Mit + / − zoomen.
- **Scheinwerfer** (rechts oben, Glühbirne): ein/aus. Leuchtet hell hinterlegt, solange er an ist.
- **Sirene** (rechts oben): Nach Bestätigung ertönt die Sirene. Solange sie läuft, ist der Knopf gelb und dauerhaft sichtbar, ein Tippen schaltet sie sofort ab. Spätestens nach der eingestellten Zeit (Standard 30 s) oder beim Verlassen des Livebilds geht sie automatisch aus.
- **Ganz/Füllen**: „Füllen“ schneidet das 16:9-Bild seitlich ab, sodass das quadratische Display voll ausgefüllt ist.
- Die Aufnahmen werden vor dem Abspielen kurz geladen. Mit SD geht das schnell.

## Vorschaubilder

Die Reolink-API liefert keine Vorschaubilder zu Aufnahmen. Die App speichert deshalb zu Beginn jeder Bewegung ein kleines Kamerabild (320×180) und ordnet es der passenden Aufnahme zu. Aufnahmen, die bereits abgespielt wurden, bekommen ein Bild aus dem Video. Aufnahmen von vor der Installation bzw. aus Zeiten, in denen das Panel aus war, zeigen ein Platzhalter-Symbol. Die Bilder werden 3 Wochen aufbewahrt.

## Bildschirm

Unter ⚙ → *Bildschirm*:

- **Bildschirm-Timeout**: Wie lange das Livebild ohne Berührung an bleibt (System-Standard, Nie, 15 s … 30 min). Danach wird das Display **ganz ausgeschaltet**.
- Dafür braucht die App einmalig die Erlaubnis als Geräteadministrator (Knopf „Display-Ausschalten erlauben“) oder per ADB:
  `adb shell dpm set-active-admin at.reolinkpanel.viewer/.AdminReceiver`
- **Einschalten**: Bewegung und Türklingel wie gewohnt, zusätzlich per **Handbewegung** vor dem Panel (Näherungssensor, abschaltbar).
- Bei ganz ausgeschaltetem Display reagiert der Touchscreen des NSPanel Pro nicht – ein Tipp weckt es daher nicht.

## Livebild lädt nicht?

Die App probiert automatisch mehrere RTSP-Varianten (`Preview_01_sub`, `h264Preview_01_sub`, `h265Preview_01_sub`, jeweils per TCP und UDP) und merkt sich die funktionierende. Alternativ unter ⚙ → Kamera → *Erweitert* die komplette RTSP-URL eintragen, die z. B. in einer anderen App funktioniert. Der Verbindungstest prüft jetzt auch RTSP (Livebild). Meldet er „RTSP ausgeschaltet“, in der Reolink-App unter *Einstellungen → Netzwerk → Erweitert → Server-Einstellungen* RTSP aktivieren. Bis das Livebild läuft, zeigt die App sekündlich aktualisierte Standbilder.

## Hinweise

- **Display aus:** Nach Ende der Bewegung und Ablauf der Anzeigedauer geht das Display gemäß dem System-Bildschirm-Timeout des Panels aus.
- **Reaktionszeit:** Die Kamera wird standardmäßig jede Sekunde abgefragt, das Display schaltet also ca. 1–2 s nach der Bewegung ein.
- **HD:** Das Panel kann 4K-H.265 nicht dekodieren. HD probiert zuerst den mittleren Stream und dann den Hauptstream. Klappt beides nicht, schaltet die App automatisch auf SD zurück. Abhilfe: in der Reolink-App den Hauptstream ("Klar") auf eine niedrigere Auflösung bzw. H.264 stellen.
- **Sicherheit:** Die App verbindet sich nur im lokalen Netz. Das selbstsignierte HTTPS-Zertifikat der Kamera wird akzeptiert.
- **Gegensprechen** ist nicht enthalten. Reolink nutzt dafür ein proprietäres Protokoll.

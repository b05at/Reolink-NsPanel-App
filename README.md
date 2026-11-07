# Kamera – Reolink-Viewer für das Sonoff NSPanel Pro (Gen 1)

Schlanke Android-App (ab Android 8.0) für die **Reolink E1 Outdoor Pro**, ohne Reolink-App und ohne Cloud.

- **Livebild** per RTSP (SD-Substream als Standard, HD/4K umschaltbar), Ton optional
- **Aufnahmen** von der SD-Karte nach Tag durchblättern und abspielen (mit Suchleiste und Vorschaubildern)
- **Bewegungserkennung**: Bei Bewegung (wahlweise nur Personen bzw. Personen + Fahrzeuge) schaltet sich das Display ein und das Livebild erscheint
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

## 2. APK bauen (kostenlos über GitHub, ohne Installation)

1. Auf github.com ein **privates Repository** anlegen.
2. Den Inhalt dieses Ordners hochladen (*Add file → Upload files*, alle Dateien und Ordner hineinziehen). Wichtig ist, dass auch der Ordner `.github/workflows` mit hochgeladen wird.
3. Den Reiter **Actions** öffnen. Der Build „APK bauen“ startet automatisch, sonst über *Run workflow* starten.
4. Nach ca. 3–5 Minuten unten unter **Artifacts** auf **Kamera-APK** klicken. Das ZIP enthält `app-debug.apk`.

Alternativ: Den Ordner in **Android Studio** öffnen und *Build → Build APK(s)* wählen.

## 3. Auf dem NSPanel Pro installieren

Für das Panel muss ADB aktiviert sein. Eine Anleitung dazu findest du hier: <https://blakadder.com/nspanel-pro-sideload/>

```bash
adb connect <IP-des-Panels>:5555
adb install app-debug.apk
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

## Bedienung

- **Tippen aufs Bild** blendet die Bedienleiste ein: Aufnahmen · Ton · SD/HD · Ganz/Füllen · ⚙
- **Schwenken** (rechts oben): blendet das Steuerkreuz ein. Gedrückt halten = Kamera bewegt sich, loslassen = Stopp. Mit + / − zoomen.
- **Sirene** (rechts oben): Nach Bestätigung ertönt die Sirene. Solange sie läuft, ist der Knopf gelb und dauerhaft sichtbar, ein Tippen schaltet sie sofort ab. Spätestens nach der eingestellten Zeit (Standard 30 s) oder beim Verlassen des Livebilds geht sie automatisch aus.
- **Ganz/Füllen**: „Füllen“ schneidet das 16:9-Bild seitlich ab, sodass das quadratische Display voll ausgefüllt ist.
- Die Aufnahmen werden vor dem Abspielen kurz geladen. Mit SD geht das schnell.

## Vorschaubilder

Die Reolink-API liefert keine Vorschaubilder zu Aufnahmen. Die App speichert deshalb zu Beginn jeder Bewegung ein kleines Kamerabild (320×180) und ordnet es der passenden Aufnahme zu. Aufnahmen, die bereits abgespielt wurden, bekommen ein Bild aus dem Video. Aufnahmen von vor der Installation bzw. aus Zeiten, in denen das Panel aus war, zeigen ein Platzhalter-Symbol. Die Bilder werden 3 Wochen aufbewahrt.

## Hinweise

- **Display aus:** Nach Ende der Bewegung und Ablauf der Anzeigedauer geht das Display gemäß dem System-Bildschirm-Timeout des Panels aus.
- **Reaktionszeit:** Die Kamera wird standardmäßig jede Sekunde abgefragt, das Display schaltet also ca. 1–2 s nach der Bewegung ein.
- **HD:** Das Panel kann 4K-H.265 nicht dekodieren. HD probiert zuerst den mittleren Stream und dann den Hauptstream. Klappt beides nicht, schaltet die App automatisch auf SD zurück. Abhilfe: in der Reolink-App den Hauptstream ("Klar") auf eine niedrigere Auflösung bzw. H.264 stellen.
- **Sicherheit:** Die App verbindet sich nur im lokalen Netz. Das selbstsignierte HTTPS-Zertifikat der Kamera wird akzeptiert.
- **Gegensprechen** ist nicht enthalten. Reolink nutzt dafür ein proprietäres Protokoll.

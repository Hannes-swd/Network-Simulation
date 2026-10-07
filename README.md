# Virtual Mashine

Fabric-Mod für Minecraft 26.1: ein platzierbarer **PC**-Block, in dem eine echte virtuelle Maschine läuft
(QEMU). ISO einlegen, RAM/CPU einstellen, Betriebssystem installieren und direkt in Minecraft benutzen.

## Voraussetzungen

- Fabric Loader 0.19.5+ und Fabric API für Minecraft 26.1
- **QEMU** (Open Source), z. B. unter Windows:
  `winget install SoftwareFreedomConservancy.QEMU`
  Die Mod findet QEMU automatisch (`C:\Program Files\qemu`, PATH, …). Liegt es woanders, trägt man den Ordner in
  `config/virtual_mashine.json` bei `qemuPath` ein.

## Benutzung

1. PC craften (Eisen außen, oben Glasscheibe, Mitte Redstone, unten Komparator) oder aus dem Kreativ-Tab
   „Endgeräte“ nehmen und platzieren.
2. Rechtsklick → Einstellungen: CD (ISO), RAM, CPU-Kerne, Festplattengröße, Bootreihenfolge, Sound, Internet.
3. ISO-Dateien in den ISO-Ordner legen (Knopf **ISO-Ordner öffnen**) oder einfach auf das Fenster ziehen.
4. **Starten** → der Bildschirm des PCs öffnet sich.

| Aktion | Taste |
|---|---|
| Maus & Tastatur an den PC geben | ins Bild klicken |
| wieder freigeben | **Links-Strg + Links-Alt** |
| zurück ins Spiel (PC läuft weiter) | ESC (wenn freigegeben) |
| Strg+Alt+Entf | Knopf in der Leiste |

Die Tastatur sendet echte Scancodes: Im Gast-Betriebssystem einfach das deutsche Tastaturlayout auswählen,
wie bei einem echten PC. Internet gibt es über ein Kabel zum Internet-Anschluss (siehe unten).

## Speichern & Weitermachen

Alles liegt im Weltordner unter `virtual_mashine/<PC-ID>/` (Festplatte `disk.qcow2`, `config.json`, `qemu.log`).
Jeder PC hat seine eigene Maschine; abgebaute PCs behalten ihre Festplatte, wenn man sie wieder platziert.

- **Beschleunigung an** (Standard, schnell, WHPX): Beim Verlassen der Welt fährt der PC sauber herunter
  (nach `shutdownTimeoutSeconds` Sekunden wird hart ausgeschaltet). WHPX kann keinen RAM-Zustand speichern.
- **Beschleunigung aus** (Emulation, langsamer): **Einfrieren** ist möglich – beim Verlassen der Welt oder per
  Knopf wird der komplette Zustand inkl. RAM gespeichert und mit **Fortsetzen** genau dort weitergemacht.

## Netzwerke bauen

Creative-Tabs: **Endgeräte** (PC, Laptop, Server), **Netzwerkgeräte** (Switch, Managed Switch, Router,
Access Point), **Internet** (Internet-Anschluss) und **Kabel & Werkzeug**.

| Gerät | Was es ist | Ports |
|---|---|---|
| PC | Rechner mit eigenem Betriebssystem (ISO) | eth0, COM1 |
| Laptop | wie ein PC, aber nur mit WLAN | wlan |
| Server | wie ein PC, startet automatisch mit der Welt | eth0, eth1, COM1 |
| Switch (einfach) | echter lernender Switch in der Mod, keine Einrichtung nötig | 8 |
| Managed Switch | VM für echte Switch-Software (z. B. OpenWrt per Knopfdruck, VyOS) | 8 + Console |
| Router | VM für echte Router-Software (OpenWrt per Knopfdruck, VyOS, OPNsense …) | eth0–eth3 + Console |
| Access Point | WLAN mit SSID und WPA2-Passwort, LAN-Port zum Switch/Router | LAN |
| Internet-Anschluss | echtes Internet mit DHCP und NAT (192.168.178.x) | LAN |

**Kabel:** Cat5e (1 Gbit/s), Cat6 (10 Gbit/s bis 55 m), Cat6a/Cat7 (10 Gbit/s), Glasfaser (10 km),
Konsolenkabel (COM1 ↔ Console). Kabel in die Hand, Gerät rechtsklicken, Port wählen, am zweiten Gerät
ebenfalls. Abziehen: Schleichen + Rechtsklick, belegten Port anklicken – das Betriebssystem sieht dabei ein
echtes „Kabel gezogen“. LEDs am Gerät: grün = Link, orange = Datenverkehr. Farbe: Schleichen + Rechtsklick
in die Luft. **Netzwerk-Tester:** zeigt Geschwindigkeit, MAC-Adresse und Datenverkehr jedes Ports.

**OpenWrt per Knopfdruck:** Bei einem Router oder Managed Switch mit leerer Festplatte „OpenWrt laden“
drücken, dann starten. Die Mod lädt die aktuelle OpenWrt-Version (Prüfsumme wird kontrolliert) und richtet
sie beim ersten Start über die Konsole ein:
- Router: eth1 = WAN (an den Internet-Anschluss), eth0/eth2/eth3 = LAN mit DHCP (192.168.1.x).
- Managed Switch: alle Ports in einer Bridge, holt sich seine Verwaltungs-IP per DHCP.

Danach ist es ein ganz normales OpenWrt: Weboberfläche LuCI (z. B. http://192.168.1.1 vom PC aus),
SSH oder Konsolenkabel. Eigene Festplatten-Images (.img, .img.gz, .qcow2, .vmdk, .vdi, .vhdx) legst du
in den ISO-Ordner und wählst sie unter „Image“.

**WLAN:** Access Point per Kabel an Switch/Router hängen, im Laptop-Menü „WLAN“ das Netz wählen und das
Passwort aus dem Access-Point-Menü eingeben. Entfernung und Wände (Stein stark, Holz mittel, Glas kaum)
bestimmen Signal und Geschwindigkeit. Hinweis: Das Betriebssystem im Laptop sieht die WLAN-Karte als normale
Netzwerkkarte; verbunden/getrennt und die Geschwindigkeit sind aber echt.

**Konsole:** Konsolenkabel von COM1 eines PCs an „Console“ eines Routers/Switches. Im PC z. B.
`screen /dev/ttyS0 115200` (Linux) bzw. PuTTY auf COM1 (Windows).

Neue Geräte stehen auf **„Netzwerk: Kabel“** – Internet gibt es also nur über eine Verbindung zum
Internet-Anschluss. „Direkt“ ist die Abkürzung ohne Kabel.

## Zusammen spielen (Essential, LAN, Server)

Treten Freunde der Welt bei (z. B. über Essential), können alle die PCs benutzen – auch denselben PC
gleichzeitig. Die virtuellen Maschinen laufen immer auf dem Computer des Hosts; Freunde bekommen Bild, Tastatur
und Maus komprimiert über die Minecraft-Verbindung. Alle Spieler brauchen die Mod. ISO-Dateien kann nur der Host
hinzufügen, eingelegt werden können sie von allen.

## Selbst bauen

Java 25 wird benötigt.

```
./gradlew build      # Mod-Datei liegt danach in build/libs/
./gradlew runClient  # Minecraft mit der Mod zum Testen starten
```

## Lizenz

Copyright (c) 2026 Hannes. Alle Rechte vorbehalten (siehe `LICENSE.txt`).
QEMU und OpenWrt sind eigenständige Open-Source-Projekte und werden nicht mitgeliefert.

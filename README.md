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
| Text aus der Zwischenablage eintippen | Knopf **Einfügen** (daneben DE/US = Tastatur im PC) |

Die Tastatur sendet echte Scancodes: Im Gast-Betriebssystem einfach das deutsche Tastaturlayout auswählen,
wie bei einem echten PC. Internet gibt es über ein Kabel zum Internet-Anschluss (siehe unten).

## Kopieren & Einfügen

- **Knopf „Einfügen“:** tippt den Text aus deiner Zwischenablage in den PC – geht mit jedem System, auch in
  Konsolen, im BIOS oder bei Tiny Core. Der Knopf daneben (DE/US) muss zum Tastaturlayout im PC passen.
- **Echtes Kopieren in beide Richtungen:** Läuft im PC `spice-vdagent` (bei Ubuntu, Debian, Fedora usw. mit
  Desktop meist schon installiert, sonst z. B. `sudo apt install spice-vdagent`), wird die Zwischenablage
  automatisch abgeglichen: im PC Strg+C, in Windows Strg+V und umgekehrt. Für Windows im PC gibt es die
  „spice-guest-tools“.

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
| Handy | Android (Item, kein Block) – nur WLAN, misst das Signal dort, wo du stehst | wlan |
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
Internet-Anschluss. „Direkt“ ist die Abkürzung ohne Kabel (ein unsichtbarer eigener Anschluss, gleich
abgesichert).

**Handy:** aus dem Tab „Endgeräte“ nehmen, Rechtsklick → **„Android laden“** (Android-x86 9, Open Source,
ca. 920 MB, Prüfsumme wird kontrolliert) → **Starten**. Android installiert sich beim ersten Start von selbst
(ein paar Minuten), danach startet das Handy neu und ist ein ganz normales Android: Apps als APK oder z. B.
über F-Droid installieren. Internet nur per WLAN: im Handy-Menü „WLAN“ das Netz eines Access Points wählen.
Das Signal hängt davon ab, wo du mit dem Handy stehst; liegt es in einer Kiste oder auf dem Boden, hat es
kein Netz.

## Automatisierung: I/O-Modul (Netzwerk)

Ein Block wie ein echtes Ethernet-I/O-Modul in einer Fabrik (Tab „Automatisierung“). Er hängt per Kabel am
Netzwerk, holt sich eine IP per DHCP (oder bekommt eine feste) und ist von **jedem** Betriebssystem aus
steuerbar – Linux, Windows, Android, eine SPS-Software wie OpenPLC oder ein eigenes Skript.

- **6 Kanäle, einer pro Seite:** 0 vorne, 1 hinten, 2 links, 3 rechts (von vorne gesehen), 4 oben, 5 unten.
- **Eingang:** liest die Redstone-Stärke (0–15), die **genau auf dieser Seite** ankommt.
- **Ausgang:** gibt die eingestellte Stärke **nur auf dieser Seite** aus (stark, wie ein Hebel) – z. B. Kolben.
- Rechtsklick: Anzeige mit Adresse, Kanälen und Netzwerkeinstellungen. Schleichen + Rechtsklick: Anschluss.

**Web und JSON (Port 80):** `http://<IP>/` im Browser (auch vom Handy), oder per Skript:

```
wget -qO- http://192.168.1.50/api                          # Status aller Kanäle als JSON
wget -qO- "http://192.168.1.50/mode?ch=front&mode=output"  # Kanal vorne als Ausgang
wget -qO- "http://192.168.1.50/set?ch=front&level=15"      # Kolben vorne ausfahren (0 = einfahren)
```

**Modbus TCP (Port 502)** – der Industriestandard, Unit-ID egal:

| Bereich | Adressen | Bedeutung |
|---|---|---|
| Coils (FC 1, 5, 15) | 0–5 | Ausgang an (15) / aus (0) |
| Discrete Inputs (FC 2) | 0–5 | Signal auf der Seite vorhanden |
| Input Register (FC 4) | 0–5 | Pegel auf der Seite (0–15) |
| Holding Register (FC 3, 6, 16) | 0–5 | Ausgangspegel (0–15) |
| Holding Register (FC 3, 6, 16) | 100–105 | Modus: 0 = Eingang, 1 = Ausgang |

Schreiben auf einen Eingang gibt die Modbus-Ausnahme 4 zurück. Beispiel mit Python (`pip install pymodbus`):

```python
from pymodbus.client import ModbusTcpClient
plc = ModbusTcpClient("192.168.1.50"); plc.connect()
plc.write_register(100, 1)                       # vorne = Ausgang
plc.write_register(0, 15)                        # vorne einschalten
print(plc.read_input_registers(0, count=6).registers)   # Pegel aller Seiten
```

Eine kleine „Fabriksteuerung“ als Shell-Skript (läuft z. B. in Tiny Core): Kommt links Redstone an, fährt der
Kolben vorne aus.

```
while true; do
  if wget -qO- http://192.168.1.50/api | grep -q '"left","mode":"input","level":[1-9]'; then L=15; else L=0; fi
  wget -qO- "http://192.168.1.50/set?ch=front&level=$L" > /dev/null
  sleep 1
done
```

## Display (Netzwerk)

Ein Bildschirm-Block mit eigenem kleinem System (Debian + Firefox im Vollbild): Kabel zum Switch, eine
Adresse eintragen, und er zeigt die Seite dauerhaft direkt in der Welt – z. B. eine Karte deiner Basis mit
Knöpfen für Türen (über das I/O-Modul). Größe einstellbar bis 8 x 6 Blöcke (wächst nach rechts und oben).

- **Bedienen:** draufschauen – der Mauszeiger folgt dem Fadenkreuz (hover); Links- oder Rechtsklick klickt,
  gedrückt halten zieht, Mausrad scrollt.
- **Schleichen** gibt die Maus an Minecraft zurück: Schleichen + Rechtsklick = Einstellungen
  (Seite, Größe, Ein/Aus), Schleichen + Linksklick = abbauen, Kabel einstecken mit Kabel in der Hand.
- Das System kommt aus `netz-display.qcow2` im ISO-Ordner; die Seite wird beim Start übergeben.

## Sicherheit (z. B. zum Testen von Viren)

- Jede VM läuft in QEMU und sieht nur ihre eigene virtuelle Festplatte – keine Ordner deines PCs.
- **Internet-Filter:** Was eine VM ins Internet schickt, geht durch einen Filter im Internet-Anschluss. Erlaubt
  sind nur DHCP, DNS und **öffentliche** Internet-Adressen. Gesperrt sind dein echter PC (auch Dienste, die nur
  lokal auf `127.0.0.1` laufen, und die Steuerung der anderen VMs) und dein Heimnetz (Router, NAS, andere
  Geräte: 10.x, 172.16–31.x, 192.168.x usw.).
- **Zwischenablage:** pro PC abschaltbar („Kopieren: Aus“). Wenn an, bekommt der PC deinen kopierten Text nur
  in dem Moment, in dem du in seinen Bildschirm klickst.
- Grenzen: Die VM kann ins Internet (wie ein echter infizierter PC hinter einem Router). Gegen Fehler in QEMU
  selbst („VM-Ausbruch“) hilft nur, QEMU aktuell zu halten (`winget upgrade SoftwareFreedomConservancy.QEMU`).
  Für Viren-Tests: Kopieren aus, Netzwerk aus oder nur Kabel ohne Internet-Anschluss, Einfrieren statt Festplatte
  löschen, wenn du zurück willst.

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

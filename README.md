# Virtual Mashine

**Virtual Mashine** ist eine Fabric-Mod für Minecraft 26.1, die echte virtuelle Computer und Netzwerke
in die Spielwelt bringt. PCs, Server, Router, Switches, WLAN, Displays und Netzwerk-I/O lassen sich
verbinden und bedienen. Die Computer laufen mit **QEMU** und starten echte Betriebssysteme statt einer
Minecraft-Simulation davon.

## Betriebssysteme und Plattformen

Host und Gast sind zwei verschiedene Dinge: Das **Hostsystem** ist der Computer, auf dem Minecraft und
QEMU laufen. Das **Gastsystem** ist das Betriebssystem innerhalb einer virtuellen Maschine.

| System | Verwendung |
|---|---|
| Windows, Linux oder macOS | Hostsysteme: Die Mod sucht QEMU an den üblichen Installationsorten und im `PATH`. QEMU muss `qemu-system-x86_64` enthalten. |
| x86-64-Betriebssysteme, die unter QEMU laufen | Eigene VM-Gäste: ISO-Datei einlegen oder ein unterstütztes Festplatten-Image verwenden. Ob ein System funktioniert, hängt auch von dessen QEMU- und Hardware-Anforderungen ab. |
| Android-x86 9.0-r2 | Vorgefertigtes Handysystem. Die Mod kann das ISO herunterladen, die Prüfsumme prüfen und Android auf der virtuellen Festplatte installieren. |
| OpenWrt x86-64 | Vorgefertigtes Router-/Switch-System. Die Mod lädt das aktuelle stabile Image samt SHA-256-Prüfung und kann es für Router oder Managed Switches einrichten. |
| Debian mit Firefox | Vorgesehenes Kiosk-System für Netzwerk-Displays. Das Festplatten-Image `netz-display.qcow2` muss im ISO-Ordner vorhanden sein; es wird nicht mit der Mod ausgeliefert. |
| Debian oder Ubuntu | Für den optionalen Linux-Treiber der virtuellen Kamera gibt es ein Installationsskript unter [`guest/netz-media`](guest/netz-media). |

Die Mod schränkt eigene Gäste nicht auf diese Beispiele ein: Sie können grundsätzlich andere x86-64-Systeme
verwenden, sofern sie mit der von QEMU bereitgestellten virtuellen Hardware zurechtkommen. Die genannten
vorgefertigten Systeme und Funktionen sind keine Zusage, dass jede Version oder jedes beliebige ISO getestet
wurde.

## Voraussetzungen

- Minecraft **26.1** mit **Fabric Loader 0.19.5 oder neuer** und Fabric API
  (Projektversion **0.155.3+26.1.2**)
- **Java 25** für Minecraft bzw. zum Bauen des Projekts
- **QEMU** inklusive `qemu-system-x86_64` und `qemu-img` auf dem Hostsystem
- Genügend Arbeitsspeicher und CPU-Leistung für Minecraft und die gestarteten VMs. RAM und CPU-Kerne der
  Gäste werden in den VM-Einstellungen vergeben.

Unter Windows kann QEMU z. B. mit `winget install SoftwareFreedomConservancy.QEMU` installiert werden.
Die Mod sucht QEMU automatisch (unter Windows z. B. in `C:\Program Files\qemu`, außerdem im `PATH`).
Liegt QEMU an einem anderen Ort, kann der Pfad in `config/virtual_mashine.json` unter `qemuPath`
eingetragen werden. Hardwarebeschleunigung nutzt die auf dem Host verfügbare QEMU-Technik (z. B. WHPX
unter Windows, KVM unter Linux oder HVF unter macOS); ohne Beschleunigung kann QEMU in langsamer Emulation
laufen.

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
ISOs und weitere Festplatten-Images können im Weltordner unter `virtual_mashine/isos/` oder im gemeinsamen
Ordner `vm_isos/` neben dem Minecraft-Spiel liegen. Unterstützte Image-Endungen sind `.qcow2`, `.img`,
`.img.gz`, `.raw`, `.vmdk`, `.vdi`, `.vhd` und `.vhdx`; Installationsmedien werden als `.iso` eingebunden.

- **Beschleunigung an** (Standard, schnell): QEMU nutzt, sofern verfügbar, die Hardwarebeschleunigung des
  Hostsystems. Beim Verlassen der Welt fährt der PC sauber herunter (nach `shutdownTimeoutSeconds` Sekunden
  wird er nötigenfalls hart ausgeschaltet).
- **Beschleunigung aus** (Emulation, langsamer): **Einfrieren** ist möglich – beim Verlassen der Welt oder
  per Knopf wird der komplette Zustand inklusive RAM gespeichert und mit **Fortsetzen** genau dort weitergemacht.
  Auch Linux mit KVM kann eingefroren werden. WHPX unter Windows kann keinen RAM-Zustand speichern; bei
  aktivierter Beschleunigung ist Einfrieren dort nicht verfügbar.

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
SSH oder Konsolenkabel. Eigene Festplatten-Images (.qcow2, .img, .img.gz, .raw, .vmdk, .vdi, .vhd, .vhdx) legst du
in den ISO-Ordner und wählst sie unter „Image“.

**WLAN:** Access Point per Kabel an Switch/Router hängen, im Laptop-Menü „WLAN“ das Netz wählen und das
Passwort aus dem Access-Point-Menü eingeben. Entfernung und Wände (Stein stark, Holz mittel, Glas kaum)
bestimmen Signal und Geschwindigkeit. Hinweis: Das Betriebssystem im Laptop sieht die WLAN-Karte als normale
Netzwerkkarte; verbunden/getrennt und die Geschwindigkeit sind aber echt.

**Konsole:** Konsolenkabel von COM1 eines PCs an „Console“ eines Routers/Switches. Im PC z. B.
`screen /dev/ttyS0 115200` (Linux) bzw. PuTTY auf COM1 (Windows).

Neue Geräte stehen auf **„Netzwerk: Kabel“** – Internet gibt es über eine Verbindung zum
Internet-Anschluss. **„Direkt“** verwendet QEMUs eingebautes NAT und kommt ohne Kabel aus; **„Aus“**
deaktiviert die Netzwerkkarte. Für kontrollierte, in der Welt verkabelte Netzwerke wähle „Kabel“.

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

## Monitore über HDMI (mehrere Bildschirme)

Der Monitor-Block (früher „Display“) zeigt über ein **HDMI-Kabel** das Bild eines PCs, Laptops oder Servers –
direkt in der Welt, so groß, wie du ihn einstellst (bis 8 x 6 Blöcke). Jeder Computer hat dafür eine
Grafikkarte mit 4 Bildschirmen; im Betriebssystem sind das ganz normale Monitore.

- **Anschließen:** HDMI-Kabel in die Hand, Schleichen + Rechtsklick auf den PC → `HDMI 1` (bzw. `HDMI 2`),
  dann Schleichen + Rechtsklick auf den Monitor → `HDMI IN`. PC: 2 HDMI-Buchsen, Laptop und Server: 1
  (beim Laptop ist der eingebaute Bildschirm Nummer 1, der HDMI-Monitor ist der zweite).
- **HDMI-Switch:** `IN` an den Computer, bis zu 4 Monitore an `OUT 1–4`. Rechtsklick schaltet um:
  **Erweitern** (jeder Ausgang ist ein eigener Bildschirm) oder **Duplizieren** (überall dasselbe Bild).
  Switches lassen sich hintereinanderstecken; mehr als 4 Bildschirme pro Computer gibt es nicht.
- **Erweitern oder spiegeln im Betriebssystem:** Ubuntu stellt neue Monitore automatisch daneben
  (Einstellungen → Anzeigen → „Verbinden“ / „Spiegeln“, Anordnung per Ziehen). Die Auflösung kommt aus der
  Größe des Monitors (z. B. 2 x 1 Blöcke = 1920 x 960).
- **Von außen bedienen:** draufschauen – der Mauszeiger folgt dem Fadenkreuz, auch über mehrere Monitore
  desselben PCs hinweg. Ein Klick gibt dem PC zusätzlich die **Tastatur** (du bleibst stehen, umsehen bewegt
  die Maus); **Strg links + Alt links** gibt Tastatur und Maus an Minecraft zurück.
- Das Fenster des PCs (Rechtsklick auf den PC) zeigt Bildschirm 1; der Knopf „Bildschirm“ wechselt zu den
  anderen.
- Damit die Maus auf dem 2., 3., 4. Bildschirm genau trifft, braucht das Betriebssystem `spice-vdagent`
  (bei Ubuntu-, Debian- und Fedora-Desktops schon dabei).
- Die neue Grafikkarte bekommt ein PC beim nächsten **Neustart** (nicht beim Fortsetzen nach Einfrieren).
  Für Systeme, die nur die alte Karte kennen: Steuerung → „Bildschirme: VGA“ (dann zeigen alle Monitore
  dasselbe Bild).

**USB-Hub:** `Host` per USB-Kabel an den Computer (oder an einen anderen Hub), an `USB 1–4` kommen
Mikrofone, Kameras oder weitere Hubs.

## Display (Netzwerk)

Ohne HDMI-Kabel kann der Monitor wie bisher ein eigenes kleines System (Debian + Firefox im Vollbild)
betreiben: Kabel zum Switch, eine
Adresse eintragen, und er zeigt die Seite dauerhaft direkt in der Welt – z. B. eine Karte deiner Basis mit
Knöpfen für Türen (über das I/O-Modul). Größe einstellbar bis 8 x 6 Blöcke (wächst nach rechts und oben).

- **Bedienen:** draufschauen – der Mauszeiger folgt dem Fadenkreuz (hover); Links- oder Rechtsklick klickt,
  gedrückt halten zieht, Mausrad scrollt.
- **Schleichen** gibt die Maus an Minecraft zurück: Schleichen + Rechtsklick = Einstellungen
  (Seite, Größe, Ein/Aus), Schleichen + Linksklick = abbauen, Kabel einstecken mit Kabel in der Hand.
- Das System kommt aus `netz-display.qcow2` im ISO-Ordner; die Seite wird beim Start übergeben.

## Ton, Mikrofon und Kamera

- **Ton:** PCs, Laptops, Displays (und das Handy in deiner Tasche) spielen ihren Ton im Spiel ab – dort, wo sie
  stehen, bis 24 Blöcke weit, je näher, desto lauter. Hast du den Bildschirm eines Geräts offen, hörst du ihn
  direkt in Stereo. Lautstärke: Minecraft-Einstellung „Plattenspieler/Notenblöcke“. Funktioniert mit jedem
  Betriebssystem in der VM; auch Mitspieler hören die Geräte in ihrer Nähe.
- **Mikrofon (USB):** Block aufstellen, mit dem **USB-Kabel** an einen PC, Laptop, Server oder ein Display
  stecken (Schleichen + Rechtsklick öffnet die Buchsen). Was Spieler in seiner Nähe (bis 8 Blöcke) sagen, kommt im
  Computer als Mikrofon an (Line-In der Soundkarte, jedes Betriebssystem). Oben links steht, solange dein
  Mikrofon aufnimmt. Rechtsklick auf den Block schaltet weiter: **Stimmen** → **Stimmen + Spielgeräusche** →
  **nur Spielgeräusche** → aus. Spielgeräusche sind alles, was in der Welt rund ums Mikrofon zu hören ist
  (Schritte, Tiere, Plattenspieler …, leiser mit Abstand); der Ton der Computer selbst gehört nicht dazu.
  Mehrere Mikrofone an einem Computer (z. B. über einen USB-Hub) landen gemischt im selben Eingang. Ganz abschalten: `"allowMicrophone": false` in
  `config/virtual_mashine.json`.
- **Kamera (USB):** genauso einstecken. Sie filmt die Minecraft-Welt vor ihrem Objektiv (auch dich) und gibt das Bild
  als Webcam in den Computer – z. B. für Discord im Browser. Gefilmt wird vom Spiel eines Spielers in der Nähe.
  In Linux braucht es dafür den kleinen Treiber aus [`guest/netz-media`](guest/netz-media): Ordner in die VM bringen,
  `sudo sh install.sh`, neu starten. Dann gibt es die Webcam „Minecraft-Kamera“ (`/dev/video10`).
  Die fertigen Systeme `netz-client.qcow2` und `netz-display.qcow2` haben ihn schon.
- PCs, die von einer älteren Version eingefroren wurden, bekommen Mikrofon-Eingang und Kamera-Anschluss erst nach
  einem Neustart (neue Hardware).

## Sicherheit (z. B. zum Testen von Viren)

- Jede VM läuft als eigener QEMU-Prozess. Es ist keine gemeinsame Host-Ordnerfreigabe eingerichtet; die VM
  bekommt nur die für sie ausgewählten virtuellen Laufwerke und Installationsmedien.
- **Internet-Anschluss in der Welt:** Der Modem-Filter lässt DHCP, DNS und **öffentliche** Internet-Adressen
  passieren. Gesperrt sind dein echter PC (auch Dienste, die nur lokal auf `127.0.0.1` laufen, und die
  Steuerung der anderen VMs) und private Adressbereiche wie Heimnetz, Router und NAS
  (`10.x`, `172.16–31.x`, `192.168.x` usw.).
- **„Direkt“-Netzwerk:** Nutzt QEMUs eingebautes NAT statt des Modem-Blocks und damit nicht dessen
  In-Game-Filter. Für Tests ohne direkten Internetzugang die Netzwerkkarte auf „Aus“ stellen oder die VM
  nur an ein isoliertes Kabelnetz ohne Internet-Anschluss anschließen.
- **Zwischenablage:** pro PC abschaltbar („Kopieren: Aus“). Wenn an, bekommt der PC deinen kopierten Text nur
  in dem Moment, in dem du in seinen Bildschirm klickst.
- Grenzen: Die VM kann ins Internet (wie ein echter infizierter PC hinter einem Router). Gegen Fehler in QEMU
  selbst („VM-Ausbruch“) hilft nur, QEMU aktuell zu halten (`winget upgrade SoftwareFreedomConservancy.QEMU`).
  Für Viren-Tests: Kopieren aus, Netzwerk aus oder ein isoliertes Kabelnetz verwenden. Einfrieren statt
  Festplatte löschen, wenn du zu einem gespeicherten Zustand zurückwillst.

## Zusammen spielen (Essential, LAN, Server)

Treten Freunde der Welt bei (z. B. über Essential), können alle die PCs benutzen – auch denselben PC
gleichzeitig. Die virtuellen Maschinen laufen immer auf dem Computer des Hosts; Freunde bekommen Bild, Tastatur
und Maus komprimiert über die Minecraft-Verbindung. Alle Spieler brauchen die Mod. ISO-Dateien kann nur der Host
hinzufügen, eingelegt werden können sie von allen.

## Selbst bauen

Das Projekt verwendet Java 25, Gradle mit Fabric Loom, Minecraft 26.1 und Fabric API. QEMU führt die
x86-64-Gäste aus; die Mod steuert QEMU über QMP und zeigt den VM-Bildschirm über VNC/RFB an. Das virtuelle
Netzwerk verbindet die Mod-Geräte per Ethernet; zusätzlich stellt das I/O-Modul HTTP/JSON und Modbus TCP
bereit. In Mehrspielerwelten werden Bild und Eingaben über die Minecraft-Verbindung zwischen Host und
Mitspielern übertragen. QEMU und Gastbetriebssysteme sind externe Komponenten und werden nicht mit dem
Mod-Build ausgeliefert.

```
./gradlew build      # Mod-Datei liegt danach in build/libs/
./gradlew runClient  # Minecraft mit der Mod zum Testen starten
```

Unter Windows geht es mit `.\gradlew.bat build` bzw. `.\gradlew.bat runClient`.

## Lizenz

Copyright (c) 2026 Hannes. Alle Rechte vorbehalten (siehe `LICENSE.txt`).
QEMU und OpenWrt sind eigenständige Open-Source-Projekte und werden nicht mitgeliefert.

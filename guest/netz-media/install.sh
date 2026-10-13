#!/bin/sh
# Camera and microphone of the Minecraft mod "Virtual Mashine" for Debian / Ubuntu guests.
#
#   sudo sh install.sh            (agent.py next to this script; needs internet for the packages)
#
# - The camera block becomes a webcam: v4l2loopback provides /dev/video10 ("Minecraft-Kamera"),
#   the netz-media agent fills it with the pictures the game sends.
# - The microphone block needs nothing here: it arrives as the sound card's line-in. This only makes
#   sure the sound system is there (PulseAudio) for systems without a desktop (e.g. a display kiosk).
# - Firefox may use camera and microphone on pages of the local network (http), which it normally
#   only allows on https pages.
# If a newer kernel came along with the driver, restart the computer afterwards.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
export DEBIAN_FRONTEND=noninteractive

apt-get update
# The driver is built against the kernel headers: Debian and Ubuntu name those packages differently.
# (apt-cache show also succeeds for names other packages only mention, so ask for a real candidate.)
available() {
    LC_ALL=C apt-cache policy "$1" 2>/dev/null | grep -q "Candidate: [0-9]"
}
if available linux-headers-amd64; then
    KERNEL="linux-image-amd64 linux-headers-amd64"
elif available "linux-headers-$(uname -r)"; then
    KERNEL="linux-headers-$(uname -r) linux-headers-generic"
else
    KERNEL="linux-image-generic linux-headers-generic"
fi
apt-get install -y --no-install-recommends $KERNEL dkms v4l2loopback-dkms python3
if ! command -v pulseaudio >/dev/null 2>&1 && ! command -v pipewire >/dev/null 2>&1; then
    apt-get install -y pulseaudio
fi

install -d /usr/local/lib/netz-media
install -m 755 "$HERE/agent.py" /usr/local/lib/netz-media/agent.py
echo 'options v4l2loopback devices=1 video_nr=10 card_label="Minecraft-Kamera" exclusive_caps=1' > /etc/modprobe.d/netz-kamera.conf
echo v4l2loopback > /etc/modules-load.d/netz-kamera.conf

cat > /etc/systemd/system/netz-media.service <<'EOF'
[Unit]
Description=Minecraft-Kamera (Bilder der Kamera aus dem Spiel)
After=systemd-modules-load.service

[Service]
ExecStartPre=-/sbin/modprobe v4l2loopback
ExecStart=/usr/bin/python3 /usr/local/lib/netz-media/agent.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl enable netz-media.service

# Firefox: camera and microphone also for http pages in the local network.
for dir in /etc/firefox-esr/policies /etc/firefox/policies; do
    if [ "$dir" = /etc/firefox/policies ] && ! command -v firefox >/dev/null 2>&1; then
        continue
    fi
    if [ "$dir" = /etc/firefox-esr/policies ] && ! command -v firefox-esr >/dev/null 2>&1; then
        continue
    fi
    mkdir -p "$dir"
    python3 - "$dir/policies.json" <<'EOF'
import json, os, sys
path = sys.argv[1]
data = json.load(open(path)) if os.path.exists(path) else {}
policies = data.setdefault("policies", {})
prefs = policies.setdefault("Preferences", {})
for name in ("media.devices.insecure.enabled", "media.getusermedia.insecure.enabled"):
    prefs[name] = {"Value": True, "Status": "default"}
permissions = policies.setdefault("Permissions", {})
for kind in ("Camera", "Microphone"):
    allow = permissions.setdefault(kind, {}).setdefault("Allow", [])
    for origin in ("http://server.netz.lan", "https://discord.com"):
        if origin not in allow:
            allow.append(origin)
json.dump(data, open(path, "w"), indent=2)
EOF
done
apt-get clean
# Start it right away when the driver fits the running kernel (else after the restart).
if modprobe v4l2loopback 2>/dev/null && systemctl restart netz-media.service 2>/dev/null; then
    echo "netz-media installiert - die Minecraft-Kamera ist da."
else
    echo "netz-media installiert - bitte neu starten."
fi

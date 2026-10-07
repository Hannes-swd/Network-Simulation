package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * First boot of a freshly downloaded OpenWrt: types a few UCI commands on its serial console so a
 * router has all four ports in use and a managed switch behaves like a switch. Afterwards the
 * device is configured like any real OpenWrt box (LuCI web interface, SSH, console).
 */
final class OpenWrtProvisioner implements Consumer<byte[]> {
    static final String ROUTER = "router";
    static final String SWITCH = "switch";

    /** Printed by the shell when every command ran; the typed command itself does not contain it. */
    private static final String DONE_MARKER = "VMPROV2DONE";

    /** Generic x86 OpenWrt only sets up LAN on eth0; a router needs its WAN on eth1 as well. */
    private static final String ROUTER_SCRIPT = String.join("; ",
            "uci add_list network.@device[0].ports=eth2",
            "uci add_list network.@device[0].ports=eth3",
            "uci set network.wan=interface",
            "uci set network.wan.device=eth1",
            "uci set network.wan.proto=dhcp",
            "uci set network.wan6=interface",
            "uci set network.wan6.device=eth1",
            "uci set network.wan6.proto=dhcpv6",
            "uci commit network",
            "/etc/init.d/network reload",
            "echo VMPROV$((1+1))DONE") + "\r";

    private static final String SWITCH_SCRIPT = String.join("; ",
            "uci delete network.@device[0].ports",
            "for p in eth0 eth1 eth2 eth3 eth4 eth5 eth6 eth7; do uci add_list network.@device[0].ports=$p; done",
            "uci -q delete network.wan",
            "uci -q delete network.wan6",
            "uci -q delete network.lan.ipaddr",
            "uci -q delete network.lan.netmask",
            "uci -q delete network.lan.ip6assign",
            "uci set network.lan.proto=dhcp",
            "uci set dhcp.lan.ignore=1",
            "uci commit",
            "/etc/init.d/network restart",
            "/etc/init.d/dnsmasq restart",
            "echo VMPROV$((1+1))DONE") + "\r";

    private enum Stage { BOOTING, WAITING_FOR_SHELL, RUNNING, DONE }

    private final SerialLine serial;
    private final String mode;
    private final Runnable onDone;
    private final StringBuilder recent = new StringBuilder();
    private final long started = System.currentTimeMillis();
    private volatile Stage stage = Stage.BOOTING;
    private long lastNudge;

    private OpenWrtProvisioner(SerialLine serial, String mode, Runnable onDone) {
        this.serial = serial;
        this.mode = mode;
        this.onDone = onDone;
    }

    static void attach(SerialLine serial, String mode, Runnable onDone) {
        OpenWrtProvisioner provisioner = new OpenWrtProvisioner(serial, mode, onDone);
        serial.addTap(provisioner);
        Thread nudger = new Thread(provisioner::nudgeLoop, "virtual_mashine-openwrt-setup");
        nudger.setDaemon(true);
        nudger.start();
    }

    @Override
    public synchronized void accept(byte[] chunk) {
        recent.append(new String(chunk, StandardCharsets.ISO_8859_1));
        if (recent.length() > 8192) {
            recent.delete(0, recent.length() - 4096);
        }
        String text = recent.toString();
        switch (stage) {
            case BOOTING -> {
                if (text.contains("Please press Enter to activate this console")) {
                    stage = Stage.WAITING_FOR_SHELL;
                    recent.setLength(0);
                    send("\r");
                }
            }
            case WAITING_FOR_SHELL -> {
                if (text.contains("root@OpenWrt") || text.contains("# ")) {
                    stage = Stage.RUNNING;
                    recent.setLength(0);
                    Virtual_mashine.LOGGER.info("Setting up OpenWrt as {}", mode);
                    send(SWITCH.equals(mode) ? SWITCH_SCRIPT : ROUTER_SCRIPT);
                }
            }
            case RUNNING -> {
                if (text.contains(DONE_MARKER)) {
                    stage = Stage.DONE;
                    serial.removeTap(this);
                    onDone.run();
                }
            }
            default -> {
            }
        }
    }

    /** If the "press Enter" line came before we were listening, keep pressing Enter now and then. */
    private void nudgeLoop() {
        while (stage != Stage.DONE && System.currentTimeMillis() - started < 10 * 60 * 1000) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (stage == Stage.BOOTING && now - started > 60_000 && now - lastNudge > 15_000) {
                    lastNudge = now;
                    stage = Stage.WAITING_FOR_SHELL;
                    send("\r");
                } else if (stage == Stage.WAITING_FOR_SHELL && now - lastNudge > 15_000) {
                    lastNudge = now;
                    send("\r");
                }
            }
        }
        serial.removeTap(this);
    }

    private void send(String text) {
        serial.write(text.getBytes(StandardCharsets.US_ASCII));
    }
}

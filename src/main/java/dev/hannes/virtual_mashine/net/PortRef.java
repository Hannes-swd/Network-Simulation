package dev.hannes.virtual_mashine.net;

import java.util.UUID;

/** One socket of one device. */
public record PortRef(UUID device, int port) {
}

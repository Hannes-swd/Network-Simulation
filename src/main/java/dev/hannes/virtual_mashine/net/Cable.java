package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.device.DeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;

/** A patch cable between two ports. Plain fields so it can be stored as JSON. */
public final class Cable {
    public UUID id;
    public String type;
    public int color;
    public End a;
    public End b;

    public static final class End {
        public UUID device;
        public int port;
        public String deviceType;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public String facing;

        public static End of(PortRef ref, DeviceType deviceType, String dimension, BlockPos pos, Direction facing) {
            End end = new End();
            end.device = ref.device();
            end.port = ref.port();
            end.deviceType = deviceType.id;
            end.dimension = dimension;
            end.x = pos.getX();
            end.y = pos.getY();
            end.z = pos.getZ();
            end.facing = facing.getSerializedName();
            return end;
        }

        public PortRef ref() {
            return new PortRef(device, port);
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public DeviceType type() {
            return DeviceType.byId(deviceType);
        }

        public Direction direction() {
            Direction parsed = Direction.byName(facing);
            return parsed == null || parsed.getAxis().isVertical() ? Direction.NORTH : parsed;
        }
    }

    public CableType cableType() {
        return CableType.byId(type);
    }

    public boolean touches(UUID device) {
        return a.device.equals(device) || b.device.equals(device);
    }

    public boolean uses(PortRef port) {
        return a.ref().equals(port) || b.ref().equals(port);
    }

    public End endAt(PortRef port) {
        return a.ref().equals(port) ? a : b;
    }

    public End otherEnd(PortRef port) {
        return a.ref().equals(port) ? b : a;
    }

    /** Straight distance between the two sockets in metres. */
    public double length() {
        return DeviceGeometry.socket(a).distanceTo(DeviceGeometry.socket(b));
    }
}

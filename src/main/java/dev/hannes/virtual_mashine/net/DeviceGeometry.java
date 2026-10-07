package dev.hannes.virtual_mashine.net;

import dev.hannes.virtual_mashine.device.DeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Where on a device's housing each socket sits, so cables end exactly at their port. */
public final class DeviceGeometry {
    private DeviceGeometry() {
    }

    public static Vec3 socket(Cable.End end) {
        return socket(end.type(), end.pos(), end.direction(), end.port);
    }

    /**
     * @param facing the side the device's front points to (its block state)
     */
    public static Vec3 socket(DeviceType type, BlockPos pos, Direction facing, int port) {
        Direction face = type.portsOnFront ? facing : facing.getOpposite();
        // Seen from in front of that face, getClockWise() points to the viewer's left: port 1 is leftmost.
        Direction left = face.getClockWise();
        double offset = type.ports <= 1 ? 0 : type.socketSpread / 2 - type.socketSpread * port / (type.ports - 1);
        double depth = type.socketDepth + 0.02;
        if (type.isConsolePort(port)) {
            if (type.portsOnFront) {
                // Switches and routers have their console socket in the middle of the back.
                face = facing.getOpposite();
                left = face.getClockWise();
                offset = 0;
                depth = type.socketDepth + 0.02;
            } else {
                offset = -Math.min(0.32, type.socketSpread / 2 + 0.16);
            }
        }
        return new Vec3(
                pos.getX() + 0.5 + face.getStepX() * depth + left.getStepX() * offset,
                pos.getY() + type.socketY,
                pos.getZ() + 0.5 + face.getStepZ() * depth + left.getStepZ() * offset);
    }
}

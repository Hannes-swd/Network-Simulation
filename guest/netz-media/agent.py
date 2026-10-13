#!/usr/bin/env python3
"""netz-media: the Minecraft camera as a webcam in Linux.

The mod sends the pictures of a camera block over the virtio serial port "org.netz.media": each one
is b"NMF1", a type byte (1 = YUYV), width and height (16 bit) and the length (32 bit, all
big-endian), then the pixels. This agent writes them to a v4l2loopback device ("Minecraft-Kamera",
/dev/video10) at a steady 10 pictures per second, repeats the last one while nothing new comes, and
shows a dark "no picture" frame when the camera is off. Every few seconds it says hello on the port,
so the mod knows a camera driver is there.
"""
import fcntl
import os
import select
import struct
import sys
import threading
import time

PORT = "/dev/virtio-ports/org.netz.media"
VIDEO = "/dev/video10"
WIDTH, HEIGHT = 640, 360
FRAME_BYTES = WIDTH * HEIGHT * 2
MAGIC = b"NMF1"
FPS = 10
STALE_SECONDS = 3.0

VIDIOC_S_FMT = 0xC0D05605  # _IOWR('V', 5, struct v4l2_format), 208 bytes on 64 bit
V4L2_BUF_TYPE_VIDEO_OUTPUT = 2
V4L2_PIX_FMT_YUYV = 0x56595559
V4L2_FIELD_NONE = 1
V4L2_COLORSPACE_SMPTE170M = 1

latest = None
latest_time = 0.0
lock = threading.Lock()


def no_picture():
    """Dark blue-grey with a lighter band in the middle, so "camera off" is visible as such."""
    dark = bytes((40, 136, 40, 122)) * (WIDTH // 2)
    band = bytes((70, 140, 70, 118)) * (WIDTH // 2)
    rows = [band if HEIGHT * 2 // 5 <= y < HEIGHT * 3 // 5 else dark for y in range(HEIGHT)]
    return b"".join(rows)


NO_PICTURE = no_picture()


def open_video():
    fd = os.open(VIDEO, os.O_RDWR)
    fmt = struct.pack("I4x12I", V4L2_BUF_TYPE_VIDEO_OUTPUT, WIDTH, HEIGHT, V4L2_PIX_FMT_YUYV, V4L2_FIELD_NONE,
                      WIDTH * 2, FRAME_BYTES, V4L2_COLORSPACE_SMPTE170M, 0, 0, 0, 0, 0)
    fmt += b"\0" * (208 - len(fmt))
    fcntl.ioctl(fd, VIDIOC_S_FMT, bytearray(fmt))
    return fd


def read_port():
    """Reads pictures from the mod; reopens the port whenever it goes away."""
    global latest, latest_time
    while True:
        try:
            fd = os.open(PORT, os.O_RDWR | os.O_NONBLOCK)
        except OSError:
            time.sleep(3)
            continue
        buffer = bytearray()
        last_hello = 0.0
        try:
            while True:
                now = time.monotonic()
                if now - last_hello > 4:
                    try:
                        os.write(fd, b"hello netz-media 1\n")
                    except BlockingIOError:
                        pass  # Nobody on the other side yet.
                    last_hello = now
                ready, _, _ = select.select([fd], [], [], 1.0)
                if not ready:
                    continue
                try:
                    chunk = os.read(fd, 1 << 20)
                except BlockingIOError:
                    continue
                if not chunk:
                    time.sleep(0.5)  # The game is not connected right now.
                    continue
                buffer += chunk
                while True:
                    start = buffer.find(MAGIC)
                    if start < 0:
                        del buffer[:max(0, len(buffer) - 3)]
                        break
                    if start > 0:
                        del buffer[:start]
                    if len(buffer) < 13:
                        break
                    kind, width, height, length = struct.unpack(">BHHI", bytes(buffer[4:13]))
                    if length > 16 << 20:
                        del buffer[:4]
                        continue
                    if len(buffer) < 13 + length:
                        break
                    data = bytes(buffer[13:13 + length])
                    del buffer[:13 + length]
                    if kind == 1 and width == WIDTH and height == HEIGHT and length == FRAME_BYTES:
                        with lock:
                            latest = data
                            latest_time = time.monotonic()
        except OSError as e:
            print("port:", e, file=sys.stderr)
            time.sleep(1)
        finally:
            os.close(fd)


def main():
    threading.Thread(target=read_port, daemon=True).start()
    while True:
        try:
            video = open_video()
        except OSError as e:
            print("video device:", e, file=sys.stderr)
            time.sleep(5)
            continue
        try:
            next_frame = time.monotonic()
            while True:
                with lock:
                    frame = latest if latest is not None and time.monotonic() - latest_time < STALE_SECONDS else NO_PICTURE
                os.write(video, frame)
                next_frame += 1.0 / FPS
                delay = next_frame - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                else:
                    next_frame = time.monotonic()
        except OSError as e:
            print("video device:", e, file=sys.stderr)
            time.sleep(2)
        finally:
            os.close(video)


if __name__ == "__main__":
    main()

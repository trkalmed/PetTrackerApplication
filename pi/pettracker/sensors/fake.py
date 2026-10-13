"""Simulated sensors so the service can run and be tested without a Pi."""

import math
import random
import struct
import time
from typing import List, Optional

from .base import Camera, Feeder, LocationSensor, MotionSensor, Scale, SensorSet


class FakeScale(Scale):
    def __init__(self, initial_grams: float = 0.0, noise_grams: float = 0.4, seed: int = 0):
        self._grams = initial_grams
        self._noise = noise_grams
        self._random = random.Random(seed)
        self._tare_offset = 0.0
        self._eating_rate_g_per_s = 0.0
        self._last_read = time.monotonic()
        self.available_flag = True

    def read_grams(self) -> Optional[float]:
        now = time.monotonic()
        elapsed = now - self._last_read
        self._last_read = now

        if self._eating_rate_g_per_s > 0 and self._grams > 0:
            self._grams = max(0.0, self._grams - self._eating_rate_g_per_s * elapsed)

        if not self.available_flag:
            return None
        return self._grams - self._tare_offset + self._random.gauss(0, self._noise)

    def tare(self) -> None:
        self._tare_offset = self._grams

    def add_food(self, grams: float) -> None:
        self._grams += grams

    def start_eating(self, grams_per_second: float = 2.0) -> None:
        self._eating_rate_g_per_s = grams_per_second

    def stop_eating(self) -> None:
        self._eating_rate_g_per_s = 0.0

    def set_weight(self, grams: float) -> None:
        self._grams = grams

    @property
    def available(self) -> bool:
        return self.available_flag


class FakeMotionSensor(MotionSensor):
    def __init__(self, room: str, trigger_probability: float = 0.05, seed: int = 0):
        self._room = room
        self._probability = trigger_probability
        self._random = random.Random(seed)
        self._active_until = 0.0
        self.available_flag = True
        self._forced = False

    @property
    def room(self) -> str:
        return self._room

    def poll(self) -> bool:
        if not self.available_flag:
            return False
        if self._forced:
            return True

        now = time.monotonic()
        if now < self._active_until:
            return True
        if self._random.random() < self._probability:
            self._active_until = now + 3.0
            return True
        return False

    def trigger(self, hold_seconds: float = 3.0) -> None:
        self._active_until = time.monotonic() + hold_seconds

    def force(self, on: bool) -> None:
        self._forced = on

    @property
    def available(self) -> bool:
        return self.available_flag


class FakeFeeder(Feeder):
    def __init__(self, scale: Optional[FakeScale] = None, accuracy: float = 0.9, seed: int = 0):
        self._scale = scale
        self._accuracy = accuracy
        self._random = random.Random(seed)
        self.dispensed_calls: List[int] = []
        self.available_flag = True

    def dispense(self, grams: int) -> None:
        if not self.available_flag:
            raise RuntimeError("feeder unavailable")
        self.dispensed_calls.append(grams)

        actual = grams * self._random.uniform(self._accuracy, 2 - self._accuracy)
        if self._scale is not None:
            self._scale.add_food(actual)

    @property
    def available(self) -> bool:
        return self.available_flag


class FakeCamera(Camera):
    CYCLE_LENGTH = 8

    def __init__(self, width: int = 160, height: int = 120):
        self._width = width
        self._height = height
        self._frame = 0
        self._cache: dict = {}
        self.available_flag = True

    def read_jpeg(self) -> Optional[bytes]:
        if not self.available_flag:
            return None
        index = self._frame % self.CYCLE_LENGTH
        self._frame += 1
        if index not in self._cache:
            self._cache[index] = _encode_jpeg(
                self._width, self._height, index * 4)
        return self._cache[index]

    @property
    def available(self) -> bool:
        return self.available_flag

    def close(self) -> None:
        self.available_flag = False


class FakeLocationSensor(LocationSensor):
    def __init__(self, fix_after_reads: int = 3, lat: float = 47.4979, lon: float = 19.0402):
        self._reads = 0
        self._fix_after = fix_after_reads
        self._lat = lat
        self._lon = lon
        self.available_flag = True

    @property
    def port(self) -> str:
        return "fake-serial"

    def read_fix(self) -> Optional[dict]:
        if not self.available_flag:
            return None
        self._reads += 1
        if self._reads < self._fix_after:
            return None
        return {"lat": self._lat, "lon": self._lon, "satellites": 7}

    def read_sentences(self) -> List[str]:
        if not self.available_flag:
            return []

        self._reads += 1
        satellites = 0 if self._reads < self._fix_after else 7
        if satellites == 0:
            body = "GPGGA,120000.00,,,,,0,00,99.99,,,,,,"
        else:
            body = (f"GPGGA,120000.00,{_ddmm(self._lat, 2)},N,"
                    f"{_ddmm(self._lon, 3)},E,1,{satellites:02d},0.9,545.4,M,46.9,M,,")
        return [_nmea(body), _nmea("GPGSV,1,1,04,01,40,083,41")]

    @property
    def available(self) -> bool:
        return self.available_flag


def _ddmm(degrees: float, width: int) -> str:
    whole = int(abs(degrees))
    minutes = (abs(degrees) - whole) * 60.0
    return f"{whole:0{width}d}{minutes:07.4f}"


def _nmea(body: str) -> str:
    checksum = 0
    for character in body:
        checksum ^= ord(character)
    return f"${body}*{checksum:02X}"


def build_fake_sensor_set(seed: int = 0, rooms: Optional[List[str]] = None) -> SensorSet:
    if rooms is None:
        rooms = ["Kitchen", "Living room", "Hall"]

    scale = FakeScale(initial_grams=0.0, seed=seed)
    return SensorSet(
        scale=scale,
        motion_sensors=[
            FakeMotionSensor(room, seed=seed + i) for i, room in enumerate(rooms)
        ],
        feeder=FakeFeeder(scale=scale, seed=seed),
        camera=FakeCamera(),
        location=FakeLocationSensor(),
    )


# Minimal JPEG encoder, to avoid depending on Pillow.

_ZIGZAG = [
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
]

_QUANT = bytes([16] * 64)

_DC_BITS = bytes([0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0])
_DC_VALS = bytes(range(12))
_AC_BITS = bytes([0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D])
_AC_VALS = bytes(
    [
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
        0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
        0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72,
        0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45,
        0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
        0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75,
        0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3,
        0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
        0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9,
        0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4,
        0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    ]
)


class _BitWriter:
    def __init__(self) -> None:
        self._out = bytearray()
        self._bits = 0
        self._count = 0

    def write(self, value: int, length: int) -> None:
        for i in range(length - 1, -1, -1):
            self._bits = (self._bits << 1) | ((value >> i) & 1)
            self._count += 1
            if self._count == 8:
                self._out.append(self._bits & 0xFF)
                if self._bits & 0xFF == 0xFF:
                    self._out.append(0x00)     # byte stuffing
                self._bits = 0
                self._count = 0

    def flush(self) -> bytes:
        while self._count:
            self.write(1, 1)
        return bytes(self._out)


def _huffman_table(bits: bytes, vals: bytes):
    codes, code, k = {}, 0, 0
    for length in range(1, 17):
        for _ in range(bits[length - 1]):
            codes[vals[k]] = (code, length)
            code += 1
            k += 1
        code <<= 1
    return codes


_DC_TABLE = _huffman_table(_DC_BITS, _DC_VALS)
_AC_TABLE = _huffman_table(_AC_BITS, _AC_VALS)


def _magnitude(value: int):
    if value == 0:
        return 0, 0
    size, magnitude = 0, abs(value)
    while magnitude:
        size += 1
        magnitude >>= 1
    return size, value if value > 0 else value + (1 << size) - 1


# Precomputed cosine table for the DCT.
_COS = [[math.cos((2 * a + 1) * b * math.pi / 16) for b in range(8)] for a in range(8)]
_C = [1 / math.sqrt(2)] + [1.0] * 7


def _dct_2d(block):
    rows = [0.0] * 64
    for x in range(8):
        base = x * 8
        row = block[base:base + 8]
        for v in range(8):
            cos_v = _COS
            total = 0.0
            for y in range(8):
                total += row[y] * cos_v[y][v]
            rows[base + v] = total * _C[v] * 0.5

    out = [0.0] * 64
    for v in range(8):
        for u in range(8):
            total = 0.0
            for x in range(8):
                total += rows[x * 8 + v] * _COS[x][u]
            out[u * 8 + v] = total * _C[u] * 0.5
    return out


def _encode_jpeg(width: int, height: int, frame: int) -> bytes:
    width = (width // 8) * 8
    height = (height // 8) * 8

    block_x = (frame * 8) % max(width - 40, 8)
    pixels = [[40] * width for _ in range(height)]
    for y in range(height // 3, height // 3 + 40):
        for x in range(block_x, min(block_x + 40, width)):
            pixels[y][x] = 220

    out = bytearray()
    out += b"\xFF\xD8"                                        # SOI
    out += b"\xFF\xDB\x00\x43\x00" + _QUANT                   # DQT
    out += b"\xFF\xC0\x00\x0B\x08" + struct.pack(">HH", height, width) \
        + b"\x01\x01\x11\x00"                                 # SOF0, 1 component
    out += b"\xFF\xC4" + struct.pack(">H", 2 + 1 + 16 + len(_DC_VALS)) \
        + b"\x00" + _DC_BITS + _DC_VALS                       # DHT (DC)
    out += b"\xFF\xC4" + struct.pack(">H", 2 + 1 + 16 + len(_AC_VALS)) \
        + b"\x10" + _AC_BITS + _AC_VALS                       # DHT (AC)
    out += b"\xFF\xDA\x00\x08\x01\x01\x00\x00\x3F\x00"        # SOS

    writer = _BitWriter()
    previous_dc = 0
    for by in range(0, height, 8):
        for bx in range(0, width, 8):
            block = [
                pixels[by + y][bx + x] - 128
                for y in range(8)
                for x in range(8)
            ]
            coeffs = _dct_2d(block)
            quantised = [int(round(coeffs[_ZIGZAG[i]] / _QUANT[i])) for i in range(64)]

            diff = quantised[0] - previous_dc
            previous_dc = quantised[0]
            size, bits = _magnitude(diff)
            code, length = _DC_TABLE[size]
            writer.write(code, length)
            if size:
                writer.write(bits, size)

            run = 0
            for i in range(1, 64):
                if quantised[i] == 0:
                    run += 1
                    continue
                while run > 15:
                    code, length = _AC_TABLE[0xF0]
                    writer.write(code, length)
                    run -= 16
                size, bits = _magnitude(quantised[i])
                code, length = _AC_TABLE[(run << 4) | size]
                writer.write(code, length)
                writer.write(bits, size)
                run = 0
            if run:
                code, length = _AC_TABLE[0x00]
                writer.write(code, length)

    out += writer.flush()
    out += b"\xFF\xD9"                                        # EOI
    return bytes(out)

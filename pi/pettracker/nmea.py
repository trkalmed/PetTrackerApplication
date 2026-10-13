"""NMEA sentence parsing for the GPS module."""

import logging
from typing import Optional

log = logging.getLogger(__name__)

_GGA_SENTENCES = ("$GPGGA", "$GNGGA")


def valid_checksum(sentence: str) -> bool:
    sentence = sentence.strip()
    if not sentence.startswith("$") or "*" not in sentence:
        return False

    body, _, checksum = sentence[1:].partition("*")
    if len(checksum) < 2:
        return False

    computed = 0
    for character in body:
        computed ^= ord(character)
    try:
        return computed == int(checksum[:2], 16)
    except ValueError:
        return False


def parse_gga(sentence: str) -> Optional[dict]:
    sentence = sentence.strip()
    if not sentence.startswith(_GGA_SENTENCES):
        return None
    if not valid_checksum(sentence):
        log.debug("discarding a GGA sentence with a bad checksum")
        return None

    fields = sentence.split("*")[0].split(",")
    if len(fields) < 8:
        return None

    try:
        quality = int(fields[6] or 0)
    except ValueError:
        return None
    if quality == 0:
        # Quality 0 means no fix. The zero coordinates are not a position.
        return None

    latitude = _degrees(fields[2], fields[3])
    longitude = _degrees(fields[4], fields[5])
    if latitude is None or longitude is None:
        return None

    try:
        satellites = int(fields[7] or 0)
    except ValueError:
        satellites = 0

    return {"lat": latitude, "lon": longitude, "satellites": satellites}


def _degrees(value: str, hemisphere: str) -> Optional[float]:
    if not value or not hemisphere:
        return None

    try:
        # Longitudes reach 180, latitudes only 90, so the split point differs.
        split = 2 if hemisphere in ("N", "S") else 3
        degrees = float(value[:split])
        minutes = float(value[split:])
    except ValueError:
        return None

    decimal = degrees + minutes / 60.0
    if hemisphere in ("S", "W"):
        decimal = -decimal

    if hemisphere in ("N", "S") and not -90.0 <= decimal <= 90.0:
        return None
    if hemisphere in ("E", "W") and not -180.0 <= decimal <= 180.0:
        return None
    return decimal

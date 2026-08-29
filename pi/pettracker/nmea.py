"""Reading NMEA sentences, the format every GPS module speaks.

A NEO-6M sends lines of text at 9600 baud whether or not it can see the sky:

    $GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47

Only one of them is needed here. GGA carries the fix quality, the satellite
count and the position, which is the whole question. The others repeat parts of
it in different arrangements.

Three details decide whether this reports where you are or somewhere else:

**Field 6 is the fix quality, and 0 means there is no fix.** The coordinate
fields are still populated in that case, usually with zeros — which is a real
position, in the Gulf of Guinea. A module that has just been switched on indoors
will happily tell you the pet is off the coast of West Africa unless this field
is read first.

**Coordinates are degrees and minutes glued together**, not decimal degrees.
``4807.038`` is 48 degrees and 7.038 minutes, which is 48.1173 degrees, not
48.07. The two agree to within a degree, so the mistake produces a position in
roughly the right country and is easy to miss.

**Lines get corrupted.** Serial at 9600 baud next to a servo picks up noise, and
half a sentence joined to half of the next one can parse cleanly into a position
that is nowhere near you. Every sentence carries an XOR checksum, and it is
checked here rather than trusted.
"""

import logging
from typing import Optional

log = logging.getLogger(__name__)

#: Talker prefixes for the fix sentence. GP is GPS alone; GN is a receiver using
#: more than one constellation, which most modern modules do. Both carry the
#: same fields, and a module can switch between them as satellites come and go.
_GGA_SENTENCES = ("$GPGGA", "$GNGGA")


def valid_checksum(sentence: str) -> bool:
    """Whether the trailing ``*hh`` matches the XOR of everything before it.

    Cheap, and the only defence against a corrupted line parsing into a
    plausible position somewhere else entirely.
    """
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
    """A position from one GGA sentence, or None if it does not carry one.

    None covers every way this can fail to be a location: a different sentence,
    a corrupted one, and — the important one — a valid sentence from a receiver
    that has no fix yet, which is the normal state indoors and for the first
    minutes outdoors.

    :returns: ``{"lat": float, "lon": float, "satellites": int}`` or None
    """
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
        # No fix. The coordinate fields below are usually zeros, and zero is a
        # real place — reporting it would put the Pi in the Atlantic.
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
    """``"4807.038", "N"`` to ``48.1173``.

    Degrees and minutes are run together in the field, with two digits of
    degrees for a latitude and three for a longitude — the sign is the
    hemisphere letter, not a minus.
    """
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

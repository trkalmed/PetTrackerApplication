#!/usr/bin/env python3
"""Entry point.

    python3 run.py --fake        simulated sensors, runs anywhere
    python3 run.py               real hardware, Raspberry Pi only

--fake is not a toy mode. It runs the same service, the same event pipeline and
the same HTTP contract as the real thing; only the five sensor objects differ.
Point the Android app at a laptop running --fake and the entire app-to-Pi path
can be finished and debugged before any wiring exists.
"""

import argparse
import logging
import sys

from pettracker.monitor import Monitor
from pettracker.server import DEFAULT_PORT, PetTrackerServer, local_ip
from pettracker.store import EventStore


def pir_pins(args, rooms):
    """The GPIO for each room, defaults filled in and the pairing checked.

    Resolved before any hardware is touched: a typo should be a message at
    startup, not two claimed pins and a half-built sensor set.
    """
    from pettracker.sensors.real import DEFAULT_PIR_PINS, parse_pins, pin_map

    pins = parse_pins(args.pir_pins) if args.pir_pins else DEFAULT_PIR_PINS[:len(rooms)]
    pin_map(rooms, pins)          # raises ValueError with something readable
    return pins


def check_motion_mode(args, rooms, log) -> int:
    """--check-motion: watch the PIRs and exit, without starting the service.

    Bringing a sensor up is a different job from running the service, and it
    needs the opposite of a daemon: no event log, no HTTP, no debounce — just
    the pin, on screen, now. Everything else stays off so nothing else can be
    blamed for what you see.
    """
    from pettracker.bringup import check_motion

    if args.fake:
        from pettracker.sensors.fake import build_fake_sensor_set
        sensors = build_fake_sensor_set(rooms=rooms).motion_sensors
    else:
        from pettracker.sensors.real import build_motion_sensors

        try:
            pins = pir_pins(args, rooms)
        except ValueError as e:
            log.error("%s", e)
            return 2
        sensors = build_motion_sensors(rooms, pins, warmup_s=args.pir_warmup)

    try:
        check_motion(sensors)
    finally:
        for sensor in sensors:
            sensor.close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Pet Tracker Pi service")
    parser.add_argument("--fake", action="store_true",
                        help="use simulated sensors instead of real hardware")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"HTTP port (default {DEFAULT_PORT}, matches the app)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--db", default="pettracker.db")
    parser.add_argument("--fps", type=int, default=10, help="MJPEG frame rate")
    parser.add_argument("--rooms", default="Kitchen,Living room,Hall",
                        help="comma-separated room names, one per PIR sensor")
    parser.add_argument("--pir-pins", default=None,
                        help="comma-separated BCM GPIO numbers, in the same order "
                             "as --rooms (default 17,27,22)")
    parser.add_argument("--pir-warmup", type=float, default=60.0,
                        help="seconds to ignore the PIRs for after start. The "
                             "warm-up belongs to the sensor powering up, not to "
                             "this process, so pass 0 when restarting the service "
                             "on a rig that has been powered for a while")
    parser.add_argument("--check-motion", action="store_true",
                        help="watch the PIR sensors live and exit, instead of "
                             "running the service. Use this while wiring them")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    log = logging.getLogger("run")

    rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]

    if args.check_motion:
        return check_motion_mode(args, rooms, log)

    pins = None
    if not args.fake:
        try:
            pins = pir_pins(args, rooms)
        except ValueError as e:
            log.error("%s", e)
            return 2

    if args.fake:
        from pettracker.sensors.fake import build_fake_sensor_set
        sensors = build_fake_sensor_set(rooms=rooms)
        log.info("simulated sensors: %s", ", ".join(rooms))
    else:
        try:
            from pettracker.sensors.real import build_real_sensor_set
        except ImportError as e:
            log.error("real sensors need Raspberry Pi libraries: %s", e)
            log.error("on a laptop, run with --fake")
            return 1
        sensors = build_real_sensor_set(
            rooms=rooms, pir_pins=pins, pir_warmup_s=args.pir_warmup)

    store = EventStore(args.db)
    monitor = Monitor(sensors, store)
    monitor.start()

    server = PetTrackerServer(sensors, store, monitor,
                              host=args.host, port=args.port, stream_fps=args.fps)

    address = local_ip() or "<this machine's IP>"
    log.info("listening on %s:%d", args.host, args.port)
    log.info("enter this in the app's Pi Connection dialog: %s", address)
    log.info("  stream : http://%s:%d/stream", address, args.port)
    log.info("  events : http://%s:%d/events?since=0", address, args.port)
    log.info("  health : http://%s:%d/health", address, args.port)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")
    finally:
        server.shutdown()
        monitor.stop()
        sensors.close()
        store.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

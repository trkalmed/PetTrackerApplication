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
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    log = logging.getLogger("run")

    rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]

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
        sensors = build_real_sensor_set(rooms=rooms)

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

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
from pettracker.snapshots import SnapshotStore
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


def build_scale(args, log):
    """The load cell on its own, for the two bring-up modes."""
    from pettracker.sensors.real import (DEFAULT_SCALE_PINS, HX711Scale,
                                         parse_pins)

    pins = parse_pins(args.scale_pins) if args.scale_pins else DEFAULT_SCALE_PINS
    if len(pins) != 2:
        raise ValueError("--scale-pins takes exactly two pins: DT,SCK")

    return HX711Scale(
        dout_pin=pins[0], sck_pin=pins[1],
        calibration_factor=(args.scale_factor
                            if args.scale_factor is not None
                            else HX711Scale.UNCALIBRATED))


def scale_mode(args, log) -> int:
    """--check-scale and --calibrate-scale, without starting the service.

    Both need the bowl still and nobody else clocking the amplifier, so the
    monitor thread stays off. Two readers on one HX711 interleave their clock
    pulses and produce two corrupt samples rather than one good one.
    """
    from pettracker.bringup import calibrate_scale, check_scale

    if args.fake:
        from pettracker.sensors.fake import build_fake_sensor_set
        scale = build_fake_sensor_set().scale
    else:
        try:
            scale = build_scale(args, log)
        except ValueError as e:
            log.error("%s", e)
            return 2

    try:
        if args.calibrate_scale:
            return 0 if calibrate_scale(scale) is not None else 1
        check_scale(scale)
        if not args.fake and scale.available and scale.read_raw() is None:
            _suggest_swapped_pins(args, scale, log)
        return 0
    finally:
        close = getattr(scale, "close", None)
        if close:
            close()


def _suggest_swapped_pins(args, scale, log) -> None:
    """Nothing read — see whether the other pin order does.

    Worth the extra ten seconds at the end of a failed check: swapping DT and
    SCK produces exactly the same symptom as an amplifier with no power, and
    only one of the two can be ruled out from an armchair.
    """
    from pettracker.sensors.real import DEFAULT_SCALE_PINS, parse_pins, probe_scale_pins

    pins = parse_pins(args.scale_pins) if args.scale_pins else DEFAULT_SCALE_PINS
    scale.close()          # the probe needs the pins

    print()
    print("Trying the pins the other way round...")
    working = probe_scale_pins(pins[0], pins[1])

    if working is None:
        print("  No reading either way, so this is not a swapped pair.")
        print("  The amplifier is not driving its data line at all: check that it")
        print("  has power, and that its GND is shared with the Pi.")
    elif list(working) != list(pins):
        print(f"  It reads with DT={working[0]} and SCK={working[1]} — your two")
        print("  wires are the other way round. Either swap them, or pass")
        print(f"  --scale-pins \"{working[0]},{working[1]}\" and put")
        print(f"  PETTRACKER_SCALE_PINS={working[0]},{working[1]} in service.env.")


def build_feeder(args):
    """The servo on its own, with whatever angles have been measured so far."""
    from pettracker.sensors.real import ServoFeeder

    return ServoFeeder(
        pin=args.servo_pin,
        open_angle=(args.servo_open if args.servo_open is not None
                    else ServoFeeder.DEFAULT_OPEN_ANGLE),
        closed_angle=(args.servo_closed if args.servo_closed is not None
                      else ServoFeeder.DEFAULT_CLOSED_ANGLE),
        ms_per_gram=(args.servo_ms_per_gram if args.servo_ms_per_gram is not None
                     else ServoFeeder.DEFAULT_MS_PER_GRAM))


def servo_mode(args, log) -> int:
    """--check-servo: move the servo by hand, without starting the service."""
    from pettracker.bringup import check_servo

    if args.fake:
        from pettracker.sensors.fake import build_fake_sensor_set
        feeder = build_fake_sensor_set().feeder
    else:
        feeder = build_feeder(args)

    try:
        check_servo(feeder)
        return 0
    finally:
        close = getattr(feeder, "close", None)
        if close:
            close()


def gps_mode(args, log) -> int:
    """--check-gps: watch the sentences arrive, without starting the service."""
    from pettracker.bringup import check_gps

    if args.fake:
        from pettracker.sensors.fake import build_fake_sensor_set
        location = build_fake_sensor_set().location
    else:
        from pettracker.sensors.real import UbloxLocationSensor
        location = UbloxLocationSensor(port=args.gps_port)

    try:
        check_gps(location)
        return 0
    finally:
        close = getattr(location, "close", None)
        if close:
            close()


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
    parser.add_argument("--camera", default="csi",
                        help="which camera this Pi has: csi (ribbon), usb, or none")
    parser.add_argument("--camera-device", type=int, default=0,
                        help="which /dev/videoN, for --camera usb (default 0)")
    parser.add_argument("--snapshot-dir", default="snapshots",
                        help="where motion snapshots are written (default ./snapshots)")
    parser.add_argument("--no-snapshots", action="store_true",
                        help="do not save a picture when motion fires")
    parser.add_argument("--scale-pins", default=None,
                        help="HX711 data and clock as BCM GPIO numbers, "
                             "\"DT,SCK\" (default 5,6)")
    parser.add_argument("--scale-factor", type=float, default=None,
                        help="counts per gram, from --calibrate-scale. Without "
                             "it every weight is raw counts wearing a gram label")
    parser.add_argument("--check-scale", action="store_true",
                        help="watch the load cell live and exit. Use this while "
                             "wiring it")
    parser.add_argument("--calibrate-scale", action="store_true",
                        help="find the counts-per-gram factor with a known "
                             "weight, print it, and exit")
    parser.add_argument("--gps-port", default="/dev/serial0",
                        help="serial port the GPS is on (default /dev/serial0; "
                             "/dev/ttyAMA0 is wrong on a Pi 4)")
    parser.add_argument("--no-gps", action="store_true",
                        help="do not open the serial port at all")
    parser.add_argument("--check-gps", action="store_true",
                        help="watch the NMEA sentences arrive and exit. Use this "
                             "while wiring it")
    parser.add_argument("--servo-pin", type=int, default=18,
                        help="GPIO the servo signal wire is on (default 18)")
    parser.add_argument("--servo-open", type=int, default=None,
                        help="angle at which the hopper gate is open")
    parser.add_argument("--servo-closed", type=int, default=None,
                        help="angle at which it is shut")
    parser.add_argument("--servo-ms-per-gram", type=float, default=None,
                        help="how long the gate must be open per gram, measured "
                             "by opening it for a known time and weighing what fell")
    parser.add_argument("--check-servo", action="store_true",
                        help="move the servo to angles you type, to find the open "
                             "and closed positions for a gate")
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

    if args.check_scale or args.calibrate_scale:
        return scale_mode(args, log)

    if args.check_gps:
        return gps_mode(args, log)

    if args.check_servo:
        return servo_mode(args, log)

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
        from pettracker.sensors.real import HX711Scale, parse_pins

        try:
            scale_pins = parse_pins(args.scale_pins) if args.scale_pins else None
            if scale_pins is not None and len(scale_pins) != 2:
                raise ValueError("--scale-pins takes exactly two pins: DT,SCK")
            sensors = build_real_sensor_set(
                rooms=rooms, pir_pins=pins, pir_warmup_s=args.pir_warmup,
                camera=args.camera, camera_device=args.camera_device,
                gps_port=(None if args.no_gps else args.gps_port),
                servo_pin=args.servo_pin,
                servo_open=args.servo_open,
                servo_closed=args.servo_closed,
                servo_ms_per_gram=args.servo_ms_per_gram,
                scale_pins=scale_pins,
                scale_factor=(args.scale_factor if args.scale_factor is not None
                              else HX711Scale.UNCALIBRATED))
        except ValueError as e:
            log.error("%s", e)
            return 2

    store = EventStore(args.db)

    # Configured rather than detected. Asking whether the camera is available
    # *right now* used to decide this, which meant a Pi that started before its
    # webcam enumerated ran the whole session with snapshots silently off —
    # events still arriving, only the pictures missing. The camera reopens
    # itself now, so the only question here is whether this rig has one at all.
    snapshots = None
    if not args.no_snapshots and args.camera != "none":
        snapshots = SnapshotStore(args.snapshot_dir)
        log.info("motion snapshots: %s", snapshots.directory)

    monitor = Monitor(sensors, store, snapshots=snapshots)
    monitor.start()

    server = PetTrackerServer(sensors, store, monitor,
                              host=args.host, port=args.port, stream_fps=args.fps,
                              snapshots=snapshots)

    address = local_ip() or "<this machine's IP>"
    log.info("listening on %s:%d", args.host, args.port)
    log.info("enter this in the app's Pi Connection dialog: %s", address)
    log.info("  stream : http://%s:%d/stream", address, args.port)
    log.info("  events : http://%s:%d/events?since=0", address, args.port)
    log.info("  health : http://%s:%d/health", address, args.port)
    if snapshots is not None:
        log.info("  motion snapshots are saved and served from /snapshot?name=…")

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

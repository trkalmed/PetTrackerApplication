"""Sensor interfaces, simulated implementations and the real Pi ones."""

from .base import Camera, Feeder, LocationSensor, MotionSensor, Scale, SensorSet

__all__ = [
    "Camera", "Feeder", "LocationSensor", "MotionSensor", "Scale", "SensorSet",
]

import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from app.manager import LightManager
from app.protocols import (
    FrameRequest,
    classify,
    encode,
    hex_bytes,
    parse_ks03_notification,
    permute_color,
    sunday_one,
)


class ProtocolTests(unittest.TestCase):
    def test_ks03_power_color_and_scene(self):
        on = encode("ks03_tilde", FrameRequest(kind="power", on=True))
        off = encode("ks03_tilde", FrameRequest(kind="power", on=False))
        color = encode(
            "ks03_tilde",
            FrameRequest(kind="color", r=255, g=0, b=0, brightness=80),
        )
        scene = encode(
            "ks03_tilde",
            FrameRequest(kind="effect", effect=0x95, speed=40, brightness=80),
        )
        self.assertEqual(hex_bytes(on), "5B F0 00 B5")
        self.assertEqual(hex_bytes(off), "5B 0F 00 B5")
        self.assertEqual(hex_bytes(color), "5A 00 01 FF 00 00 00 50 00 A5")
        self.assertEqual(hex_bytes(scene), "5C 00 95 28 50 00 C5")

    def test_ks_classic_color_matches_vendor_frame(self):
        color = encode("ks_classic", FrameRequest(kind="color", r=255, g=128, b=0))
        power = encode("ks_classic", FrameRequest(kind="power", on=True))
        self.assertEqual(hex_bytes(color), "7E 07 05 03 FF 80 00 00 EF")
        self.assertEqual(hex_bytes(power), "7E 04 04 F0 00 01 FF 00 EF")

    def test_leddmx_uses_rbg_and_scaled_brightness(self):
        self.assertEqual(permute_color(0, 255, 0, "rbg"), (0, 0, 255))
        color = encode(
            "leddmx",
            FrameRequest(kind="color", r=0, g=255, b=0, color_order="rbg"),
        )
        bright = encode("leddmx", FrameRequest(kind="brightness", brightness=100))
        power = encode("leddmx", FrameRequest(kind="power", on=True))
        alt = encode("leddmx", FrameRequest(kind="power", on=True, power_style="alt"))
        self.assertEqual(hex_bytes(color), "7B FF 07 00 00 FF 00 FF BF")
        self.assertEqual(hex_bytes(bright), "7B FF 01 20 64 00 FF FF BF")
        self.assertEqual(hex_bytes(power), "7B FF 04 03 FF FF FF FF BF")
        self.assertEqual(hex_bytes(alt), "7B FF 04 01 FF FF FF FF BF")

    def test_ledble_power_and_color(self):
        color = encode("ledble", FrameRequest(kind="color", r=0, g=0, b=255))
        off = encode("ledble", FrameRequest(kind="power", on=False))
        effect = encode("ledble", FrameRequest(kind="effect", effect=0x87))
        self.assertEqual(hex_bytes(color), "7E FF 05 03 00 00 FF FF EF")
        self.assertEqual(hex_bytes(off), "7E FF 04 00 FF FF FF FF EF")
        self.assertEqual(hex_bytes(effect), "7E FF 03 87 03 FF FF FF EF")

    def test_classify_names(self):
        self.assertEqual(classify("KS03~A1B2"), ("ks03_tilde", True))
        self.assertEqual(classify("KS03-4410"), ("ks_classic", True))
        self.assertEqual(classify("LEDDMX-03-0042"), ("leddmx", True))
        self.assertEqual(classify("LEDBLE-01-9201"), ("ledble", True))
        self.assertEqual(
            classify(None, ["0000ffe0-0000-1000-8000-00805f9b34fb"]),
            ("leddmx", False),
        )
        self.assertEqual(classify("Headphones"), (None, False))

    def test_weekday_and_state_notification(self):
        sunday = datetime(2026, 10, 25, 21, 5, 6)
        monday = datetime(2026, 10, 26, 9, 0, 0)
        self.assertEqual(sunday_one(sunday), 1)
        self.assertEqual(sunday_one(monday), 2)
        packet = encode("ks03_tilde", FrameRequest(kind="time", when=sunday))
        self.assertEqual(hex_bytes(packet), "7E 07 83 15 05 06 01 FF EF")
        parsed = parse_ks03_notification(bytes.fromhex("5f0601f0ff00000064640000"))
        self.assertEqual(parsed["on"], True)
        self.assertEqual(parsed["r"], 255)
        self.assertEqual(parsed["brightness"], 100)
        self.assertEqual(parsed["speed"], 0)


class DemoFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_demo_scan_connect_and_color(self):
        with tempfile.TemporaryDirectory() as folder:
            manager = LightManager(Path(folder) / "lights.json", demo=True)
            await manager.scan(2)
            self.assertEqual(len(manager.lights), 3)
            await manager.connect("demo:dmx")
            await manager.control(["demo:dmx"], {"type": "color", "r": 0, "g": 255, "b": 0})
            light = manager.lights["demo:dmx"]
            self.assertTrue(light.connected)
            self.assertEqual(light.color_order, "rbg")
            self.assertEqual(light.last_command, "7B FF 01 19 50 00 FF FF BF")
            await manager.control(["demo:dmx"], {"type": "power", "on": False})
            self.assertFalse(light.on)
            self.assertEqual(light.last_command, "7B FF 04 02 FF FF FF FF BF")


if __name__ == "__main__":
    unittest.main()

"""Scan, connect, and send commands to lights."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from app.protocols import (
    COLOR_ORDERS,
    LEDDMX,
    PROFILE_IDS,
    RHYTHM_MODES,
    WRITE_CHAR,
    FrameRequest,
    catalog_by_id,
    classify,
    encode,
    hex_bytes,
    known_effect_ids,
    parse_ks03_notification,
    profile_catalog,
    uuid_matches,
    warning_for,
)
from app.store import load_lights, save_lights


DEMO_LIGHTS = (
    {
        "address": "demo:ks",
        "name": "KS03~Floor",
        "profile": "ks03_tilde",
        "confident": True,
        "rssi": -48,
    },
    {
        "address": "demo:dmx",
        "name": "LEDDMX-03-0042",
        "profile": "leddmx",
        "confident": True,
        "rssi": -61,
    },
    {
        "address": "demo:ble",
        "name": "LEDBLE-01-9201",
        "profile": "ledble",
        "confident": True,
        "rssi": -70,
    },
)


@dataclass
class Light:
    address: str
    bluetooth_name: str = ""
    nickname: str = ""
    profile: str | None = None
    profile_confident: bool = False
    color_order: str = "rgb"
    power_style: str = "standard"
    rssi: int | None = None
    seen: bool = False
    saved: bool = False
    connected: bool = False
    connecting: bool = False
    on: bool = True
    r: int = 255
    g: int = 180
    b: int = 90
    brightness: int = 80
    mode: str = "solid"
    effect: int | None = None
    speed: int = 50
    rhythm: int | None = None
    sensitivity: int = 4
    last_command: str = ""
    error: str | None = None
    state_source: str = "remembered"
    session: object | None = field(default=None, repr=False)
    state_event: asyncio.Event | None = field(default=None, repr=False)

    def public(self) -> dict:
        return {
            "address": self.address,
            "name": self.bluetooth_name,
            "nickname": self.nickname or self.bluetooth_name or self.address,
            "profile": self.profile,
            "profileConfident": self.profile_confident,
            "colorOrder": self.color_order,
            "powerStyle": self.power_style,
            "rssi": self.rssi,
            "seen": self.seen,
            "saved": self.saved,
            "connected": self.connected,
            "connecting": self.connecting,
            "on": self.on,
            "r": self.r,
            "g": self.g,
            "b": self.b,
            "brightness": self.brightness,
            "mode": self.mode,
            "effect": self.effect,
            "speed": self.speed,
            "rhythm": self.rhythm,
            "sensitivity": self.sensitivity,
            "lastCommand": self.last_command,
            "error": self.error,
            "stateSource": self.state_source,
            "warning": warning_for(self.bluetooth_name, self.profile),
        }

    def persisted(self) -> dict:
        return {
            "address": self.address,
            "bluetoothName": self.bluetooth_name,
            "nickname": self.nickname,
            "profile": self.profile,
            "profileConfident": self.profile_confident,
            "colorOrder": self.color_order,
            "powerStyle": self.power_style,
            "on": self.on,
            "r": self.r,
            "g": self.g,
            "b": self.b,
            "brightness": self.brightness,
            "mode": self.mode,
            "effect": self.effect,
            "speed": self.speed,
            "rhythm": self.rhythm,
            "sensitivity": self.sensitivity,
        }


class LightManager:
    def __init__(self, store_path: Path, demo: bool = False) -> None:
        self.store_path = store_path
        self.demo = demo
        self.lights: dict[str, Light] = {}
        self.radio_error: str | None = None
        self.scanning = False
        self._radio = asyncio.Lock()
        self._load()

    def _load(self) -> None:
        for item in load_lights(self.store_path):
            address = str(item["address"])
            profile = item.get("profile")
            if profile not in PROFILE_IDS:
                profile = None
            order = item.get("colorOrder") or "rgb"
            if order not in COLOR_ORDERS:
                order = "rgb"
            self.lights[address] = Light(
                address=address,
                bluetooth_name=str(item.get("bluetoothName") or ""),
                nickname=str(item.get("nickname") or ""),
                profile=profile,
                profile_confident=bool(item.get("profileConfident")),
                color_order=order,
                power_style=item.get("powerStyle") if item.get("powerStyle") in ("standard", "alt") else "standard",
                saved=True,
                on=bool(item.get("on", True)),
                r=int(item.get("r", 255)),
                g=int(item.get("g", 180)),
                b=int(item.get("b", 90)),
                brightness=int(item.get("brightness", 80)),
                mode=item.get("mode") if item.get("mode") in ("solid", "effect", "rhythm") else "solid",
                effect=item.get("effect"),
                speed=int(item.get("speed", 50)),
                rhythm=item.get("rhythm"),
                sensitivity=int(item.get("sensitivity", 4)),
                state_source="remembered",
            )

    def _save(self) -> None:
        save_lights(
            self.store_path,
            [light.persisted() for light in self.lights.values() if light.saved],
        )

    def snapshot(self) -> dict:
        lights = sorted(
            self.lights.values(),
            key=lambda light: (
                not light.connected,
                not light.seen,
                (light.nickname or light.bluetooth_name or light.address).lower(),
            ),
        )
        return {
            "demo": self.demo,
            "scanning": self.scanning,
            "radioError": self.radio_error,
            "profiles": profile_catalog(),
            "colorOrders": list(COLOR_ORDERS),
            "rhythmModes": [{"id": mode_id, "name": name} for mode_id, name in RHYTHM_MODES],
            "lights": [light.public() for light in lights],
        }

    async def scan(self, seconds: float = 6) -> None:
        async with self._radio:
            self.scanning = True
            self.radio_error = None
            try:
                for light in self.lights.values():
                    if not light.connected:
                        light.seen = False
                found = await self._discover(seconds)
                for item in found:
                    self._merge_seen(item)
                # Drop one-off scan hits that were not saved and are gone now.
                for address in list(self.lights):
                    light = self.lights[address]
                    if not light.saved and not light.seen and not light.connected:
                        del self.lights[address]
            except Exception as exc:  # Bluetooth stacks fail in many local ways.
                self.radio_error = f"Bluetooth scan failed. {exc}"
            finally:
                self.scanning = False

    async def _discover(self, seconds: float) -> list[dict]:
        if self.demo:
            await asyncio.sleep(0.2)
            return [dict(item) for item in DEMO_LIGHTS]
        from bleak import BleakScanner

        discovered = await BleakScanner.discover(timeout=seconds, return_adv=True)
        found: list[dict] = []
        for address, (device, adv) in discovered.items():
            name = (adv.local_name if adv else None) or device.name or ""
            uuids = [str(uuid) for uuid in (adv.service_uuids or [])] if adv else []
            profile, confident = classify(name, uuids)
            if not name and profile is None:
                continue
            rssi = adv.rssi if adv else None
            found.append(
                {
                    "address": address,
                    "name": name,
                    "profile": profile,
                    "confident": confident,
                    "rssi": rssi,
                }
            )
        return found

    def _merge_seen(self, item: dict) -> None:
        address = item["address"]
        light = self.lights.get(address)
        if light is None:
            light = Light(address=address, saved=False)
            self.lights[address] = light
        light.seen = True
        light.rssi = item.get("rssi")
        if item.get("name"):
            light.bluetooth_name = item["name"]
            if not light.nickname:
                light.nickname = item["name"]
        if light.profile is None and item.get("profile"):
            light.profile = item["profile"]
            light.profile_confident = bool(item.get("confident"))
            if light.profile == LEDDMX:
                light.color_order = "rbg"
        light.error = None

    async def connect(self, address: str, profile: str | None = None) -> None:
        light = self.lights.get(address)
        if light is None:
            raise KeyError(address)
        if profile:
            if profile not in PROFILE_IDS:
                raise ValueError(f"Unknown profile: {profile}")
            light.profile = profile
        if not light.profile:
            raise ValueError("Choose a light type before connecting. Scan again if this device was not recognized.")
        async with self._radio:
            if light.connected:
                light.saved = True
                self._save()
                return
            light.connecting = True
            light.error = None
            try:
                session = await self._open_session(light)
                light.session = session
                light.connected = True
                light.saved = True
                light.state_source = "remembered"
                await self._after_connect(light)
            except Exception as exc:
                await self._close(light)
                light.error = str(exc)
                raise
            finally:
                light.connecting = False
                self._save()

    async def _open_session(self, light: Light):
        if self.demo:
            if not str(light.address).startswith("demo:"):
                raise RuntimeError("Demo mode only connects to the sample lights. Restart without --demo for real lamps.")
            return DemoSession()
        from bleak import BleakClient

        client = BleakClient(light.address, timeout=25)
        await client.connect()
        services = getattr(client, "services", None)
        if not services:
            getter = getattr(client, "get_services", None)
            services = await getter() if getter else []
        characteristics = [char for service in services for char in service.characteristics]
        short = WRITE_CHAR[light.profile]
        match = next((char for char in characteristics if uuid_matches(char.uuid, short)), None)
        if match is None:
            available = sorted({str(char.uuid) for char in characteristics})
            fallback = self._fallback_profile(light.profile, characteristics)
            await client.disconnect()
            if fallback and fallback != light.profile:
                light.profile = fallback
                light.profile_confident = False
                raise RuntimeError(
                    f"This lamp does not speak {short.upper()}. It looks like {catalog_by_id()[fallback]['label']}. Connect again to use that type."
                )
            raise RuntimeError(
                "Connected, but this device does not expose the expected light control characteristic. "
                f"Available characteristics: {', '.join(available) or 'none'}."
            )
        notify = None
        notify_short = {"ks03_tilde": "afd2"}.get(light.profile or "")
        if notify_short:
            notify = next((char for char in characteristics if uuid_matches(char.uuid, notify_short)), None)
        return BleSession(client, match, notify, light.address, self)

    def _fallback_profile(self, current: str | None, characteristics) -> str | None:
        shorts = []
        for char in characteristics:
            for profile, short in WRITE_CHAR.items():
                if uuid_matches(char.uuid, short):
                    shorts.append(profile)
        if current == "ks03_tilde" and "ks_classic" in shorts:
            return "ks_classic"
        if current == "ks_classic" and "ks03_tilde" in shorts:
            return "ks03_tilde"
        return None

    async def _after_connect(self, light: Light) -> None:
        if light.profile != "ks03_tilde":
            return
        light.state_event = asyncio.Event()
        session = light.session
        if isinstance(session, BleSession) and session.notify is not None:
            await session.start_notify(self._on_notify)
        await self._write_frame(light, FrameRequest(kind="time", when=datetime.now()))
        await self._write_frame(light, FrameRequest(kind="query"))
        if light.state_event is not None:
            try:
                await asyncio.wait_for(light.state_event.wait(), timeout=1.5)
            except TimeoutError:
                pass

    def _on_notify(self, address: str, payload: bytes) -> None:
        parsed = parse_ks03_notification(payload)
        light = self.lights.get(address)
        if not parsed or light is None:
            return
        if parsed["on"] is not None:
            light.on = parsed["on"]
        light.r = parsed["r"]
        light.g = parsed["g"]
        light.b = parsed["b"]
        light.brightness = parsed["brightness"]
        light.speed = parsed["speed"]
        light.state_source = "lamp"
        light.error = None
        if light.state_event is not None:
            light.state_event.set()
        self._save()

    async def disconnect(self, address: str) -> None:
        light = self.lights.get(address)
        if light is None:
            raise KeyError(address)
        async with self._radio:
            await self._close(light)

    async def _close(self, light: Light) -> None:
        session = light.session
        light.session = None
        light.connected = False
        light.connecting = False
        if session is not None:
            try:
                await session.disconnect()
            except Exception:
                pass

    async def forget(self, address: str) -> None:
        light = self.lights.get(address)
        if light is None:
            raise KeyError(address)
        if light.connected:
            await self.disconnect(address)
        light = self.lights.get(address)
        if light is None:
            return
        light.saved = False
        if not light.seen and not light.connected:
            del self.lights[address]
        self._save()

    async def update_meta(self, address: str, fields: dict) -> None:
        light = self.lights.get(address)
        if light is None:
            raise KeyError(address)
        profile_changed = False
        if "nickname" in fields:
            nickname = str(fields["nickname"]).strip()
            if nickname:
                light.nickname = nickname[:40]
        if "colorOrder" in fields:
            order = fields["colorOrder"]
            if order not in COLOR_ORDERS:
                raise ValueError("Unknown color order")
            light.color_order = order
        if "powerStyle" in fields:
            style = fields["powerStyle"]
            if style not in ("standard", "alt"):
                raise ValueError("Unknown power style")
            light.power_style = style
        if "profile" in fields and fields["profile"] != light.profile:
            profile = fields["profile"]
            if profile not in PROFILE_IDS:
                raise ValueError("Unknown profile")
            light.profile = profile
            light.profile_confident = True
            profile_changed = True
        light.saved = True
        self._save()
        if profile_changed and light.connected:
            await self.disconnect(address)
            await self.connect(address)

    async def control(self, addresses: list[str], payload: dict) -> str | None:
        if not addresses:
            raise ValueError("Select a light first")
        missing = [address for address in addresses if address not in self.lights]
        if missing:
            raise KeyError(missing[0])
        disconnected = [self.lights[address].nickname for address in addresses if not self.lights[address].connected]
        if disconnected:
            raise ValueError(f"Not connected: {', '.join(disconnected)}")
        notes: list[str] = []
        async with self._radio:
            for address in addresses:
                light = self.lights[address]
                try:
                    await self._control_one(light, payload)
                except Exception as exc:
                    light.error = str(exc)
                    notes.append(f"{light.nickname}: {exc}")
        self._save()
        return " ".join(notes) or None

    async def _control_one(self, light: Light, payload: dict) -> None:
        kind = payload.get("type")
        if kind == "power":
            turn_on = bool(payload.get("on"))
            await self._send(light, FrameRequest(kind="power", on=turn_on, power_style=light.power_style))
            light.on = turn_on
            if turn_on:
                await self._resend_look(light)
            return
        if kind == "color":
            light.r = int(payload["r"])
            light.g = int(payload["g"])
            light.b = int(payload["b"])
            light.mode = "solid"
            light.effect = None
            light.rhythm = None
            if not light.on:
                await self._send(light, FrameRequest(kind="power", on=True, power_style=light.power_style))
                light.on = True
            await self._send_color(light)
            return
        if kind == "brightness":
            light.brightness = max(0, min(100, int(payload["level"])))
            if light.mode == "rhythm":
                return
            if light.mode == "effect" and light.effect is not None and light.profile == "ks03_tilde":
                await self._send_effect(light)
            elif light.mode == "solid" or light.profile == "ks03_tilde":
                await self._send_color(light)
            else:
                await self._send_brightness(light)
            return
        if kind == "effect":
            effect = int(payload["id"])
            allowed = known_effect_ids(light.profile or "")
            if allowed is not None and effect not in allowed:
                raise ValueError("That pattern is not in this lamp's list")
            if light.profile == LEDDMX:
                effect = max(0, min(210, effect))
            light.mode = "effect"
            light.effect = effect
            light.rhythm = None
            if not light.on:
                await self._send(light, FrameRequest(kind="power", on=True, power_style=light.power_style))
                light.on = True
            await self._send_effect(light)
            return
        if kind == "speed":
            light.speed = max(0, min(100, int(payload["level"])))
            if light.profile == "ks03_tilde":
                if light.mode == "effect" and light.effect is not None:
                    await self._send_effect(light)
                return
            await self._send(light, FrameRequest(kind="speed", speed=light.speed))
            return
        if kind == "rhythm":
            if light.profile != "ks03_tilde":
                raise ValueError("Microphone patterns are only on newer KS Light lamps")
            light.rhythm = max(1, min(4, int(payload["mode"])))
            light.sensitivity = max(1, min(8, int(payload.get("sensitivity", light.sensitivity))))
            light.mode = "rhythm"
            light.effect = None
            if not light.on:
                await self._send(light, FrameRequest(kind="power", on=True, power_style=light.power_style))
                light.on = True
            await self._send(
                light,
                FrameRequest(kind="rhythm", rhythm=light.rhythm, sensitivity=light.sensitivity),
            )
            return
        raise ValueError("Unknown control")

    async def _resend_look(self, light: Light) -> None:
        if light.mode == "effect" and light.effect is not None:
            await self._send_effect(light)
        elif light.mode == "rhythm" and light.rhythm is not None:
            await self._send(
                light,
                FrameRequest(kind="rhythm", rhythm=light.rhythm, sensitivity=light.sensitivity),
            )
        else:
            light.mode = "solid"
            await self._send_color(light)

    async def _send_color(self, light: Light) -> None:
        await self._send(light, self._visual(light, kind="color"))
        if light.profile != "ks03_tilde":
            await self._send_brightness(light)

    async def _send_brightness(self, light: Light) -> None:
        if light.profile == "ks03_tilde":
            await self._send(light, self._visual(light, kind="color"))
            return
        await self._send(light, self._visual(light, kind="brightness"))

    async def _send_effect(self, light: Light) -> None:
        await self._send(light, self._visual(light, kind="effect"))
        if light.profile in ("ks_classic", "ledble"):
            await self._send(light, self._visual(light, kind="speed"))

    def _visual(self, light: Light, kind: str) -> FrameRequest:
        return FrameRequest(
            kind=kind,
            on=light.on,
            r=light.r,
            g=light.g,
            b=light.b,
            brightness=light.brightness,
            effect=light.effect or 0,
            speed=light.speed,
            color_order=light.color_order,
            power_style=light.power_style,
        )

    async def _send(self, light: Light, request: FrameRequest) -> None:
        if light.profile is None or light.session is None:
            raise RuntimeError("Light is not connected")
        payload = encode(light.profile, request)
        await self._write_frame(light, request, payload)

    async def _write_frame(self, light: Light, request: FrameRequest, payload: bytes | None = None) -> None:
        if payload is None:
            if light.profile is None:
                return
            payload = encode(light.profile, request)
        session = light.session
        if session is None:
            raise RuntimeError("Light is not connected")
        await session.write(payload)
        light.last_command = hex_bytes(payload)
        light.error = None
        light.state_source = "sent" if request.kind != "query" else light.state_source
        print(f"{light.nickname or light.address}: {request.kind} {light.last_command}", flush=True)
        await asyncio.sleep(0.06)

    async def shutdown(self) -> None:
        for light in list(self.lights.values()):
            if light.connected:
                await self._close(light)
        self._save()


class DemoSession:
    async def write(self, payload: bytes) -> None:
        return None

    async def disconnect(self) -> None:
        return None


class BleSession:
    def __init__(self, client, char, notify, address: str, manager: LightManager) -> None:
        self.client = client
        self.char = char
        self.notify = notify
        self.address = address
        self.manager = manager
        self._response = self._needs_response(char)

    def _needs_response(self, char) -> bool:
        names = set()
        for prop in getattr(char, "properties", []) or []:
            names.add(str(prop).split(".")[-1].lower().replace("_", "-"))
        if "write-without-response" in names:
            return False
        return True

    async def start_notify(self, callback) -> None:
        if self.notify is None:
            return
        loop = asyncio.get_running_loop()

        def _handle(_char, data: bytearray) -> None:
            loop.call_soon_threadsafe(callback, self.address, bytes(data))

        await self.client.start_notify(self.notify, _handle)

    async def write(self, payload: bytes) -> None:
        await self.client.write_gatt_char(self.char, payload, response=self._response)

    async def disconnect(self) -> None:
        if self.client.is_connected:
            await self.client.disconnect()

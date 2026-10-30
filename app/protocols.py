"""Bluetooth frame builders for the two vendor apps.

KS Light (com.huaxin.smartlight) covers Keepsmile-style controllers:

- KS03~ uses service 0xAFD0 / write 0xAFD1. Power, color, and scenes were
  reconstructed from the vendor app's packets (keepsmile KS03~ notes).
- Older KS01 / KS02 / KS03- / KS04 controllers use service 0xFFF0 / write
  0xFFF3 and 9-byte 0x7E … 0xEF frames. The color frame matches captures
  from the KS vendor app; power, brightness, speed, and scene frames are
  the matching frame family used by those controllers.

LED LAMP (com.ledlamp) covers a second family on service 0xFFE0 / write
0xFFE1:

- LEDDMX / LEDCAR dream-color strips use 0x7B … 0xBF frames.
- LEDBLE / LEDSTAGE / LEDLIGHT controllers use 0x7E 0xFF … 0xEF frames.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


KS_TILDE = "ks03_tilde"
KS_CLASSIC = "ks_classic"
LEDDMX = "leddmx"
LEDBLE = "ledble"

PROFILE_IDS = (KS_TILDE, KS_CLASSIC, LEDDMX, LEDBLE)

COLOR_ORDERS = ("rgb", "rbg", "grb", "gbr", "brg", "bgr")

# Scene numbers from the KS Light app, starting at 0x80.
KS_EFFECTS: tuple[tuple[int, str], ...] = (
    (0x80, "Seven-color jump"),
    (0x81, "RGB jump"),
    (0x82, "Seven-color pulse"),
    (0x83, "RGB pulse"),
    (0x84, "Red pulse"),
    (0x85, "Green pulse"),
    (0x86, "Blue pulse"),
    (0x87, "Yellow pulse"),
    (0x88, "Cyan pulse"),
    (0x89, "Purple pulse"),
    (0x8A, "White pulse"),
    (0x8B, "Red and green pulse"),
    (0x8C, "Red and blue pulse"),
    (0x8D, "Green and blue pulse"),
    (0x8E, "Seven-color strobe"),
    (0x8F, "RGB strobe"),
    (0x90, "Red strobe"),
    (0x91, "Green strobe"),
    (0x92, "Blue strobe"),
    (0x93, "Yellow strobe"),
    (0x94, "Cyan strobe"),
    (0x95, "Purple strobe"),
    (0x96, "White strobe"),
)

# LEDBLE mode bytes captured from the LED LAMP app, 0x87 through 0x9D.
LEDBLE_EFFECTS: tuple[tuple[int, str], ...] = tuple(
    (0x87 + index, f"Pattern {index + 1}") for index in range(23)
)

RHYTHM_MODES: tuple[tuple[int, str], ...] = (
    (1, "Fade"),
    (2, "Pulse"),
    (3, "Jump"),
    (4, "Flash"),
)

WRITE_CHAR = {
    KS_TILDE: "afd1",
    KS_CLASSIC: "fff3",
    LEDDMX: "ffe1",
    LEDBLE: "ffe1",
}

NOTIFY_CHAR = {
    KS_TILDE: "afd2",
}


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, int(value)))


def _effects(pairs: tuple[tuple[int, str], ...]) -> list[dict]:
    return [{"id": effect_id, "name": name} for effect_id, name in pairs]


def profile_catalog() -> list[dict]:
    return [
        {
            "id": KS_TILDE,
            "label": "KS Light · newer KS03~",
            "app": "KS Light",
            "effects": _effects(KS_EFFECTS),
            "supportsSpeed": True,
            "supportsRhythm": True,
            "supportsEffectIndex": False,
            "effectMin": 0,
            "effectMax": 0,
        },
        {
            "id": KS_CLASSIC,
            "label": "KS Light · older KS models",
            "app": "KS Light",
            "effects": _effects(KS_EFFECTS),
            "supportsSpeed": True,
            "supportsRhythm": False,
            "supportsEffectIndex": False,
            "effectMin": 0,
            "effectMax": 0,
        },
        {
            "id": LEDDMX,
            "label": "LED LAMP · LEDDMX strip",
            "app": "LED LAMP",
            "effects": [],
            "supportsSpeed": False,
            "supportsRhythm": False,
            "supportsEffectIndex": True,
            "effectMin": 0,
            "effectMax": 210,
        },
        {
            "id": LEDBLE,
            "label": "LED LAMP · LEDBLE controller",
            "app": "LED LAMP",
            "effects": _effects(LEDBLE_EFFECTS),
            "supportsSpeed": True,
            "supportsRhythm": False,
            "supportsEffectIndex": False,
            "effectMin": 0,
            "effectMax": 0,
        },
    ]


def catalog_by_id() -> dict[str, dict]:
    return {item["id"]: item for item in profile_catalog()}


def known_effect_ids(profile: str) -> set[int] | None:
    """Return the legal effect ids, or None when any index in range is legal."""
    meta = catalog_by_id()[profile]
    if meta["supportsEffectIndex"]:
        return None
    return {effect["id"] for effect in meta["effects"]}


def permute_color(r: int, g: int, b: int, order: str) -> tuple[int, int, int]:
    if order not in COLOR_ORDERS:
        raise ValueError(f"Unknown color order: {order}")
    channels = {"r": _clamp(r, 0, 255), "g": _clamp(g, 0, 255), "b": _clamp(b, 0, 255)}
    return tuple(channels[channel] for channel in order)  # type: ignore[return-value]


def sunday_one(when: datetime) -> int:
    """Sunday = 1 … Saturday = 7, which is what KS03~ timers expect."""
    return (when.weekday() + 1) % 7 + 1


def uuid_matches(uuid: str, short: str) -> bool:
    compact = str(uuid).lower().replace("-", "")
    short = short.lower()
    if len(short) == 4:
        return compact == f"0000{short}00001000800000805f9b34fb" or compact == short
    return compact == short.replace("-", "")


def classify(name: str | None, service_uuids: list[str] | None = None) -> tuple[str | None, bool]:
    """Guess a profile from the advertised name and service UUIDs.

    The second value is True when the Bluetooth name itself identified the
    family, and False when the guess came only from a service UUID.
    """
    label = (name or "").strip().upper()
    services = " ".join(service_uuids or []).lower().replace("-", "")

    if label.startswith("KS03~"):
        return KS_TILDE, True
    if label.startswith("KS"):
        return KS_CLASSIC, True
    if label.startswith(("LEDDMX", "LEDCAR")):
        return LEDDMX, True
    if label.startswith(("LEDBLE", "LEDSTAGE", "LEDLIGHT")):
        return LEDBLE, True

    def has(short: str) -> bool:
        token = f"0000{short}00001000800000805f9b34fb"
        return token in services or short in services.split()

    if has("afd0") or has("afd1"):
        return KS_TILDE, False
    if has("ffe0") or has("ffe1"):
        return LEDDMX, False
    if has("fff0") or has("fff3"):
        return KS_CLASSIC, False
    return None, False


def warning_for(name: str | None, profile: str | None) -> str | None:
    label = (name or "").upper()
    if profile == LEDDMX and any(token in label for token in ("LEDDMX-02", "LEDDMX-04", "LEDCAR-02")):
        return (
            "This model sometimes uses a shifted LED LAMP frame. "
            "If color and power do nothing, it is a variant this app does not speak yet."
        )
    if profile == KS_CLASSIC:
        return (
            "Older KS lamps share one scene list with KS03~. "
            "A few of them ignore scene commands and still accept power, color, and brightness."
        )
    return None


@dataclass
class FrameRequest:
    kind: str
    on: bool = True
    r: int = 255
    g: int = 255
    b: int = 255
    brightness: int = 100
    effect: int = 0
    speed: int = 50
    rhythm: int = 1
    sensitivity: int = 4
    color_order: str = "rgb"
    power_style: str = "standard"
    when: datetime | None = None


def encode(profile: str, request: FrameRequest) -> bytes:
    if profile not in PROFILE_IDS:
        raise ValueError(f"Unknown profile: {profile}")
    kind = request.kind
    if profile == KS_TILDE:
        return _encode_ks_tilde(request, kind)
    if profile == KS_CLASSIC:
        return _encode_ks_classic(request, kind)
    if profile == LEDDMX:
        return _encode_leddmx(request, kind)
    return _encode_ledble(request, kind)


def _level(value: int) -> int:
    return _clamp(value, 0, 100)


def _encode_ks_tilde(request: FrameRequest, kind: str) -> bytes:
    if kind == "power":
        # On is 0xF0, off is 0x0F.
        state = 0xF0 if request.on else 0x0F
        return bytes((0x5B, state, 0x00, 0xB5))
    if kind == "color":
        r, g, b = permute_color(request.r, request.g, request.b, request.color_order)
        return bytes((0x5A, 0x00, 0x01, r, g, b, 0x00, _level(request.brightness), 0x00, 0xA5))
    if kind == "effect":
        return bytes(
            (
                0x5C,
                0x00,
                _clamp(request.effect, 0, 255),
                _level(request.speed),
                _level(request.brightness),
                0x00,
                0xC5,
            )
        )
    if kind == "rhythm":
        return bytes(
            (
                0x5A,
                0x0A,
                0xF0,
                _clamp(request.rhythm, 1, 4),
                _clamp(request.sensitivity, 1, 8),
                0xA5,
            )
        )
    if kind == "query":
        return bytes((0x5F, 0x01, 0x00, 0xF5))
    if kind == "time":
        when = request.when or datetime.now()
        return bytes(
            (
                0x7E,
                0x07,
                0x83,
                when.hour,
                when.minute,
                when.second,
                sunday_one(when),
                0xFF,
                0xEF,
            )
        )
    raise ValueError(f"KS03~ does not use a separate {kind} frame")


def _encode_ks_classic(request: FrameRequest, kind: str) -> bytes:
    if kind == "power":
        if request.on:
            return bytes((0x7E, 0x04, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF))
        return bytes((0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF))
    if kind == "color":
        r, g, b = permute_color(request.r, request.g, request.b, request.color_order)
        return bytes((0x7E, 0x07, 0x05, 0x03, r, g, b, 0x00, 0xEF))
    if kind == "brightness":
        return bytes((0x7E, 0x07, 0x01, _level(request.brightness), 0xFF, 0xFF, 0xFF, 0x00, 0xEF))
    if kind == "speed":
        return bytes((0x7E, 0x04, 0x02, _level(request.speed), 0xFF, 0xFF, 0xFF, 0x00, 0xEF))
    if kind == "effect":
        return bytes((0x7E, 0x07, 0x03, _clamp(request.effect, 0, 255), 0x03, 0xFF, 0xFF, 0x00, 0xEF))
    raise ValueError(f"Older KS lamps do not use a separate {kind} frame")


def _encode_leddmx(request: FrameRequest, kind: str) -> bytes:
    if kind == "power":
        # Standard LED LAMP / LEDDMX uses 0x03 on and 0x02 off.
        # Some controllers in the same app use 0x01 / 0x00 instead.
        if request.power_style == "alt":
            state = 0x01 if request.on else 0x00
        else:
            state = 0x03 if request.on else 0x02
        return bytes((0x7B, 0xFF, 0x04, state, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF))
    if kind == "color":
        c1, c2, c3 = permute_color(request.r, request.g, request.b, request.color_order)
        return bytes((0x7B, 0xFF, 0x07, c1, c2, c3, 0x00, 0xFF, 0xBF))
    if kind == "brightness":
        level = _level(request.brightness)
        scaled = (level * 32) // 100
        return bytes((0x7B, 0xFF, 0x01, scaled, level, 0x00, 0xFF, 0xFF, 0xBF))
    if kind == "effect":
        return bytes((0x7B, 0xFF, 0x03, _clamp(request.effect, 0, 210), 0xFF, 0xFF, 0xFF, 0xFF, 0xBF))
    raise ValueError(f"LEDDMX does not use a separate {kind} frame")


def _encode_ledble(request: FrameRequest, kind: str) -> bytes:
    if kind == "power":
        state = 0x01 if request.on else 0x00
        return bytes((0x7E, 0xFF, 0x04, state, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF))
    if kind == "color":
        r, g, b = permute_color(request.r, request.g, request.b, request.color_order)
        return bytes((0x7E, 0xFF, 0x05, 0x03, r, g, b, 0xFF, 0xEF))
    if kind == "brightness":
        return bytes((0x7E, 0xFF, 0x01, _level(request.brightness), 0x00, 0xFF, 0xFF, 0xFF, 0xEF))
    if kind == "speed":
        return bytes((0x7E, 0xFF, 0x02, _level(request.speed), 0x00, 0xFF, 0xFF, 0xFF, 0xEF))
    if kind == "effect":
        return bytes((0x7E, 0xFF, 0x03, _clamp(request.effect, 0, 255), 0x03, 0xFF, 0xFF, 0xFF, 0xEF))
    raise ValueError(f"LEDBLE does not use a separate {kind} frame")


def parse_ks03_notification(payload: bytes) -> dict | None:
    """Parse a KS03~ state notification, if this packet is one."""
    if len(payload) < 12 or payload[0] != 0x5F or payload[1] != 0x06 or payload[2] != 0x01:
        return None
    power_byte = payload[3]
    on: bool | None
    if power_byte == 0xF0:
        on = True
    elif power_byte == 0x0F:
        on = False
    else:
        on = None
    return {
        "on": on,
        "r": payload[4],
        "g": payload[5],
        "b": payload[6],
        "brightness": _clamp(payload[8], 0, 100),
        "speed": _clamp(100 - payload[9], 0, 100),
    }


def hex_bytes(payload: bytes) -> str:
    return " ".join(f"{byte:02X}" for byte in payload)

"""Saved light names and last-used settings."""

from __future__ import annotations

import json
from pathlib import Path


def load_lights(path: Path) -> list[dict]:
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict) and item.get("address")]


def save_lights(path: Path, lights: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(lights, indent=2), encoding="utf-8")

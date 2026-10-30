"""Run the local light controller and open it in a browser."""

from __future__ import annotations

import argparse
import asyncio
import webbrowser
from pathlib import Path

from aiohttp import web

from app.manager import LightManager
from app.server import create_app

ROOT = Path(__file__).resolve().parent.parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Control KS Light and LED LAMP bulbs over Bluetooth.")
    parser.add_argument("--demo", action="store_true", help="Run with sample lights and no Bluetooth radio.")
    parser.add_argument("--host", default="127.0.0.1", help="Address to listen on. Use 0.0.0.0 to open the page from a phone on the same Wi-Fi.")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true", help="Do not open a browser window.")
    return parser.parse_args()


async def serve(args: argparse.Namespace) -> None:
    manager = LightManager(ROOT / "data" / "lights.json", demo=args.demo)
    app = create_app(manager)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    try:
        await site.start()
    except OSError as exc:
        print(f"Could not listen on {args.host}:{args.port}. {exc}", flush=True)
        await runner.cleanup()
        raise SystemExit(1) from exc

    shown_host = "127.0.0.1" if args.host in ("0.0.0.0", "::") else args.host
    url = f"http://{shown_host}:{args.port}"
    print(f"Lights is running at {url}", flush=True)
    if args.demo:
        print("Demo mode is on. No Bluetooth commands will be sent.", flush=True)
    else:
        print("Leave this window open. Closing it disconnects the lights.", flush=True)
        print("Close the KS Light and LED LAMP phone apps before connecting.", flush=True)
    if args.host == "0.0.0.0":
        print("This page is available to other devices on your network.", flush=True)
    if not args.no_browser:
        webbrowser.open(url)

    try:
        await asyncio.Event().wait()
    finally:
        await manager.shutdown()
        await runner.cleanup()


def main() -> None:
    args = parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        print("\nStopped.", flush=True)


if __name__ == "__main__":
    main()

"""HTTP server for the local light controller."""

from __future__ import annotations

from pathlib import Path

from aiohttp import web

from app.manager import LightManager

WEB = Path(__file__).resolve().parent.parent / "web"


@web.middleware
async def _no_cache(request: web.Request, handler):
    response = await handler(request)
    response.headers["Cache-Control"] = "no-store"
    return response


def create_app(manager: LightManager) -> web.Application:
    app = web.Application(middlewares=[_no_cache])
    app["manager"] = manager
    app.router.add_get("/", _index)
    app.router.add_get("/styles.css", _styles)
    app.router.add_get("/app.js", _script)
    app.router.add_get("/api/state", _state)
    app.router.add_post("/api/scan", _scan)
    app.router.add_post("/api/connect", _connect)
    app.router.add_post("/api/disconnect", _disconnect)
    app.router.add_post("/api/forget", _forget)
    app.router.add_post("/api/meta", _meta)
    app.router.add_post("/api/control", _control)
    return app


def _manager(request: web.Request) -> LightManager:
    return request.app["manager"]


def _json(data: dict, status: int = 200) -> web.Response:
    response = web.json_response(data, status=status)
    response.headers["Cache-Control"] = "no-store"
    return response


async def _read_json(request: web.Request) -> dict:
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(text="Expected JSON") from exc
    if not isinstance(body, dict):
        raise web.HTTPBadRequest(text="Expected a JSON object")
    return body


async def _index(_request: web.Request) -> web.FileResponse:
    return web.FileResponse(WEB / "index.html", headers={"Content-Type": "text/html; charset=utf-8"})


async def _styles(_request: web.Request) -> web.FileResponse:
    return web.FileResponse(WEB / "styles.css", headers={"Content-Type": "text/css; charset=utf-8"})


async def _script(_request: web.Request) -> web.FileResponse:
    return web.FileResponse(WEB / "app.js", headers={"Content-Type": "text/javascript; charset=utf-8"})


async def _state(request: web.Request) -> web.Response:
    return _json(_manager(request).snapshot())


async def _scan(request: web.Request) -> web.Response:
    body = await _read_json(request)
    seconds = float(body.get("seconds") or 6)
    seconds = max(2, min(12, seconds))
    await _manager(request).scan(seconds)
    return _json(_manager(request).snapshot())


async def _connect(request: web.Request) -> web.Response:
    body = await _read_json(request)
    address = str(body.get("address") or "")
    try:
        await _manager(request).connect(address, body.get("profile"))
    except KeyError:
        return _json({"error": "That light is not in the list. Scan again."}, status=404)
    except (ValueError, RuntimeError) as exc:
        snap = _manager(request).snapshot()
        snap["error"] = str(exc)
        return _json(snap, status=400)
    return _json(_manager(request).snapshot())


async def _disconnect(request: web.Request) -> web.Response:
    body = await _read_json(request)
    try:
        await _manager(request).disconnect(str(body.get("address") or ""))
    except KeyError:
        return _json({"error": "That light is not in the list."}, status=404)
    return _json(_manager(request).snapshot())


async def _forget(request: web.Request) -> web.Response:
    body = await _read_json(request)
    try:
        await _manager(request).forget(str(body.get("address") or ""))
    except KeyError:
        return _json({"error": "That light is not in the list."}, status=404)
    return _json(_manager(request).snapshot())


async def _meta(request: web.Request) -> web.Response:
    body = await _read_json(request)
    address = str(body.get("address") or "")
    try:
        await _manager(request).update_meta(address, body)
    except KeyError:
        return _json({"error": "That light is not in the list."}, status=404)
    except (ValueError, RuntimeError) as exc:
        snap = _manager(request).snapshot()
        snap["error"] = str(exc)
        return _json(snap, status=400)
    return _json(_manager(request).snapshot())


async def _control(request: web.Request) -> web.Response:
    body = await _read_json(request)
    addresses = body.get("addresses") or []
    if not isinstance(addresses, list):
        return _json({"error": "addresses must be a list"}, status=400)
    try:
        warning = await _manager(request).control([str(item) for item in addresses], body)
    except KeyError:
        return _json({"error": "That light is not in the list."}, status=404)
    except ValueError as exc:
        return _json({"error": str(exc)}, status=400)
    snap = _manager(request).snapshot()
    if warning:
        snap["warning"] = warning
    return _json(snap)

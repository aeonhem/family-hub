"""Smart Life lights through Tuya's cloud API, for the family's phones.

The lights are added in the Smart Life app; a Tuya IoT cloud project linked to
that Smart Life account lets this server switch them, dim them, change their
colour or white, and start a scene. Turned on by putting the project's keys at
STATE_DIR/tuya.json (see web/README.md). Blocking calls: run them off the
event loop.
"""
from __future__ import annotations

import colorsys
import hashlib
import hmac
import json
import logging
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlencode

log = logging.getLogger("familyhub-web.lights")

# The on/off code each kind of device uses: bulbs and strips, then plugs and switches.
SWITCH_CODES = ("switch_led", "switch_1", "switch")
# Brightness codes with Tuya's usual range for each (newer bulbs use v2).
BRIGHTNESS = {"bright_value_v2": (10, 1000), "bright_value": (25, 255)}
TOKEN_INVALID = {1010, 1011}  # token expired or not valid: get a new one and retry once


def _white(bright: int, temperature: int) -> dict:
    return {"bright": bright, "h": 0, "s": 0, "temperature": temperature, "v": 0}


def _colour(h: int, s: int = 1000, v: int = 1000) -> dict:
    return {"bright": 0, "h": h, "s": s, "temperature": 0, "v": v}


def _scene(num: int, change: str, speed: int, units: list[dict]) -> dict:
    """scene_data_v2 for a bulb. [speed] is Tuya's 0-100 duration for each step."""
    return {"scene_num": num, "scene_units": [
        {**u, "unit_change_mode": change, "unit_gradient_duration": speed, "unit_switch_duration": speed}
        for u in units]}


# Family Hub's own scenes, played by the bulb itself (its "scene" mode), so
# they work on every colour bulb without anything set up in Smart Life.
SCENES = {
    "night": ("Night", _scene(1, "static", 14, [_white(200, 0)])),
    "reading": ("Reading", _scene(2, "static", 14, [_white(1000, 500)])),
    "relax": ("Relax", _scene(3, "static", 14, [_white(500, 100)])),
    "sunset": ("Sunset", _scene(4, "gradient", 60, [_colour(20, 1000, 900), _colour(0, 900, 700),
                                                    _colour(330, 800, 600), _colour(35, 1000, 800)])),
    "ocean": ("Ocean", _scene(5, "gradient", 70, [_colour(190, 900, 800), _colour(220, 1000, 700),
                                                  _colour(170, 800, 800)])),
    "rainbow": ("Rainbow", _scene(6, "gradient", 40, [_colour(h) for h in (0, 60, 120, 180, 240, 300)])),
}


class LightsError(Exception):
    pass


def string_to_sign(method: str, path: str, query: dict | None, body: bytes) -> str:
    """Tuya's signing string: method, body SHA-256, (no signed headers), and the
    path with its query sorted by key. Tuya signs the query as written, not
    URL-encoded (so "a,b" stays "a,b")."""
    url = path + ("?" + "&".join(f"{k}={v}" for k, v in sorted(query.items())) if query else "")
    return "\n".join([method, hashlib.sha256(body).hexdigest(), "", url])


def sign(access_id: str, secret: str, token: str, t: str, nonce: str, to_sign: str) -> str:
    """HMAC-SHA256 of id + token + t + nonce + stringToSign, upper case. The
    token request leaves the token out (an empty string)."""
    msg = access_id + token + t + nonce + to_sign
    return hmac.new(secret.encode(), msg.encode(), hashlib.sha256).hexdigest().upper()


def to_percent(value: int, code: str) -> int:
    lo, hi = BRIGHTNESS[code]
    return max(1, min(100, round(1 + (value - lo) * 99 / (hi - lo))))


def from_percent(percent: int, code: str) -> int:
    lo, hi = BRIGHTNESS[code]
    return round(lo + (max(1, min(100, percent)) - 1) * (hi - lo) / 99)


def hex_to_hs(colour: str) -> tuple[int, int]:
    """'#ff8800' -> Tuya hue 0-360 and saturation 0-1000. Brightness is set separately."""
    r, g, b = (int(colour[i:i + 2], 16) / 255 for i in (1, 3, 5))
    h, s, _ = colorsys.rgb_to_hsv(r, g, b)
    return round(h * 360) % 360, round(s * 1000)


def hs_to_hex(h: int, s: int) -> str:
    """The colour at full brightness, for the swatch on screen."""
    r, g, b = colorsys.hsv_to_rgb(h / 360, s / 1000, 1)
    return "#" + "".join(f"{round(c * 255):02x}" for c in (r, g, b))


def valid_hex(value) -> bool:
    return isinstance(value, str) and len(value) == 7 and value[0] == "#" and \
        all(c in "0123456789abcdefABCDEF" for c in value[1:])


def light_json(device: dict) -> dict | None:
    """A Smart Life device cut down to what the Lights tab shows, plus the Tuya
    codes it uses (dropped before it goes to a phone), or None if it can't be
    switched (sensors, cameras and so on)."""
    status = {s["code"]: s["value"] for s in device.get("status", [])}
    switch = next((c for c in SWITCH_CODES if c in status), None)
    if not switch:
        return None
    dim = next((c for c in BRIGHTNESS if c in status), None)
    hsv = None
    if "colour_data_v2" in status:
        try:
            raw = status["colour_data_v2"]
            hsv = {k: int(v) for k, v in (json.loads(raw) if isinstance(raw, str) else raw).items() if k in "hsv"}
        except (ValueError, TypeError, AttributeError):
            hsv = None
    mode = status.get("work_mode") if isinstance(status.get("work_mode"), str) else None
    brightness = to_percent(int(status[dim]), dim) if dim else None
    if mode == "colour" and hsv:
        brightness = to_percent(max(10, hsv["v"]), "bright_value_v2")
    scene = None
    if mode == "scene" and isinstance(status.get("scene_data_v2"), str):
        try:
            num = json.loads(status["scene_data_v2"]).get("scene_num")
            scene = next((k for k, (_, data) in SCENES.items() if data["scene_num"] == num), None)
        except ValueError:
            pass
    temp = status.get("temp_value_v2")
    return {
        "id": device["id"],
        "name": device.get("name") or "Light",
        "online": bool(device.get("online")),
        "on": bool(status[switch]),
        "brightness": brightness,
        "mode": mode,
        "color": hs_to_hex(hsv["h"], hsv["s"]) if hsv else None,
        "temp": round(int(temp) / 10) if temp is not None else None,  # 0 warm .. 100 cool
        "scene": scene,
        "can": {"color": hsv is not None and mode is not None, "temp": temp is not None,
                "scenes": "scene_data_v2" in status and mode is not None},
        "switch": switch,
        "dim": dim,
        "hsv": hsv,
    }


def with_sent(device: dict, sent: tuple[float, dict] | None) -> dict:
    """The device with what this server last sent it laid over its reported
    status, if that was sent after the device's last update."""
    if not sent or sent[0] <= float(device.get("update_time") or 0):
        return device
    values = {k: json.dumps(v) if isinstance(v, dict) else v for k, v in sent[1].items()}
    status = [{**s, "value": values.get(s["code"], s["value"])} for s in device.get("status", [])]
    return {**device, "status": status}


def public(light: dict) -> dict:
    """What a phone sees: no Tuya codes."""
    return {k: v for k, v in light.items() if k not in ("switch", "dim", "hsv")}


def commands_for(light: dict, on=None, brightness=None, color=None, temp=None, scene=None) -> list[dict]:
    """Tuya commands for a change. Colour, white temperature and a scene each
    put the bulb in that mode; brightness follows the mode it's in."""
    cmds = []
    if on is not None:
        cmds.append({"code": light["switch"], "value": on})
    mode = light.get("mode")
    if color is not None and light["can"]["color"]:
        h, s = hex_to_hs(color)
        v = (light["hsv"] or {}).get("v", 1000)
        if brightness is not None:
            v = from_percent(brightness, "bright_value_v2")
        cmds += [{"code": "work_mode", "value": "colour"}, {"code": "colour_data_v2", "value": {"h": h, "s": s, "v": v}}]
        return cmds
    if scene is not None and light["can"]["scenes"]:
        cmds += [{"code": "work_mode", "value": "scene"}, {"code": "scene_data_v2", "value": SCENES[scene][1]}]
        return cmds
    if temp is not None and light["can"]["temp"]:
        cmds += [{"code": "work_mode", "value": "white"}, {"code": "temp_value_v2", "value": max(0, min(1000, temp * 10))}]
        mode = "white"
    if brightness is not None:
        if mode == "colour" and light["hsv"]:
            hsv = {**light["hsv"], "v": from_percent(brightness, "bright_value_v2")}
            cmds.append({"code": "colour_data_v2", "value": hsv})
        elif light["dim"]:
            if mode == "scene" and light["can"]["temp"] and temp is None:
                # Dimming leaves a scene for plain white, like the Smart Life app.
                cmds.append({"code": "work_mode", "value": "white"})
            cmds.append({"code": light["dim"], "value": from_percent(brightness, light["dim"])})
    return cmds


class Lights:
    def __init__(self, key_file: Path):
        keys = json.loads(key_file.read_text())
        self.endpoint = keys["endpoint"].rstrip("/")  # e.g. https://openapi.tuyaeu.com, the project's data centre
        self.access_id = keys["access_id"]
        self.secret = keys["access_secret"]
        self.uid = keys["uid"]  # the linked Smart Life account, from the project's Devices > Link App Account
        self.token, self.token_expires = "", 0.0
        self.known: dict[str, dict] = {}  # last list, so a command knows each device's codes and mode
        # What this server last sent each device: {id: (unix time, {code: value})}. Some
        # bulbs act on commands but stop reporting their state to Tuya (the bedroom
        # lamp did, 2026-11-03), so this shows until the device reports something newer.
        self.sent: dict[str, tuple[float, dict]] = {}
        self.lock = threading.Lock()

    def _request(self, method: str, path: str, query: dict | None = None, body: dict | None = None,
                 token: str | None = None) -> dict:
        raw = json.dumps(body).encode() if body is not None else b""
        t, nonce = str(int(time.time() * 1000)), uuid.uuid4().hex
        token = self._token() if token is None else token
        headers = {
            "client_id": self.access_id, "t": t, "nonce": nonce, "sign_method": "HMAC-SHA256",
            "sign": sign(self.access_id, self.secret, token, t, nonce, string_to_sign(method, path, query, raw)),
            "Content-Type": "application/json",
        }
        if token:
            headers["access_token"] = token
        url = self.endpoint + path + ("?" + urlencode(sorted(query.items())) if query else "")
        req = urllib.request.Request(url, data=raw or None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                reply = json.loads(resp.read())
        except urllib.error.HTTPError as e:
            raise LightsError(f"Smart Life {e.code}") from e
        except urllib.error.URLError as e:
            raise LightsError("Can't reach Smart Life right now") from e
        if not reply.get("success"):
            raise LightsError(f"Smart Life said: {reply.get('msg') or reply.get('code')}", reply.get("code"))
        return reply["result"]

    def _token(self) -> str:
        if not self.token or time.time() > self.token_expires - 60:
            result = self._request("GET", "/v1.0/token", {"grant_type": 1}, token="")
            self.token, self.token_expires = result["access_token"], time.time() + int(result["expire_time"])
        return self.token

    def _call(self, *args, **kwargs) -> dict:
        with self.lock:
            try:
                return self._request(*args, **kwargs)
            except LightsError as e:
                if len(e.args) < 2 or e.args[1] not in TOKEN_INVALID:
                    raise
                self.token = ""
                return self._request(*args, **kwargs)

    def list(self) -> list[dict]:
        devices = self._call("GET", f"/v1.0/users/{self.uid}/devices")
        lights = [j for j in (light_json(with_sent(d, self.sent.get(d.get("id")))) for d in devices) if j]
        self.known = {j["id"]: j for j in lights}
        return sorted(lights, key=lambda j: j["name"].lower())

    def set(self, device_id: str, **change) -> None:
        if device_id not in self.known:
            self.list()
        light = self.known.get(device_id)
        if not light:
            raise KeyError(device_id)
        cmds = commands_for(light, **change)
        if cmds:
            self._call("POST", f"/v1.0/iot-03/devices/{device_id}/commands", body={"commands": cmds})
            _, before = self.sent.get(device_id, (0, {}))
            self.sent[device_id] = (time.time(), {**before, **{c["code"]: c["value"] for c in cmds}})
            # Remember the new mode, so the next brightness change goes to the right place.
            for c in cmds:
                if c["code"] == "work_mode":
                    light["mode"] = c["value"]
                elif c["code"] == "colour_data_v2":
                    light["hsv"] = c["value"]


if __name__ == "__main__":
    # Try the keys from a PC before putting them on the server:
    #   python lights.py tuya.json                          lists the lights
    #   python lights.py tuya.json "lamp" on|off|50|#ff8800|warm|cool|<scene>
    #                                                       changes the first name match
    import sys

    home = Lights(Path(sys.argv[1]))
    found = home.list()
    if len(sys.argv) < 4:
        for j in found:
            print(f"{j['name']}: {'online' if j['online'] else 'OFFLINE'}, {'on' if j['on'] else 'off'}, "
                  f"{j['brightness']}%, mode {j['mode']}, colour {j['color']}, white {j['temp']}, scene {j['scene']}")
        print(f"{len(found)} light(s). Scenes: {', '.join(SCENES)}")
    else:
        match = next(j for j in found if j["name"].lower() == sys.argv[2].lower()) \
            if any(j["name"].lower() == sys.argv[2].lower() for j in found) \
            else next(j for j in found if sys.argv[2].lower() in j["name"].lower())
        what = sys.argv[3].lower()
        if what in ("on", "off"):
            home.set(match["id"], on=what == "on")
        elif valid_hex(what):
            home.set(match["id"], color=what)
        elif what in ("warm", "cool"):
            home.set(match["id"], temp=0 if what == "warm" else 100)
        elif what in SCENES:
            home.set(match["id"], scene=what)
        else:
            home.set(match["id"], brightness=int(what))
        print(f"Sent {what} to {match['name']}")

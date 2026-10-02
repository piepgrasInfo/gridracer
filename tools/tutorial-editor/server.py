#!/usr/bin/env python3
"""The tutorial editor: a local web GUI for authoring How-to-play walkthroughs.

Run it and open the printed URL:

    python3 tools/tutorial-editor/server.py

The browser gets `web/`, which renders a scene the way the app renders it and lets you move
every piece of it around. This process owns the files: it reads and writes `projects/*.json`
and runs `codegen.py` when you export. It is a development tool, so it binds to localhost only
and speaks no authentication - do not put it on a network.

Endpoints (all JSON):
    GET    /api/meta                what element types exist, the palette, the defaults
    GET    /api/projects            the project list
    POST   /api/projects            create one   {"id": ..., "name": ...}
    GET    /api/projects/<id>       read one
    PUT    /api/projects/<id>       write one; replies with validation warnings
    DELETE /api/projects/<id>       delete one
    POST   /api/export              generate Kotlin  {"ids": [...], "install": bool}
"""

from __future__ import annotations

import argparse
import http.server
import json
import mimetypes
import pathlib
import re
import socketserver
import sys
import webbrowser

sys.path.insert(0, str(pathlib.Path(__file__).parent))

import codegen  # noqa: E402
import scene  # noqa: E402

HERE = pathlib.Path(__file__).parent
WEB = HERE / "web"
PROJECTS = HERE / "projects"

ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


class EditorHandler(http.server.SimpleHTTPRequestHandler):
    """Serves `web/` and the project API. One handler, because the API is seven routes."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB), **kwargs)

    # -- plumbing ---------------------------------------------------------

    def log_message(self, fmt, *args):  # noqa: A003 - base class name
        if "/api/" in (args[0] if args else ""):
            super().log_message(fmt, *args)

    def _send_json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def _project_path(self, project_id: str) -> pathlib.Path:
        if not ID_PATTERN.match(project_id or ""):
            raise ValueError(f"{project_id!r} is not a valid project id (a-z, 0-9 and _)")
        return PROJECTS / f"{project_id}.json"

    # -- routes -----------------------------------------------------------

    def _send_index(self):
        """`index.html` with the first payload inlined, so the editor opens in one round trip."""
        ids = sorted(path.stem for path in PROJECTS.glob("*.json"))
        listing = [self._summary(project_id) for project_id in ids]
        first = None
        if ids:
            first = json.loads((PROJECTS / f"{ids[0]}.json").read_text(encoding="utf-8"))
        payload = json.dumps({"meta": self._meta(), "projects": listing, "project": first})
        html = (WEB / "index.html").read_text(encoding="utf-8").replace(
            '<script id="bootstrap" type="application/json">null</script>',
            f'<script id="bootstrap" type="application/json">{payload}</script>',
        )
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _meta(self) -> dict:
        return {
            "elementTypes": list(scene.ELEMENT_TYPES),
            "elementHelp": scene.ELEMENT_HELP,
            "colors": {name: hexes[1] for name, hexes in scene.NAMED_COLORS.items()},
            "cellModes": list(scene.CELL_MODES),
            "originModes": list(scene.ORIGIN_MODES),
            "pickerStyles": list(scene.PICKER_STYLES),
            "projectorColors": list(scene.PROJECTOR_COLORS),
            "trailKinds": list(scene.TRAIL_KINDS),
            "defaultGrid": scene.DEFAULT_GRID,
            "appPackage": str(codegen.APP_PACKAGE),
            "outDir": str(codegen.OUT),
        }

    def _summary(self, project_id: str) -> dict:
        data = json.loads((PROJECTS / f"{project_id}.json").read_text(encoding="utf-8"))
        return {
            "id": data.get("id", project_id),
            "name": data.get("name", project_id),
            "symbol": data.get("symbol", ""),
            "frames": len(data.get("frames") or []),
            "elements": len(data.get("elements") or []),
        }

    def do_GET(self):  # noqa: N802 - base class name
        if self.path in ("/", "/index.html"):
            return self._send_index()
        if not self.path.startswith("/api/"):
            return super().do_GET()
        try:
            if self.path == "/api/meta":
                return self._send_json(self._meta())
            if self.path == "/api/projects":
                return self._send_json(
                    [self._summary(path.stem) for path in sorted(PROJECTS.glob("*.json"))]
                )
            if self.path.startswith("/api/projects/"):
                path = self._project_path(self.path.rsplit("/", 1)[-1])
                if not path.exists():
                    return self._send_json({"error": "no such project"}, 404)
                return self._send_json(json.loads(path.read_text(encoding="utf-8")))
        except Exception as error:  # noqa: BLE001 - the browser should see the message
            return self._send_json({"error": str(error)}, 400)
        return self._send_json({"error": "not found"}, 404)

    def do_POST(self):  # noqa: N802
        try:
            payload = self._read_json()
            if self.path == "/api/projects":
                project_id = payload.get("id", "")
                path = self._project_path(project_id)
                if path.exists():
                    return self._send_json({"error": f"{project_id} already exists"}, 409)
                project = payload.get("project") or scene.new_project(
                    project_id, payload.get("name") or project_id
                )
                project["id"] = project_id
                scene.validate(project)
                path.write_text(json.dumps(project, indent=2, ensure_ascii=False) + "\n", "utf-8")
                return self._send_json(project, 201)
            if self.path == "/api/export":
                ids = payload.get("ids") or sorted(p.stem for p in PROJECTS.glob("*.json"))
                install = bool(payload.get("install"))
                target = codegen.APP_PACKAGE if install else codegen.OUT
                target.mkdir(parents=True, exist_ok=True)
                written = []
                for project_id in ids:
                    project = codegen.load(project_id)
                    source = codegen.generate(project)
                    out_path = target / f"{project['symbol']}Animation.kt"
                    out_path.write_text(source, encoding="utf-8")
                    written.append(str(out_path))
                return self._send_json({"written": written, "install": install})
        except Exception as error:  # noqa: BLE001
            return self._send_json({"error": str(error)}, 400)
        return self._send_json({"error": "not found"}, 404)

    def do_PUT(self):  # noqa: N802
        try:
            if not self.path.startswith("/api/projects/"):
                return self._send_json({"error": "not found"}, 404)
            project_id = self.path.rsplit("/", 1)[-1]
            path = self._project_path(project_id)
            project = self._read_json()
            project["id"] = project_id
            warnings = scene.validate(project)
            path.write_text(json.dumps(project, indent=2, ensure_ascii=False) + "\n", "utf-8")
            return self._send_json({"saved": str(path), "warnings": warnings})
        except Exception as error:  # noqa: BLE001
            return self._send_json({"error": str(error)}, 400)

    def do_DELETE(self):  # noqa: N802
        try:
            if not self.path.startswith("/api/projects/"):
                return self._send_json({"error": "not found"}, 404)
            path = self._project_path(self.path.rsplit("/", 1)[-1])
            if path.exists():
                path.unlink()
            return self._send_json({"deleted": str(path)})
        except Exception as error:  # noqa: BLE001
            return self._send_json({"error": str(error)}, 400)

    def end_headers(self):
        if not self.path.startswith("/api/"):
            self.send_header("Cache-Control", "no-store")
        super().end_headers()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true", help="do not open a browser window")
    args = parser.parse_args()

    mimetypes.add_type("application/javascript", ".js")
    mimetypes.add_type("text/css", ".css")
    PROJECTS.mkdir(exist_ok=True)

    url = f"http://127.0.0.1:{args.port}/"
    with Server(("127.0.0.1", args.port), EditorHandler) as httpd:
        print(f"GridRacer tutorial editor: {url}")
        print(f"  projects: {PROJECTS}")
        print(f"  export:   {codegen.OUT}  (--install writes to {codegen.APP_PACKAGE})")
        print("  Ctrl-C to stop")
        if not args.no_browser:
            try:
                webbrowser.open(url)
            except Exception:  # noqa: BLE001 - a headless box simply has no browser
                pass
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

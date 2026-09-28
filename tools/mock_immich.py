#!/usr/bin/env python3
"""Minimal Immich API mock for testing ImmichSAF without a real server.

Implements the subset used by the app, shaped per the Immich 3.2.0 OpenAPI
spec: /api/server/about, /api/albums, /api/search/metadata,
/api/assets/{id}/original, /api/assets/{id}/thumbnail.

Usage: python3 tools/mock_immich.py [port] [api-key]
"""
import json
import re
import struct
import sys
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2283
API_KEY = sys.argv[2] if len(sys.argv) > 2 else "mock-key"

ALBUMS = [
    {"id": str(uuid.uuid4()), "albumName": "测试相册 A", "assetCount": 6},
    {"id": str(uuid.uuid4()), "albumName": "测试相册 B", "assetCount": 4},
]

def make_png(width, height, rgb):
    """Valid PNG via zlib, no external deps."""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))

ASSETS = []
for i in range(10):
    album = ALBUMS[0] if i < 6 else ALBUMS[1]
    ASSETS.append({
        "id": str(uuid.uuid4()),
        "albumId": album["id"],
        "type": "IMAGE",
        "originalFileName": f"IMG_{2000 + i}.png",
        "fileCreatedAt": f"2026-09-{10 + i:02d}T12:00:00.000Z",
        "fileModifiedAt": f"2026-09-{10 + i:02d}T12:30:00.000Z",
        "size": (i + 1) * 4096,
        "color": (40 + i * 20, 90 + i * 10, 200 - i * 15),
    })

def asset_json(a):
    return {
        "id": a["id"],
        "type": a["type"],
        "originalFileName": a["originalFileName"],
        "fileCreatedAt": a["fileCreatedAt"],
        "fileModifiedAt": a["fileModifiedAt"],
        "exifInfo": {"fileSizeInByte": str(a["size"])},
    }

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_auth(self):
        if self.headers.get("x-api-key") != API_KEY:
            self._json({"message": "Invalid API key", "statusCode": 401}, 401)
            return False
        return True

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        if path == "/api/server/about":
            return self._json({"version": "3.2.0-mock", "versionUrl": ""})
        if not self._check_auth():
            return
        if path == "/api/albums":
            return self._json([
                {**a, "createdAt": "2026-09-01T00:00:00.000Z",
                 "updatedAt": "2026-09-20T00:00:00.000Z", "assetCount": a["assetCount"]}
                for a in ALBUMS
            ])
        m = re.match(r"^/api/assets/([0-9a-f-]+)/(original|thumbnail)$", path)
        if m:
            asset = next((a for a in ASSETS if a["id"] == m.group(1)), None)
            if not asset:
                return self._json({"message": "Not found"}, 404)
            small = m.group(2) == "thumbnail"
            size = parse_qs(url.query).get("size", ["thumbnail"])[0]
            px = {"thumbnail": 250, "preview": 640}.get(size, 300)
            data = make_png(px, px, asset["color"])
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self._json({"message": "Not implemented: " + path}, 404)

    def do_POST(self):
        if not self._check_auth():
            return
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        if urlparse(self.path).path == "/api/search/metadata":
            album_ids = body.get("albumIds")
            size = int(body.get("size", 250))
            page = int(body.get("page", 1))
            items = [a for a in ASSETS if not album_ids or a["albumId"] in album_ids]
            start = (page - 1) * size
            page_items = items[start:start + size]
            return self._json({"assets": {
                "total": len(items), "count": len(page_items),
                "items": [asset_json(a) for a in page_items],
                "nextPage": str(page + 1) if start + size < len(items) else None,
            }})
        self._json({"message": "Not implemented"}, 404)

if __name__ == "__main__":
    print(f"mock Immich on 0.0.0.0:{PORT}, api key: {API_KEY}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

#!/usr/bin/env bash
# Base44 preview entrypoint: build the Android project, (re)generate the
# Roborazzi Compose screenshot(s), then serve them on port 3000.
set -euo pipefail

cd /workspace

echo "=== Building project and generating Roborazzi screenshots ==="
gradle :app:testDebugUnitTest \
  --tests "com.example.GreetingScreenshotTest" \
  --no-daemon --stacktrace

echo "=== Generating preview index ==="
SHOTS_DIR="app/src/test/screenshots"
mkdir -p "$SHOTS_DIR"

# Build a simple gallery page listing every generated screenshot.
{
  echo "<!doctype html><html><head><meta charset=\"utf-8\">"
  echo "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
  echo "<title>Sndmart — Compose UI preview</title>"
  echo "<style>"
  echo "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0f1115;color:#e6e6e6;}"
  echo "header{padding:24px 32px;border-bottom:1px solid #23262d;background:#11141a;position:sticky;top:0}"
  echo "header h1{margin:0;font-size:20px;font-weight:600}"
  echo "header p{margin:6px 0 0;color:#8a8f99;font-size:13px}"
  echo "main{padding:32px;display:flex;flex-direction:column;gap:28px}"
  echo ".shot{background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.4)}"
  echo ".shot img{display:block;width:100%;height:auto}"
  echo ".shot .cap{padding:12px 16px;color:#1a1a1a;font-size:13px;border-top:1px solid #eee}"
  echo "</style></head><body>"
  echo "<header><h1>Sndmart — Compose UI preview</h1>"
  echo "<p>Native Android (Kotlin + Jetpack Compose) app. This is a Roborazzi render of the actual Compose UI, served on port 3000. The app itself runs on an Android device/emulator, not in a browser.</p></header>"
  echo "<main>"
  for img in "$SHOTS_DIR"/*.png; do
    [ -f "$img" ] || continue
    name="$(basename "$img")"
    echo "<div class=\"shot\"><img src=\"$name\" alt=\"$name\"><div class=\"cap\">$name</div></div>"
  done
  echo "</main></body></html>"
} > "$SHOTS_DIR/index.html"

echo "=== Serving screenshots on port 3000 ==="
cd "$SHOTS_DIR"
exec python3 -m http.server 3000 --bind 0.0.0.0

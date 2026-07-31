# Temi Dashboard

A locally-hosted web dashboard for monitoring and controlling a [Temi](https://www.robotemi.com/) robot, built during my internship at Robopreneur. The robot streams live telemetry to a browser and accepts commands back — navigation, speech, driving, camera capture, video playback, and a Claude-powered voice assistant — all over MQTT and HTTP on the local network, no cloud dependency.

## Architecture

```
┌──────────────────┐   MQTT/TCP :1883    ┌───────────────────┐   MQTT/WebSocket :9001   ┌──────────────────┐
│   Temi robot       │ ◀─────────────────▶│  Mosquitto broker   │◀────────────────────────▶│  Web dashboard    │
│  (bridge app,       │                    │  (runs on laptop)    │                          │  (browser)         │
│   Kotlin/Android)   │                    └───────────────────┘                          └──────────────────┘
│                     │                             ▲
│                     │                             │ MQTT/TCP
│                     │                    ┌───────────────────┐
│                     │                    │  temi_agent.py       │
│                     │                    │  (Claude API bridge) │
│                     │                    └───────────────────┘
│                     │
│                     │   HTTP :8000 (video only — NOT MQTT)
│                     │◀───────────────────────────────────────┐
└──────────────────┘                                            │
                                                          ┌───────────────────┐
                                                          │  serve.py (Flask)    │
                                                          │  dashboard + upload  │
                                                          └───────────────────┘
```

- **Bridge app** (`android/`) — a Kotlin Android app installed on the robot's tablet via the [temi SDK](https://github.com/robotemi/sdk). Publishes sensor/status data, executes commands, and streams video from a URL over plain HTTP.
- **Broker** (`mqtt/`) — Mosquitto, running on a laptop on the same LAN. Two listeners: `1883` (plain MQTT, for the robot) and `9001` (MQTT-over-WebSocket, for the browser — browsers can't open raw TCP sockets, so WebSocket is the only way in). No internet dependency.
- **Web dashboard** (`web/`) — static HTML/CSS/JS, split into `index.html` / `style.css` / `app.js`. Connects to the broker over MQTT-over-WebSocket. No build step, no framework.
- **`serve.py`** — a small Flask server that serves the dashboard and accepts video uploads. Video files are deliberately **not** sent through MQTT (too large/slow for a broker) — they're uploaded over HTTP, and the robot is just told a URL to stream from.
- **LLM agent** (`agent/`) — a Python script that listens for speech transcripts, sends them to Claude, and publishes the reply back for the robot to speak.

## Features

- **Live telemetry** — battery level, position (x/y/yaw), navigation status, person detection, screen-interaction state, serial number
- **Speak** — send text for the robot to say via TTS
- **Go To** — dynamic buttons for every location saved on the robot, live navigation status, emergency STOP
- **Head Tilt** — slider control, -25° (down) to +55° (up)
- **Drive** — press-and-hold D-pad buttons or arrow keys/WASD, with a Slow/Normal/Fast speed selector. Safety design: the robot only keeps moving while commands keep arriving (~5/sec) — release the key/button and it stops on its own within a fraction of a second, no separate watchdog needed.
- **Camera** — on-demand front-camera photo capture (with a "1, 2, 3, smile!" countdown), shown on both the dashboard and Temi's own screen; Clear removes it from both
- **Listen** — trigger speech recognition, see the transcript live
- **Claude voice assistant** — transcripts are sent to Claude (`agent/temi_agent.py`); the reply is spoken by the robot and shown on the dashboard
- **Video** — upload a clip from the dashboard; it plays on Temi's own screen, with remote Play/Pause/Stop. The video is served over plain HTTP directly to the robot — MQTT only carries the URL and the transport commands.

### Tried and removed

A few features were prototyped and then deliberately cut to keep the app focused: screen brightness control, volume control (the SDK's volume property didn't audibly affect TTS output — likely controls a different Android audio stream than the one used for speech), in-place rotation buttons (superseded by the Drive D-pad), and live MJPEG-style video streaming (replaced by upload-and-play, which is simpler and more reliable on Temi's camera hardware).

## Prerequisites

- A Temi robot, on the same Wi-Fi network as your computer
- A Windows/Mac/Linux machine to run the broker, dashboard server, and agent
- [Android Studio](https://developer.android.com/studio) (to build/deploy the bridge app)
- [Mosquitto](https://mosquitto.org/download/) MQTT broker
- Python 3.9+
- An [Anthropic API key](https://console.anthropic.com/) (for the voice assistant)

## Setup

### 1. MQTT broker

Copy `mqtt/temi.conf.example` to `mqtt/temi.conf` — the defaults work as-is for a LAN setup:

```bash
mosquitto -c mqtt/temi.conf -v
```

Opens two listeners: `1883` (MQTT/TCP, for the robot) and `9001` (MQTT-over-WebSocket, for the browser). Make sure your firewall allows both on your local network. On Windows, if Mosquitto is also installed as a background service, stop it first (`Stop-Service mosquitto`) — the service uses different (localhost-only) defaults and will silently steal the port.

### 2. Bridge app (on the robot)

1. Open `android/` in Android Studio.
2. In `MainActivity.kt`, set `BROKER_URI` to your computer's LAN IP, e.g. `tcp://192.168.1.50:1883`.
3. Enable Developer Tools + ADB on the robot (Settings → tap the version number repeatedly), then:
   ```bash
   adb connect <temi-ip>:5555
   ```
4. Select the Temi device in Android Studio and click **Run**.

### 3. Web dashboard + video upload server

```bash
cd web
pip install flask
python serve.py
```

This replaces the plain `python -m http.server` — it serves the dashboard **and** handles video uploads at `/upload`, saving them to `web/videos/` and serving them back to the robot.

In `web/app.js`, set both:
```js
const BROKER = "ws://192.168.1.50:9001";       // same IP as BROKER_URI above
const HTTP_ORIGIN = "http://192.168.1.50:8000"; // same IP, used for video URLs
```
`HTTP_ORIGIN` is deliberately hardcoded rather than derived from the page's own URL — if the dashboard were opened via `localhost`, the robot would otherwise be told to fetch video from *itself* rather than your laptop.

Open `http://<your-ip>:8000` on any device on the network.

### 4. LLM agent (optional)

```bash
cd agent
pip install -r requirements.txt
cp .env.example .env   # then fill in ANTHROPIC_API_KEY
python temi_agent.py
```

## Project structure

```
├── android/               # Kotlin bridge app (temi SDK)
│   ├── MainActivity.kt
│   └── AndroidManifest.xml
├── web/                    # Dashboard + video upload server
│   ├── index.html
│   ├── style.css
│   ├── app.js
│   ├── serve.py              # Flask server: dashboard + /upload endpoint
│   └── videos/                # uploaded videos land here (gitignored)
├── agent/                  # Claude voice-assistant bridge
│   ├── temi_agent.py
│   ├── requirements.txt
│   └── .env.example
├── mqtt/
│   └── temi.conf.example
└── README.md
```

## Known limitations

- The MQTT broker runs anonymously (no auth) by default — fine for an isolated dev network, **not** recommended before wider deployment. Enabling `password_file` in `mqtt/temi.conf` is a straightforward next step, and should happen before Drive or Video are used somewhere less trusted.
- Camera capture uses the legacy `android.hardware.Camera` API for compatibility with Temi's older Android versions.
- Photos are ephemeral — the broker retains only the most recent, in memory.
- Only one video at a time — each upload overwrites `videos/current.<ext>`. Temi's decoder wants standard **H.264 video in an .mp4 container**; newer-phone exports (HEVC, `.mov`) may not play and should be re-encoded first.
- Photo capture and video playback share the same region of Temi's screen (only one shows at a time) but do not share hardware — video plays over HTTP, not through the camera, so the two don't conflict the way live camera streaming would have.

## Roadmap ideas

- Broker authentication
- A proper video library instead of a single overwritable slot
- Multi-robot support (topic-prefix per serial number)
- Revisit live camera streaming with a proper MJPEG HTTP endpoint on the robot, instead of frame-by-frame over MQTT


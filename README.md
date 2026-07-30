# Temi Dashboard

A locally-hosted web dashboard for monitoring and controlling a [Temi](https://www.robotemi.com/) robot, built during my internship at Robopreneur. The robot streams live telemetry to a browser and accepts commands back — navigation, speech, camera capture, and a Claude-powered voice assistant — all over MQTT on the local network, no cloud dependency.

## Architecture

```
┌─────────────────┐        MQTT/TCP         ┌───────────────────┐        MQTT/WebSocket        ┌──────────────────┐
│   Temi robot     │ ──────── :1883 ───────▶ │  Mosquitto broker  │ ◀──────── :9001 ──────────── │  Web dashboard    │
│  (bridge app,    │ ◀─────────────────────  │  (runs on laptop)   │ ────────────────────────────▶│  (browser)        │
│   Kotlin/Android)│                          └───────────────────┘                               └──────────────────┘
                                                        ▲
                                                        │ MQTT/TCP
                                                        ▼
                                              ┌───────────────────┐
                                              │  temi_agent.py      │
                                              │  (Claude API bridge)│
                                              └───────────────────┘
```

- **Bridge app** (`android/`) — a Kotlin Android app installed on the robot's tablet via the [temi SDK](https://github.com/robotemi/sdk). Publishes sensor/status data and executes commands received over MQTT.
- **Broker** (`mqtt/`) — Mosquitto, running on a laptop on the same LAN. Relays messages between the robot, the dashboard, and the agent. No internet dependency.
- **Web dashboard** (`web/`) — static HTML/CSS/JS. Connects to the broker over MQTT-over-WebSocket. No build step, no framework.
- **LLM agent** (`agent/`) — a Python script that listens for speech transcripts, sends them to Claude, and publishes the reply back for the robot to speak.

## Features

- **Live telemetry** — battery level, internal (battery) temperature, position (x/y/yaw), navigation status, person detection, screen-interaction state
- **Speak** — send text for the robot to say via TTS
- **Go To** — dynamic buttons for every location saved on the robot, with live navigation status and an emergency STOP
- **Rotate & Head Tilt** — in-place turning and head tilt control
- **Camera** — on-demand front-camera photo capture (with an optional "1, 2, 3, smile!" countdown), publish/clear
- **Listen** — trigger speech recognition, see the transcript live
- **Claude voice assistant** — transcripts are sent to Claude (via `agent/temi_agent.py`); the reply is spoken by the robot and shown on the dashboard

## Prerequisites

- A Temi robot, on the same Wi-Fi network as your computer
- A Windows/Mac/Linux machine to run the broker, dashboard, and agent
- [Android Studio](https://developer.android.com/studio) (to build/deploy the bridge app)
- [Mosquitto](https://mosquitto.org/download/) MQTT broker
- Python 3.9+ (for the optional LLM agent)
- An [Anthropic API key](https://console.anthropic.com/) (for the LLM agent)

## Setup

### 1. MQTT broker

Copy `mqtt/temi.conf.example` to `mqtt/temi.conf` — the defaults work as-is for a LAN setup. Run:

```bash
mosquitto -c mqtt/temi.conf -v
```

This opens two listeners: `1883` (MQTT/TCP, for the robot) and `9001` (MQTT-over-WebSocket, for the browser). Make sure your firewall allows both on your local network.

### 2. Bridge app (on the robot)

1. Open `android/` in Android Studio.
2. In `MainActivity.kt`, set `BROKER_URI` to your computer's LAN IP, e.g. `tcp://192.168.1.50:1883`.
3. Enable Developer Tools + ADB on the robot (Settings → tap the version number repeatedly), then:
   ```bash
   adb connect <temi-ip>:5555
   ```
4. Select the Temi device in Android Studio and click **Run**.

### 3. Web dashboard

1. In `web/app.js`, set `BROKER` to the same IP, e.g. `ws://192.168.1.50:9001`.
2. Serve the folder:
   ```bash
   cd web
   python -m http.server 8000
   ```
3. Open `http://<your-ip>:8000` on any device on the network.

### 4. LLM agent (optional)

```bash
cd agent
pip install -r requirements.txt
cp .env.example .env   # then fill in ANTHROPIC_API_KEY
python temi_agent.py
```

## Project structure

```
├── android/          # Kotlin bridge app (temi SDK)
│   ├── MainActivity.kt
│   └── AndroidManifest.xml
├── web/               # Dashboard (static site)
│   ├── index.html
│   ├── style.css
│   └── app.js
├── agent/             # Claude voice-assistant bridge
│   ├── temi_agent.py
│   ├── requirements.txt
│   └── .env.example
├── mqtt/
│   └── temi.conf.example
└── README.md
```

## Known limitations

- The MQTT broker runs anonymously (no auth) by default — fine for an isolated dev network, **not** recommended before wider deployment. Enabling `password_file` in `mqtt/temi.conf` is a straightforward next step.
- Camera capture uses the legacy `android.hardware.Camera` API for compatibility with Temi's older Android versions.
- Photos and transcripts are ephemeral — the broker retains only the most recent of each, in memory.

## Roadmap ideas

- Broker authentication
- Manual joystick driving (`skidJoy`)
- MJPEG live camera stream
- Multi-robot support (topic-prefix per serial number)

## License

MIT (or your preferred license — update this section before publishing).
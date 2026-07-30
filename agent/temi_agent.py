"""
temi_agent.py — the robot's brain, running on your laptop.

Listens for speech transcripts on temi/status/asr, sends them to Claude,
speaks the reply through the robot, and shows it on the dashboard.

Setup (once):
    pip install anthropic paho-mqtt python-dotenv
    Create a file named .env next to this script containing:
        ANTHROPIC_API_KEY=sk-ant-your-key-here

Run:
    python temi_agent.py
"""

import json
import os
import time

import paho.mqtt.client as mqtt
from anthropic import Anthropic
from dotenv import load_dotenv

load_dotenv()  # reads .env from the current directory into os.environ

BROKER_HOST = "192.168.0.38"
BROKER_PORT = 1883
MODEL = "claude-haiku-4-5"   # fast + inexpensive, good for voice
MAX_HISTORY = 10             # remember the last N exchanges

SYSTEM_PROMPT = (
    "You are temi, a friendly robot. "
    "You are speaking out loud through text-to-speech, so keep replies "
    "SHORT — one to three sentences, no lists, no markdown, no emojis. "
    "Be warm and a little playful."
)

anthropic_client = Anthropic()  # reads ANTHROPIC_API_KEY from environment
history = []  # rolling conversation: [{"role": ..., "content": ...}, ...]


def ask_claude(text: str) -> str:
    global history
    history.append({"role": "user", "content": text})
    history = history[-MAX_HISTORY * 2:]  # keep the tail only

    response = anthropic_client.messages.create(
        model=MODEL,
        max_tokens=200,
        system=SYSTEM_PROMPT,
        messages=history,
    )
    answer = response.content[0].text.strip()
    history.append({"role": "assistant", "content": answer})
    return answer


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"Connected to broker ({reason_code}), waiting for speech...")
    client.subscribe("temi/status/asr")


def on_message(client, userdata, msg):
    try:
        text = json.loads(msg.payload.decode()).get("text", "").strip()
        if not text:
            return
        print(f'Heard:  "{text}"')

        t0 = time.time()
        answer = ask_claude(text)
        print(f'Claude: "{answer}"  ({time.time() - t0:.1f}s)')

        # Speak it through the robot
        client.publish("temi/command/speak", json.dumps({"text": answer}))
        # Show it on the dashboard
        client.publish("temi/status/llm", json.dumps({"question": text, "answer": answer}))
    except Exception as e:
        print(f"Error: {e}")


def main():
    if not os.environ.get("ANTHROPIC_API_KEY"):
        raise SystemExit("ANTHROPIC_API_KEY not found. Create a .env file next to this script with: ANTHROPIC_API_KEY=sk-ant-...")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(BROKER_HOST, BROKER_PORT, keepalive=60)
    client.loop_forever()


if __name__ == "__main__":
    main()
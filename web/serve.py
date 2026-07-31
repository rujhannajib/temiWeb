"""
serve.py — serves the dashboard AND handles video uploads.

Run this INSTEAD OF `python -m http.server 8000` — it does everything
that command did, plus a new /upload endpoint and a /videos/ folder
that Temi streams directly from over HTTP.

Setup (once):
    pip install flask

Run:
    python serve.py
"""

import os
from flask import Flask, request, send_from_directory, jsonify

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
VIDEOS_DIR = os.path.join(BASE_DIR, "videos")
os.makedirs(VIDEOS_DIR, exist_ok=True)

app = Flask(__name__, static_folder=BASE_DIR, static_url_path="")


@app.route("/")
def index():
    return send_from_directory(BASE_DIR, "index.html")


@app.route("/videos/<path:filename>")
def serve_video(filename):
    return send_from_directory(VIDEOS_DIR, filename)


@app.route("/upload", methods=["POST"])
def upload():
    if "video" not in request.files:
        return jsonify({"error": "no file in request"}), 400
    f = request.files["video"]
    if f.filename == "":
        return jsonify({"error": "empty filename"}), 400

    # Always save as "current.<ext>" — keeps things simple, only ever
    # one video on disk, nothing to clean up between uploads.
    ext = os.path.splitext(f.filename)[1] or ".mp4"
    saved_name = "current" + ext
    f.save(os.path.join(VIDEOS_DIR, saved_name))

    return jsonify({"filename": saved_name})


if __name__ == "__main__":
    print("Serving dashboard + video upload on http://0.0.0.0:8000")
    app.run(host="0.0.0.0", port=8000)
// Temi Dashboard — app.js
// CHANGE THIS to your laptop's IP (same machine running Mosquitto)
const BROKER = "ws://192.168.0.38:9001";

document.getElementById("brokerUrl").textContent = BROKER;

const conn = document.getElementById("conn");
const speakBtn = document.getElementById("speakBtn");
const ttsText = document.getElementById("ttsText");
const speakStatus = document.getElementById("speakStatus");
const photoBtn = document.getElementById("photoBtn");
const listenBtn = document.getElementById("listenBtn");

const client = mqtt.connect(BROKER, { reconnectPeriod: 3000 });

// ── connection state ────────────────────────────────────────────

client.on("connect", () => {
    conn.textContent = "connected";
    conn.classList.add("ok");
    speakBtn.disabled = false;
    photoBtn.disabled = false;
    listenBtn.disabled = false;
    client.subscribe("temi/status/#");
});

client.on("close", () => {
    conn.textContent = "disconnected";
    conn.classList.remove("ok");
    speakBtn.disabled = true;
    photoBtn.disabled = true;
    listenBtn.disabled = true;
});

// ── incoming messages ───────────────────────────────────────────

client.on("message", (topic, message) => {
    // photo carries raw JPEG bytes, not JSON — handle before parsing
    if (topic === "temi/status/photo") {
        const img = document.getElementById("photo");
        if (message.length === 0) {
            if (img.src) URL.revokeObjectURL(img.src);
            img.src = "";
            img.style.display = "none";
            document.getElementById("photoStatus").textContent = "";
            return;
        }
        const blob = new Blob([message], { type: "image/jpeg" });
        if (img.src) URL.revokeObjectURL(img.src);
        img.src = URL.createObjectURL(blob);
        img.style.display = "block";
        document.getElementById("photoStatus").textContent =
            "Received " + Math.round(message.length / 1024) + " KB at " +
            new Date().toLocaleTimeString();
        return;
    }

    let data;
    try { data = JSON.parse(message.toString()); } catch { return; }

    if (topic === "temi/status/battery") {
        document.getElementById("batteryLevel").textContent = data.level;
        document.getElementById("charging").textContent =
            data.charging ? "charging" : "on battery";
        const fill = document.getElementById("batteryFill");
        fill.style.width = data.level + "%";
        fill.style.background = data.level > 20 ? "#1d9e75" : "#e24b4a";
    }

    if (topic === "temi/status/position") {
        document.getElementById("posX").textContent = data.x.toFixed(2);
        document.getElementById("posY").textContent = data.y.toFixed(2);
        document.getElementById("posYaw").textContent = data.yaw.toFixed(2);
    }

    if (topic === "temi/status/heartbeat") {
        document.getElementById("serial").textContent = data.serial;
    }

    if (topic === "temi/status/nav") {
        document.getElementById("navLocation").textContent =
            data.location ? "\u2192 " + data.location : "";
        const s = document.getElementById("navStatus");
        s.textContent = data.status;
        s.style.color = data.status === "complete" ? "#0f6e56"
            : data.status === "abort" ? "#a32d2d" : "#222";
    }

    if (topic === "temi/status/detection") {
        const dot = document.getElementById("detectDot");
        document.getElementById("detectState").textContent = data.state;
        dot.style.background = data.state === "detected" ? "#1d9e75"
            : data.state === "lost" ? "#e8a13c" : "#ccc";
    }

    if (topic === "temi/status/interaction") {
        document.getElementById("interacting").textContent = data.interacting ? "yes" : "no";
    }

    if (topic === "temi/status/locations") {
        const box = document.getElementById("locButtons");
        box.innerHTML = "";
        if (!data.locations || data.locations.length === 0) {
            box.innerHTML = '<span class="sub">no saved locations on the robot</span>';
        } else {
            data.locations.forEach((loc) => {
                const b = document.createElement("button");
                b.className = "locBtn";
                b.textContent = loc;
                b.addEventListener("click", () => {
                    client.publish("temi/command/goto", JSON.stringify({ location: loc }));
                });
                box.appendChild(b);
            });
        }
        document.getElementById("stopBtn").disabled = false;
    }

    if (topic === "temi/status/asr") {
        addLogEntry("\u201C" + data.text + "\u201D",
            new Date(data.timestamp || Date.now()).toLocaleTimeString());
    }

    if (topic === "temi/status/llm") {
        addLogEntry("\uD83E\uDD16 " + data.answer, "", "#0f6e56");
    }

    document.getElementById("lastUpdate").textContent =
        new Date().toLocaleTimeString();
});

function addLogEntry(text, time, color) {
    const log = document.getElementById("asrLog");
    const entry = document.createElement("div");
    entry.className = "row";
    const left = document.createElement("span");
    left.textContent = text;              // textContent = automatic HTML escaping
    if (color) left.style.color = color;
    const right = document.createElement("span");
    right.className = "sub";
    right.textContent = time;
    entry.append(left, right);
    log.prepend(entry);
}

// ── outgoing commands ───────────────────────────────────────────

function sendSpeak() {
    const text = ttsText.value.trim();
    if (!text) return;
    client.publish("temi/command/speak", JSON.stringify({ text: text }));
    speakStatus.textContent = 'Sent: "' + text + '"';
    ttsText.value = "";
    ttsText.focus();
}

speakBtn.addEventListener("click", sendSpeak);
ttsText.addEventListener("keydown", (e) => {
    if (e.key === "Enter") sendSpeak();
});

document.getElementById("stopBtn").addEventListener("click", () => {
    client.publish("temi/command/stop", "{}");
});

photoBtn.addEventListener("click", () => {
    document.getElementById("photoStatus").textContent = "Capture requested...";
    client.publish("temi/command/photo", "{}");
});

// empty retained message deletes the broker's stored photo
document.getElementById("clearPhotoBtn").addEventListener("click", () => {
    client.publish("temi/status/photo", "", { retain: true });
});

listenBtn.addEventListener("click", () => {
    client.publish("temi/command/listen", "{}");
});

// clear listen history — local to this dashboard only
document.getElementById("clearAsrBtn").addEventListener("click", () => {
    document.getElementById("asrLog").innerHTML = "";
});

const tiltSlider = document.getElementById("tiltSlider");
const tiltValue = document.getElementById("tiltValue");
tiltSlider.addEventListener("input", () => {
    tiltValue.innerHTML = "<b>" + tiltSlider.value + "\u00B0</b>";
});
tiltSlider.addEventListener("change", () => {
    client.publish("temi/command/tilt", JSON.stringify({ angle: parseInt(tiltSlider.value) }));
});

// ── drive (buttons + keyboard) ──────────────────────────────────
// The dead-man switch lives on the ROBOT side: skidJoy only keeps moving
// while it keeps being called. So here we re-send the current vector
// every 200ms while any direction is held, and stop the instant nothing
// is held. If the connection drops or the tab closes, nothing gets sent
// and the robot naturally stops on its own — no extra watchdog needed.

const DRIVE_INTERVAL_MS = 200;
const activeDirections = new Set(); // 'forward' | 'backward' | 'left' | 'right'
let driveTimer = null;
let driveSpeed = 0.6; // scales the vector — skidJoy's x/y ARE the speed fraction (-1..1 of max)

function clamp(n) { return Math.max(-1, Math.min(1, n)); }

// direction -> its button element, so keyboard presses can glow the
// matching button even though the key never touches it directly
const dirButtons = {};
document.querySelectorAll(".dpadBtn").forEach((btn) => {
    dirButtons[btn.dataset.dir] = btn;
});

// combine whatever's currently held into one vector — supports
// diagonals, e.g. holding forward + right curves while driving
function computeDriveVector() {
    let x = 0, y = 0;
    if (activeDirections.has("forward")) y += 1;
    if (activeDirections.has("backward")) y -= 1;
    if (activeDirections.has("left")) x -= 1;
    if (activeDirections.has("right")) x += 1;
    return { x: clamp(x * driveSpeed), y: clamp(y * driveSpeed) };
}

function publishDrive() {
    client.publish("temi/command/move", JSON.stringify(computeDriveVector()));
}

function startDriving(direction) {
    if (activeDirections.has(direction)) return; // already active — key auto-repeat, ignore
    activeDirections.add(direction);
    dirButtons[direction]?.classList.add("pressed");
    publishDrive(); // send immediately, don't wait for the first interval tick
    if (!driveTimer) {
        driveTimer = setInterval(publishDrive, DRIVE_INTERVAL_MS);
    }
}

function stopDriving(direction) {
    if (!activeDirections.has(direction)) return;
    activeDirections.delete(direction);
    dirButtons[direction]?.classList.remove("pressed");
    if (activeDirections.size === 0) {
        clearInterval(driveTimer);
        driveTimer = null;
        client.publish("temi/command/move", JSON.stringify({ x: 0, y: 0 })); // explicit stop
    } else {
        publishDrive(); // still moving, but the vector changed — send right away
    }
}

// buttons — pointer events cover mouse + touch in one listener
document.querySelectorAll(".dpadBtn").forEach((btn) => {
    const dir = btn.dataset.dir;
    btn.addEventListener("pointerdown", (e) => { e.preventDefault(); startDriving(dir); });
    btn.addEventListener("pointerup", () => stopDriving(dir));
    btn.addEventListener("pointerleave", () => stopDriving(dir)); // finger/cursor dragged off the button
    btn.addEventListener("pointercancel", () => stopDriving(dir));
});

// keyboard — arrow keys and WASD
const DRIVE_KEYS = {
    ArrowUp: "forward", w: "forward", W: "forward",
    ArrowDown: "backward", s: "backward", S: "backward",
    ArrowLeft: "left", a: "left", A: "left",
    ArrowRight: "right", d: "right", D: "right",
};

window.addEventListener("keydown", (e) => {
    const dir = DRIVE_KEYS[e.key];
    if (!dir) return;
    e.preventDefault(); // stop arrow keys from scrolling the page
    startDriving(dir);
});

window.addEventListener("keyup", (e) => {
    const dir = DRIVE_KEYS[e.key];
    if (dir) stopDriving(dir);
});

// safety: if the window loses focus (alt-tab, clicking another app) while
// a key is held, keyup never fires — so force a full stop on blur
window.addEventListener("blur", () => {
    activeDirections.clear();
    clearInterval(driveTimer);
    driveTimer = null;
    Object.values(dirButtons).forEach((btn) => btn.classList.remove("pressed"));
    client.publish("temi/command/move", JSON.stringify({ x: 0, y: 0 }));
});

// speed selector — changes take effect on the NEXT publish tick,
// so adjusting speed mid-drive works without releasing first
document.querySelectorAll(".speedBtn").forEach((btn) => {
    btn.addEventListener("click", () => {
        driveSpeed = parseFloat(btn.dataset.speed);
        document.querySelectorAll(".speedBtn").forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
    });
});
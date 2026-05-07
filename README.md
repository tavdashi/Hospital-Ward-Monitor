# IOT Hospital Ward Management System
## Complete Setup & Usage Guide

---

## 📁 Project Structure

```
HospitalWardSystem/
├── arduino/
│   └── HospitalWard.ino          ← Upload to Arduino Uno
├── java/
│   ├── src/
│   │   ├── HospitalWardBridge.java   ← Main Java bridge server
│   │   └── SimpleJSON.java           ← JSON parser utility
│   ├── lib/
│   │   └── (place jserialcomm-2.10.4.jar here)
│   └── bin/                          ← compiled classes (auto-created)
├── dashboard/
│   └── index.html                ← Live web dashboard
├── run.bat                       ← Windows launcher
└── README.md
```

---

## 🔌 Hardware Wiring

| Component        | Arduino Pin | Purpose                                |
|------------------|-------------|----------------------------------------|
| Reset Button     | D2          | Resets entire system state             |
| IR Sensor 1      | D3          | Over Bed 1 — patient presence          |
| Ultrasonic TRIG  | D4          | Entrance visitor detection             |
| Ultrasonic ECHO  | D5          | Entrance visitor detection             |
| IR Sensor 2      | A0          | Over Bed 2 — patient presence (analog) |
| Buzzer           | D8          | Audio alerts (slow=warning, fast=critical) |
| LED - Armed      | A1          | System is armed and monitoring         |
| LED - Stable     | D7          | All patients present                   |
| LED - Warning    | D12         | One patient absent (slow blink)        |
| LED - Critical   | D13         | Both patients absent (fast blink)      |

---

## ⚙️ LED & Buzzer Behaviour

| State    | LED Armed | LED Stable | LED Warning | LED Critical | Buzzer          |
|----------|-----------|------------|-------------|--------------|-----------------|
| ARMED    | ON        | OFF        | OFF         | OFF          | Silent          |
| STABLE   | OFF       | ON         | OFF         | OFF          | Silent          |
| WARNING  | OFF       | OFF        | BLINKING    | OFF          | Beep every 1s   |
| CRITICAL | OFF       | OFF        | OFF         | FAST BLINK   | Beep every 200ms|

---

## 🚀 Step-by-Step Setup

### Step 1 — Arduino
1. Open `arduino/HospitalWard.ino` in Arduino IDE
2. Connect your Arduino Uno via USB
3. Select **Tools → Board → Arduino Uno**
4. Select correct **Tools → Port → COMx**
5. Click **Upload**
6. Done. LEDs will do a boot chase sequence on startup.

### Step 2 — Java Environment
1. Install **Java 11 or later** from: https://adoptium.net/
2. Verify: open Command Prompt → `java -version`

### Step 3 — jSerialComm Library
1. Download `jserialcomm-2.10.4.jar` from:
   https://github.com/Fazecast/jSerialComm/releases
2. Place the JAR file in: `java/lib/jserialcomm-2.10.4.jar`

### Step 4 — Run the System
Double-click `run.bat` or open Command Prompt in the project folder and run:

```bat
run.bat
```

Optional: specify COM port manually:
```bat
run.bat COM3
```

The script will:
- ✅ Verify Java installation
- ✅ Compile Java source files  
- ✅ Build the bridge JAR
- ✅ Launch the HTTP + SSE server on port 8765
- ✅ Open dashboard at http://localhost:8765/dashboard

### Step 5 — View Dashboard
The browser opens automatically. You can also open manually:
```
http://localhost:8765/dashboard
```

---

## 📡 System Flow

```
Arduino Uno
    │ (USB serial, 9600 baud)
    │ Sends JSON every 2s + on events
    ▼
HospitalWardBridge.java  (Java HTTP server on :8765)
    │ Parses JSON
    │ Maintains state
    │ Serves REST API
    │ Streams SSE events
    ▼
Dashboard (index.html)
    │ EventSource (SSE) for live data
    │ Falls back to HTTP polling every 5s
    ▼
Browser (Real-time display)
```

---

## 🔗 API Endpoints

| Endpoint          | Description                          |
|-------------------|--------------------------------------|
| `GET /api/status` | Latest ward state (JSON)             |
| `GET /api/events` | Last 200 events (JSON array)         |
| `GET /api/ports`  | Available COM ports                  |
| `GET /sse`        | Server-Sent Events live stream       |
| `GET /dashboard`  | Serves the HTML dashboard            |

---

## 🛠 Troubleshooting

**"No serial port found"**  
→ Arduino not connected, or wrong drivers. Install CH340/FTDI drivers.

**"jserialcomm-2.10.4.jar not found"**  
→ Download from GitHub and place in `java/lib/` folder.

**Dashboard shows "Connecting..."**  
→ Make sure `run.bat` is running and no firewall is blocking port 8765.

**IR sensor always triggering**  
→ Adjust the sensitivity potentiometer on the IR module.

**Visitor count not increasing**  
→ Ultrasonic requires < 5 cm detection for 1.5 seconds to confirm visitor.

---

## 📋 Serial JSON Format (Arduino → Java)

```json
{
  "type": "update",
  "state": "STABLE",
  "bed1": "OK",
  "bed2": "ALERT",
  "visitors": 3,
  "alerts": 1,
  "ultraDist": 4,
  "uptime": 120000,
  "btnReset": false
}
```

---

*Hospital Ward Management System v1.0*

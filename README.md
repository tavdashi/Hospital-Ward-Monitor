# IoT Hospital Ward Monitoring System

A real-time IoT hospital ward management system built with **Arduino Uno**, **Java**, and a **live web dashboard**. Built as an Object-Oriented Programming project demonstrating all five OOP pillars through actual hardware integration.

---

## What It Does

Block an IR sensor above a patient bed → Arduino detects absence → Java processes the event after a grace period → LEDs flash and buzzer fires → live browser dashboard updates instantly.

A cardboard hospital ward model with two patient beds simulates a real hospital environment. Two IR sensors monitor bed occupancy, an HC-SR04 ultrasonic sensor detects visitors at the ward entrance, and a passive buzzer + four LEDs provide immediate physical feedback. The entire system — circuit, Java bridge, and dashboard — reacts in real time.

---

## Hardware

| Component | Pin | Role |
|---|---|---|
| IR Sensor 1 (Digital) | Pin 3 | Bed 1 patient presence detection |
| IR Sensor 2 (Analog) | Pin A0 | Bed 2 patient presence — analogRead() < 400 = present |
| HC-SR04 Ultrasonic TRIG | Pin 4 | Ward entrance visitor detection trigger |
| HC-SR04 Ultrasonic ECHO | Pin 5 | Ward entrance visitor detection echo |
| Push Button | Pin 2 | Nurse override — clears all alerts and resets state |
| Passive Buzzer | Pin 8 | Audio alerts — slow beep (WARNING), rapid beep (CRITICAL) |
| LED — Armed | Pin A1 | ON when system is actively monitoring |
| LED — Stable | Pin 7 | ON when all patients present, ward is stable |
| LED — Warning | Pin 12 | Slow blink — one bed unoccupied |
| LED — Critical | Pin 13 | Fast blink — both beds unoccupied or tamper detected |
| 220Ω Resistors | — | Current limiters for all LEDs |

> **Note:** IR proximity sensors are used as direct functional equivalents to professional bed-presence sensors. The architecture is designed so that swapping to dedicated pressure mats or capacitive sensors requires only a hardware swap — zero code changes needed.

---

## Project Structure

```
TRIAGE/
├── Arduino/
│   ├── TRIAGE_HARDWARE/
│   │   └── TRIAGE_HARDWARE.ino      ← full system with buzzer, no dashboard
│   └── TRIAGE_DASHBOARD/
│       └── TRIAGE_DASHBOARD.ino     ← dashboard-compatible, buzzer disabled
├── Java/
│   └── TRIAGE_JAVA/
│       ├── lib/
│       │   └── jSerialComm-2.10.4.jar
│       └── src/
│           ├── Main.java                        ← entry point, serial reader, HTTP + SSE server
│           ├── engine/
│           │   ├── SystemState.java             ← enum: STABLE, WARNING, CRITICAL
│           │   └── TriageEngine.java            ← state machine, State pattern
│           ├── models/
│           │   ├── Patient.java                 ← patient object, Encapsulation
│           │   └── Bed.java                     ← bed object, holds Patient
│           ├── monitors/
│           │   ├── BedMonitor.java              ← abstract class, Abstraction
│           │   └── WardMonitor.java             ← extends BedMonitor, Observer pattern
│           ├── station/
│           │   └── NurseStation.java            ← Factory pattern, creates Beds + Monitors
│           └── logger/
│               └── IncidentLogger.java          ← Singleton pattern, timestamped event log
├── TRIAGE_Dashboard.html                        ← live browser dashboard
├── RUN_TRIAGE.bat                               ← one-click compile and run (Windows)
└── README.md
```

---

## System Architecture

```
┌─────────────────────────────────────────────────────┐
│                   HARDWARE LAYER                    │
│  IR1 + IR2 (beds) + Ultrasonic (entrance) + Button  │
│              Arduino Uno — USB Serial               │
└────────────────────────┬────────────────────────────┘
                         │ Serial data every 500ms
                         ▼
┌─────────────────────────────────────────────────────┐
│                    JAVA BRIDGE                      │
│  jSerialComm reads COM port → TriageEngine runs     │
│  state machine → IncidentLogger logs events →       │
│  HTTP + SSE server pushes updates to browser        │
│              localhost:8765                         │
└────────────────────────┬────────────────────────────┘
                         │ Server-Sent Events (SSE)
                         ▼
┌─────────────────────────────────────────────────────┐
│                 BROWSER DASHBOARD                   │
│  EventSource connects to /sse → live bed cards →    │
│  visitor counter → activity log → ward schematic    │
│         Works in any modern browser                 │
└─────────────────────────────────────────────────────┘
```

---

## How to Run

### Prerequisites
- Java 17 or higher installed
- Arduino IDE installed
- Google Chrome (or any modern browser)
- Arduino Uno connected via USB

---

### Step 1 — Download jSerialComm

Download the jar from [fazecast.github.io/jSerialComm](https://fazecast.github.io/jSerialComm/)

Place it here:
```
TRIAGE/Java/TRIAGE_JAVA/lib/jSerialComm-2.10.4.jar
```

If your downloaded jar has a different version number, rename it to exactly `jSerialComm-2.10.4.jar`.

---

### Step 2 — Find your Arduino COM port

1. Plug Arduino into your laptop via USB
2. Open Arduino IDE
3. Go to `Tools → Port`
4. Note the port — it will be something like `COM3`, `COM5`, or `COM6`

---

### Step 3 — Set your COM port in Main.java

Open `Java/TRIAGE_JAVA/src/Main.java` and find this line:

```java
SerialPort port = SerialPort.getCommPort("COM5");
```

Change `COM5` to your actual port from Step 2. Save the file.

---

### Step 4 — Upload Arduino sketch

**For hardware demo with buzzer (no dashboard):**
1. Open `Arduino/TRIAGE_HARDWARE/TRIAGE_HARDWARE.ino` in Arduino IDE
2. Select `Tools → Board → Arduino Uno`
3. Select `Tools → Port → your COM port`
4. Click Upload
5. Wait for "Done uploading"

**For dashboard demo (buzzer disabled):**
1. Open `Arduino/TRIAGE_DASHBOARD/TRIAGE_DASHBOARD.ino` in Arduino IDE
2. Select `Tools → Board → Arduino Uno`
3. Select `Tools → Port → your COM port`
4. Click Upload
5. Wait for "Done uploading"

> ⚠️ Only upload one sketch at a time. Close Serial Monitor after uploading before running Java or opening the dashboard.

---

### Step 5 — Run the Java bridge

**Windows — one click:**

Double-click `RUN_TRIAGE.bat`

If Windows blocks it:
- Right-click → Properties → check "Unblock" → Apply → OK
- Or open Command Prompt and run: `cd path\to\TRIAGE && RUN_TRIAGE.bat`

**Mac/Linux:**

```bash
cd TRIAGE/Java/TRIAGE_JAVA
mkdir -p out
javac -cp "lib/jSerialComm-2.10.4.jar" -d out \
  src/engine/SystemState.java \
  src/models/Patient.java \
  src/models/Bed.java \
  src/monitors/BedMonitor.java \
  src/monitors/WardMonitor.java \
  src/logger/IncidentLogger.java \
  src/engine/TriageEngine.java \
  src/station/NurseStation.java \
  src/Main.java
cd out
java -cp ".:../lib/jSerialComm-2.10.4.jar" Main
```

The terminal will print:
```
[2026-01-05 14:32:01] TRIAGE SYSTEM STARTING
[2026-01-05 14:32:01] Ward initialized with 2 beds
[2026-01-05 14:32:01] Connected to Arduino on COM5
[2026-01-05 14:32:01] Listening for sensor data...
[2026-01-05 14:32:02] STATUS:STABLE | BED1:OCCUPIED | BED2:OCCUPIED
```

---

### Step 6 — Open the dashboard

1. Make sure Java bridge from Step 5 is running
2. Open `TRIAGE_Dashboard.html` in Chrome or any modern browser
3. The status indicator turns **green** when connected to the Java bridge
4. Dashboard updates live as sensor states change

> ⚠️ The dashboard and Java terminal run simultaneously — Java owns the COM port, dashboard connects to Java via SSE. You do NOT need to close the dashboard to run Java or vice versa.

---

### Step 7 — Trigger the system

| Action | What happens |
|---|---|
| Both IR sensors blocked | STABLE — green LED solid, dashboard green |
| Remove hand from IR1 | WARNING after 3s — LED slow blink, slow buzzer, dashboard orange |
| Remove hand from IR2 | WARNING after 3s — LED slow blink, slow buzzer, dashboard orange |
| Remove both hands | CRITICAL — LED fast blink, rapid buzzer, dashboard red |
| Touch ultrasonic (<5cm) | Visitor registered — visitor counter increments on dashboard |
| Press button | RESET — all alerts cleared, LED chase sequence, back to STABLE |

---

## System States

```
ARMED    →  System booted, waiting for stable readings
    ↓
STABLE   →  Both patients present, all clear
    ↓
WARNING  →  One bed empty for >3 seconds
    ↓
CRITICAL →  Both beds empty simultaneously
```

| State | LED Behaviour | Buzzer | Dashboard |
|---|---|---|---|
| STABLE | Green solid | Silent | Green banner |
| WARNING | Warning LED slow blink | Slow beep 2000Hz | Orange banner |
| CRITICAL | Critical LED fast blink | Rapid beep 1500Hz | Red banner |
| RESET | All LEDs chase sequence | Double beep | Returns to STABLE |

---

## OOP Design Patterns

| Pattern | Class | How it's used |
|---|---|---|
| Abstract Class | `BedMonitor` | Defines `updateStatus()` and `evaluateState()` — subclasses must implement |
| Inheritance | `WardMonitor extends BedMonitor` | Inherits bed monitoring structure, overrides behaviour |
| Polymorphism | `updateStatus()` | Called on any BedMonitor — behaves differently per subclass |
| State Pattern | `TriageEngine` | Manages STABLE → WARNING → CRITICAL transitions |
| Observer Pattern | `WardMonitor` | Notifies all registered monitors when state changes |
| Factory Pattern | `NurseStation` | Creates Bed and WardMonitor objects on demand |
| Singleton | `IncidentLogger` | One logger instance shared across entire system |
| Encapsulation | `Patient`, `Bed` | Private fields exposed only through getters/setters |

---

## Alert Thresholds

| Condition | Duration | State triggered |
|---|---|---|
| One bed empty | 3 seconds grace period | WARNING |
| Both beds empty | 3 seconds grace period | CRITICAL |
| Ultrasonic < 5cm | 1.5 seconds confirmation | Visitor registered |
| Button pressed | Instant | Full system reset |

---

## Two Demo Modes

### Hardware Demo (TRIAGE_HARDWARE.ino)
Full buzzer tones, all LEDs, Serial Monitor output. Best for showing physical hardware reacting live. Run without dashboard.

### Dashboard Demo (TRIAGE_DASHBOARD.ino)
Buzzer disabled (tone() interferes with Web Serial). All LEDs still flash. Java bridge + dashboard both running simultaneously. Best for showing live data flow.

---

## Requirements

- Java 17+
- Arduino Uno + USB cable
- Arduino IDE (for uploading sketches)
- Any modern browser — Chrome, Firefox, Edge, Safari
- Windows (for `RUN_TRIAGE.bat`) — or adapt commands for Mac/Linux

---

## Troubleshooting

**"Cannot open port — Access is denied"**
→ Close Arduino Serial Monitor, close Chrome dashboard, then try again. Only one application can own the COM port at a time.

**Dashboard not connecting**
→ Make sure Java bridge is running first. Check terminal shows "Listening for sensor data". Then open dashboard.

**Compilation failed**
→ Make sure Java JDK is installed (not just JRE). Run `javac -version` in terminal to check.

**jSerialComm not found**
→ Download jar and place in `Java/TRIAGE_JAVA/lib/`. Rename to exactly `jSerialComm-2.10.4.jar`.

**IR sensor always reads OCCUPIED**
→ IR module may have inverted output. Adjust sensitivity pot on the back of the module or invert logic in Arduino sketch.

**Buzzer kills dashboard connection**
→ Use `TRIAGE_DASHBOARD.ino` instead — buzzer disabled for dashboard compatibility.

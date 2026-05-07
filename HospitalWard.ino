// ============================================================
//  IOT HOSPITAL WARD MANAGEMENT SYSTEM - Arduino Firmware
//  Author: HospitalWard v1.0
// ============================================================

#define BTN           2
#define IR1           3
#define TRIG          4
#define ECHO          5
#define IR2           A0
#define BUZZER        8
#define LED_ARMED     A1
#define LED_STABLE    7
#define LED_WARNING   12
#define LED_CRITICAL  13

// ---- System States ----
#define STATE_IDLE      0
#define STATE_ARMED     1
#define STATE_STABLE    2
#define STATE_WARNING   3
#define STATE_CRITICAL  4

// ---- Timing ----
unsigned long lastSensorRead    = 0;
unsigned long lastHeartbeat     = 0;
unsigned long lastBuzzerToggle  = 0;
unsigned long lastLedBlink      = 0;
unsigned long lastVisitorCheck  = 0;
unsigned long bed1AlertStart    = 0;
unsigned long bed2AlertStart    = 0;
unsigned long visitorDetectedAt = 0;
unsigned long btnDebounce       = 0;

const unsigned long SENSOR_INTERVAL    = 200;
const unsigned long HEARTBEAT_INTERVAL = 2000;
const unsigned long VISITOR_CONFIRM_MS = 1500;  // must be < 5cm for 1.5s
const unsigned long BED_ALERT_DELAY    = 3000;  // 3s absence before alert

// ---- State Tracking ----
int  systemState      = STATE_ARMED;
bool bed1Present      = true;   // patient in bed 1
bool bed2Present      = true;   // patient in bed 2
bool bed1Absent       = false;
bool bed2Absent       = false;
bool visitorPending   = false;
bool buzzerOn         = false;
bool ledBlinkState    = false;
bool btnLastState     = HIGH;

int  visitorCount     = 0;
int  alertCount       = 0;
long lastUltraDist    = 999;

// Forward declarations
void sendJSON();
void setAllLEDs(bool armed, bool stable, bool warning, bool critical);
void handleBuzzer();
void updateSystemState();
void checkBed1();
void checkBed2();
void checkVisitor();
void resetSystem();
long readUltrasonic();

// ============================================================
void setup() {
  Serial.begin(9600);

  pinMode(BTN,          INPUT_PULLUP);
  pinMode(IR1,          INPUT);
  pinMode(TRIG,         OUTPUT);
  pinMode(ECHO,         INPUT);
  pinMode(BUZZER,       OUTPUT);
  pinMode(LED_ARMED,    OUTPUT);
  pinMode(LED_STABLE,   OUTPUT);
  pinMode(LED_WARNING,  OUTPUT);
  pinMode(LED_CRITICAL, OUTPUT);

  // IR2 is analog - no pinMode needed (analog input)

  // Boot sequence
  bootSequence();

  systemState = STATE_ARMED;
  sendJSON();
}

// ============================================================
void loop() {
  unsigned long now = millis();

  // ---- Button debounce & reset ----
  bool btnCurrent = digitalRead(BTN);
  if (btnCurrent == LOW && btnLastState == HIGH && (now - btnDebounce > 200)) {
    btnDebounce = now;
    resetSystem();
  }
  btnLastState = btnCurrent;

  // ---- Sensor reads ----
  if (now - lastSensorRead >= SENSOR_INTERVAL) {
    lastSensorRead = now;
    checkBed1();
    checkBed2();
    checkVisitor();
    updateSystemState();
  }

  // ---- Buzzer dynamics ----
  handleBuzzer();

  // ---- Heartbeat JSON ----
  if (now - lastHeartbeat >= HEARTBEAT_INTERVAL) {
    lastHeartbeat = now;
    sendJSON();
  }
}

// ============================================================
//  SENSOR FUNCTIONS
// ============================================================

void checkBed1() {
  // IR1 digital — LOW = object detected (patient present)
  bool detected = (digitalRead(IR1) == LOW);
  unsigned long now = millis();

  if (detected) {
    bed1Present = true;
    bed1Absent  = false;
    bed1AlertStart = 0;
  } else {
    if (bed1Present) {
      // Patient just left
      if (bed1AlertStart == 0) bed1AlertStart = now;
      if ((now - bed1AlertStart) >= BED_ALERT_DELAY) {
        if (!bed1Absent) {
          bed1Absent = true;
          bed1Present = false;
          alertCount++;
          sendJSON(); // immediate alert
        }
      }
    }
  }
}

void checkBed2() {
  // IR2 analog — threshold: < 400 = detected
  int val = analogRead(IR2);
  bool detected = (val < 400);
  unsigned long now = millis();

  if (detected) {
    bed2Present = true;
    bed2Absent  = false;
    bed2AlertStart = 0;
  } else {
    if (bed2Present) {
      if (bed2AlertStart == 0) bed2AlertStart = now;
      if ((now - bed2AlertStart) >= BED_ALERT_DELAY) {
        if (!bed2Absent) {
          bed2Absent = true;
          bed2Present = false;
          alertCount++;
          sendJSON();
        }
      }
    }
  }
}

void checkVisitor() {
  long dist = readUltrasonic();
  lastUltraDist = dist;
  unsigned long now = millis();

  if (dist > 0 && dist < 9) {
    if (!visitorPending) {
      visitorPending   = true;
      visitorDetectedAt = now;
    } else if ((now - visitorDetectedAt) >= VISITOR_CONFIRM_MS) {
      // Confirmed visitor
      visitorCount++;
      visitorPending = false;
      sendJSON(); // immediate update
    }
  } else {
    visitorPending = false;
  }
}

long readUltrasonic() {
  digitalWrite(TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  long duration = pulseIn(ECHO, HIGH, 30000);
  if (duration == 0) return 999;
  return duration / 58; // cm
}

// ============================================================
//  STATE MACHINE
// ============================================================

void updateSystemState() {
  int prev = systemState;

  if (bed1Absent && bed2Absent) {
    systemState = STATE_CRITICAL;
  } else if (bed1Absent || bed2Absent) {
    systemState = STATE_WARNING;
  } else {
    systemState = STATE_STABLE;
  }

  // Update LEDs based on state
  switch (systemState) {
    case STATE_STABLE:
      setAllLEDs(false, true, false, false);
      break;
    case STATE_WARNING:
      // WARNING blinks
      setAllLEDs(false, false, true, false);
      break;
    case STATE_CRITICAL:
      // CRITICAL blinks fast
      setAllLEDs(false, false, false, true);
      break;
    case STATE_ARMED:
      setAllLEDs(true, false, false, false);
      break;
  }

  // Blink logic for WARNING/CRITICAL
  unsigned long now = millis();
  int blinkInterval = (systemState == STATE_CRITICAL) ? 200 : 500;
  if (systemState == STATE_WARNING || systemState == STATE_CRITICAL) {
    if (now - lastLedBlink >= (unsigned long)blinkInterval) {
      lastLedBlink = now;
      ledBlinkState = !ledBlinkState;
      if (systemState == STATE_WARNING)  digitalWrite(LED_WARNING,  ledBlinkState);
      if (systemState == STATE_CRITICAL) digitalWrite(LED_CRITICAL, ledBlinkState);
    }
  }

  if (prev != systemState) sendJSON();
}

// ============================================================
//  BUZZER
// ============================================================

void handleBuzzer() {
  unsigned long now = millis();
  int interval = 0;

  switch (systemState) {
    case STATE_STABLE:
    case STATE_ARMED:
      // No buzzer
      if (buzzerOn) { noTone(BUZZER); buzzerOn = false; }
      return;
    case STATE_WARNING:
      interval = 1000; // slow beep
      break;
    case STATE_CRITICAL:
      interval = 200;  // fast beep
      break;
  }

  if (now - lastBuzzerToggle >= (unsigned long)interval) {
    lastBuzzerToggle = now;
    buzzerOn = !buzzerOn;
    if (buzzerOn) tone(BUZZER, buzzerOn ? 2000 : 1500, interval / 2);
    else noTone(BUZZER);
  }
}

// ============================================================
//  LED HELPER
// ============================================================

void setAllLEDs(bool armed, bool stable, bool warning, bool critical) {
  digitalWrite(LED_ARMED,    armed    ? HIGH : LOW);
  digitalWrite(LED_STABLE,   stable   ? HIGH : LOW);
  if (!((systemState == STATE_WARNING) || (systemState == STATE_CRITICAL))) {
    digitalWrite(LED_WARNING,  warning  ? HIGH : LOW);
    digitalWrite(LED_CRITICAL, critical ? HIGH : LOW);
  }
}

// ============================================================
//  RESET
// ============================================================

void resetSystem() {
  bed1Present    = true;
  bed2Present    = true;
  bed1Absent     = false;
  bed2Absent     = false;
  bed1AlertStart = 0;
  bed2AlertStart = 0;
  visitorPending = false;
  visitorCount   = 0;
  alertCount     = 0;
  systemState    = STATE_ARMED;

  noTone(BUZZER);
  buzzerOn = false;

  // Flash all LEDs 3x to confirm reset
  for (int i = 0; i < 3; i++) {
    setAllLEDs(true, true, true, true);
    delay(150);
    setAllLEDs(false, false, false, false);
    delay(150);
  }
  tone(BUZZER, 1000, 200);
  delay(250);
  tone(BUZZER, 1500, 200);

  setAllLEDs(true, false, false, false);
  sendJSON();
}

// ============================================================
//  BOOT SEQUENCE
// ============================================================

void bootSequence() {
  // LED chase
  int leds[] = {LED_ARMED, LED_STABLE, LED_WARNING, LED_CRITICAL};
  for (int i = 0; i < 4; i++) {
    digitalWrite(leds[i], HIGH);
    delay(150);
  }
  for (int i = 0; i < 4; i++) {
    digitalWrite(leds[i], LOW);
    delay(150);
  }
  tone(BUZZER, 1200, 100);
  delay(150);
  tone(BUZZER, 1600, 100);
  delay(150);
  tone(BUZZER, 2000, 200);
  delay(300);
}

// ============================================================
//  JSON OUTPUT  (parsed by Java bridge)
// ============================================================

void sendJSON() {
  // Determine state string
  const char* stateStr;
  switch (systemState) {
    case STATE_STABLE:   stateStr = "STABLE";   break;
    case STATE_WARNING:  stateStr = "WARNING";  break;
    case STATE_CRITICAL: stateStr = "CRITICAL"; break;
    default:             stateStr = "ARMED";    break;
  }

  // Determine individual bed alerts
  const char* bed1str = bed1Absent ? "ALERT" : "OK";
  const char* bed2str = bed2Absent ? "ALERT" : "OK";

  Serial.print("{");
  Serial.print("\"type\":\"update\",");
  Serial.print("\"state\":\""); Serial.print(stateStr); Serial.print("\",");
  Serial.print("\"bed1\":\"");  Serial.print(bed1str);  Serial.print("\",");
  Serial.print("\"bed2\":\"");  Serial.print(bed2str);  Serial.print("\",");
  Serial.print("\"visitors\":");   Serial.print(visitorCount);  Serial.print(",");
  Serial.print("\"alerts\":");     Serial.print(alertCount);    Serial.print(",");
  Serial.print("\"ultraDist\":");  Serial.print(lastUltraDist); Serial.print(",");
  Serial.print("\"uptime\":");     Serial.print(millis());      Serial.print(",");
  Serial.print("\"btnReset\":false");
  Serial.println("}");
}

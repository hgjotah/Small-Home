/*
  SmartPanel C6 — Waveshare ESP32-C6-LCD-1.47 + 3 buttons
  ========================================================

  Hardware target:
    Waveshare ESP32-C6-LCD-1.47 (non-touch)
    ST7789 172x320 integrated LCD, used in 320x172 landscape mode

  External buttons (button -> GND, INPUT_PULLUP):
    LEFT   -> GPIO3
    CENTER -> GPIO23
    RIGHT  -> GPIO0

  Integrated LCD pins (Waveshare official):
    MOSI -> GPIO6
    SCLK -> GPIO7
    CS   -> GPIO14
    DC   -> GPIO15
    RST  -> GPIO21
    BL   -> GPIO22

  Main behavior:
    - Android <-> SmartPanel communication is BLE only.
    - Wi-Fi is used only by the ESP32-C6 for NTP, Home Assistant and CoinMarketCap.
    - BLE uses a bonded, MITM-protected secure connection with a 6-digit passkey
      generated once and stored in NVS. Security callbacks report pairing failures.
    - BLE protocol is newline-delimited JSON over two custom characteristics.
      Payloads are split into 18-byte chunks, so it works even with the minimum ATT MTU.
    - TX messages are queued until Android has subscribed to notifications, avoiding
      hello_ack/status loss during the GATT subscription race.
    - Advertising is restarted only by BLEServer::advertiseOnDisconnect(true), avoiding
      the double-restart race that existed in the previous firmware.
    - Entire UI is landscape (320x172): home, menus, AI keyboard and Flappy Bird.
    - Locked home: local time + cached crypto quote + status.
    - CoinMarketCap NEVER refreshes automatically. LEFT+CENTER refreshes manually.
    - Secret unlock: CENTER -> LEFT -> RIGHT, no final confirmation.
    - LEFT+RIGHT = Back.
    - Auto-lock after 60 seconds of inactivity, but screen stays on.
    - Press all three buttons together = lock + backlight off from any screen.
      Any button wakes it; that wake press is fully ignored.
    - Local notes: up to 8 persistent notes, each with a title and content. Notes
      are created, edited, read and scrolled entirely on the panel without BLE/Wi-Fi.
    - Notifications: last 10, sent from Android through BLE.
      CENTER on notification asks Android to mark-as-read/dismiss and removes it locally.
    - Home Assistant: ESP connects directly over Wi-Fi and uses the official REST API:
      /api/, /api/states/<entity>, /api/services/<entity-domain>/<service>.
    - Android may catalogue any valid Home Assistant entity as an ON/OFF control or
      thermostat and assign a panel-only display name. Home Assistant is not renamed.
    - ON/OFF controls use turn_on/turn_off in the selected entity's own domain.
    - Thermostat: target temperature only, steps of 0.5 C.
    - AI: text is typed on the panel; Android calls OpenRouter and sends the response by BLE.
      Session context is maintained by Android until leaving the AI screen.
    - Flappy Bird: fully offline mini-game. CENTER = flap, RIGHT = restart after game over,
      LEFT+RIGHT = exit. High score is stored in NVS.

  Required Arduino environment:
    - Board package: "esp32 by Espressif Systems", ESP32 Arduino core 3.x
    - Board: ESP32C6 Dev Module
    - Partition Scheme: Huge APP (3MB No OTA/1MB SPIFFS)
    - Libraries:
        * GFX Library for Arduino (Arduino_GFX)
        * ArduinoJson 7.x
      BLE, WiFi, HTTPClient, Preferences and WiFiClientSecure come with the ESP32 core.

  Important:
    - This board is 2.4 GHz Wi-Fi only.
    - Default backlight is deliberately capped around 43%. Waveshare recommends <= 50%
      for prolonged use.
    - HTTPS is encrypted but this firmware uses setInsecure() for HTTPS clients so it
      can work with Home Assistant self-signed certificates. For hardened deployments,
      install CA certificates instead.

  BLE protocol version: 2
*/

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <Arduino_GFX_Library.h>

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLESecurity.h>

#include <time.h>
#include <sys/time.h>
#include <math.h>

// -----------------------------------------------------------------------------
// Hardware
// -----------------------------------------------------------------------------
static const uint8_t PIN_LEFT   = 3;
static const uint8_t PIN_CENTER = 23;
static const uint8_t PIN_RIGHT  = 0;

static const uint8_t LCD_MOSI = 6;
static const uint8_t LCD_SCLK = 7;
static const uint8_t LCD_CS   = 14;
static const uint8_t LCD_DC   = 15;
static const uint8_t LCD_RST  = 21;
static const uint8_t LCD_BL   = 22;

static const int16_t PANEL_RAW_W = 172;
static const int16_t PANEL_RAW_H = 320;
static const int16_t LCD_W = 320;   // logical width after rotation=3
static const int16_t LCD_H = 172;   // logical height after rotation=3
static const uint8_t BACKLIGHT_DEFAULT = 110; // 0..255, ~43%
static const bool LCD_INVERT_COLORS = false;

Arduino_DataBus *displayBus = new Arduino_ESP32SPI(
  LCD_DC, LCD_CS, LCD_SCLK, LCD_MOSI, GFX_NOT_DEFINED
);

Arduino_GFX *gfx = new Arduino_ST7789(
  displayBus,
  LCD_RST,
  3,        // LANDSCAPE rotated 180 deg vs previous orientation; still 320x172
  false,
  PANEL_RAW_W,
  PANEL_RAW_H,
  34, 0,
  34, 0
);

// -----------------------------------------------------------------------------
// Palette — deliberately restrained / dark, matching the Android app direction
// -----------------------------------------------------------------------------
static const uint16_t C_BG       = RGB565(10, 14, 20);
static const uint16_t C_SURFACE  = RGB565(17, 24, 33);
static const uint16_t C_SURFACE2 = RGB565(24, 34, 45);
static const uint16_t C_TEXT     = RGB565(242, 245, 247);
static const uint16_t C_MUTED    = RGB565(154, 167, 181);
static const uint16_t C_MINT     = RGB565(99, 230, 190);
static const uint16_t C_BLUE     = RGB565(124, 131, 255);
static const uint16_t C_PURPLE   = RGB565(167, 139, 250);
static const uint16_t C_GOLD     = RGB565(245, 185, 66);
static const uint16_t C_GREEN    = RGB565(74, 222, 128);
static const uint16_t C_RED      = RGB565(255, 107, 107);
static const uint16_t C_SKY      = RGB565(67, 167, 255);
static const uint16_t C_WHITE    = RGB565(255, 255, 255);
static const uint16_t C_BLACK    = RGB565(0, 0, 0);
static const uint16_t C_PIPE     = RGB565(84, 194, 105);
static const uint16_t C_PIPE_DK  = RGB565(39, 128, 63);
static const uint16_t C_BIRD     = RGB565(255, 214, 74);
static const uint16_t C_BIRD_OR  = RGB565(255, 145, 54);

// -----------------------------------------------------------------------------
// Protocol / limits
// -----------------------------------------------------------------------------
static const uint8_t  PROTOCOL_VERSION = 2;
static const uint8_t  MAX_LIGHTS = 10;
static const uint8_t  MAX_NOTIFICATIONS = 10;
static const uint8_t  MAX_NOTES = 8;
static const uint8_t  MAX_NOTE_TITLE = 36;
static const uint16_t MAX_NOTE_CONTENT = 900;
static const uint8_t  NOTE_MAX_LINES = 120;
static const uint8_t  NOTE_LINES_PER_PAGE = 9;
static const uint16_t MAX_AI_QUERY = 320;
static const uint16_t MAX_AI_RESPONSE = 1800;
static const uint8_t  AI_MAX_LINES = 110;
static const uint8_t  AI_LINES_PER_PAGE = 11;

// Guaranteed to fit inside the minimum ATT payload (20 bytes).
static const uint8_t BLE_CHUNK_BYTES = 18;

// Custom BLE service.
static const char *BLE_SERVICE_UUID = "98d56a10-7c6d-4f5d-9af0-5a6b26aa1000";
static const char *BLE_RX_UUID      = "98d56a10-7c6d-4f5d-9af0-5a6b26aa1001"; // Android -> ESP
static const char *BLE_TX_UUID      = "98d56a10-7c6d-4f5d-9af0-5a6b26aa1002"; // ESP -> Android

// -----------------------------------------------------------------------------
// Timings
// -----------------------------------------------------------------------------
static const uint32_t AUTO_LOCK_MS        = 60UL * 1000UL;
static const uint32_t MOBILE_TIMEOUT_MS   = 45UL * 1000UL;
static const uint32_t WIFI_RETRY_MS       = 12UL * 1000UL;
static const uint32_t BUTTON_DEBOUNCE_MS  = 22UL;
static const uint32_t CHORD_WINDOW_MS     = 90UL;
static const uint32_t GAME_FRAME_MS       = 33UL; // ~30 FPS

// -----------------------------------------------------------------------------
// Persistent configuration
// -----------------------------------------------------------------------------
struct PanelConfig {
  String ssid;
  String wifiPassword;

  String deviceName = "SmartPanel C6";

  String haBaseUrl;
  String haToken;

  String cmcApiKey;
  uint32_t cmcId = 4424;
  String cmcSymbol = "XDAG";
  String fiat = "EUR";

  String timezone = "CET-1CEST,M3.5.0,M10.5.0/3";

  uint8_t brightness = BACKLIGHT_DEFAULT;

  uint8_t lightCount = 0;
  String lightIds[MAX_LIGHTS];
  String lightNames[MAX_LIGHTS];

  String climateId;
  String climateName = "Termostato";
};

Preferences prefs;
PanelConfig cfg;
uint32_t blePasskey = 0;
uint32_t flappyHighScore = 0;

// -----------------------------------------------------------------------------
// Notifications
// -----------------------------------------------------------------------------
struct NotificationItem {
  String key;
  String app;
  String title;
  String text;
  String timeText;
};

NotificationItem notifications[MAX_NOTIFICATIONS];
uint8_t notificationCount = 0;
uint8_t notificationIndex = 0;

// -----------------------------------------------------------------------------
// Local notes (stored only in ESP32 NVS)
// -----------------------------------------------------------------------------
struct LocalNote {
  String title;
  String content;
};

LocalNote notes[MAX_NOTES];
uint8_t noteCount = 0;
uint8_t noteIndex = 0;
uint8_t noteEditingIndex = 0;
bool noteEditingNew = false;
bool noteEditingTitle = true;
bool noteUpper = false;
uint8_t noteKeyboardIndex = 0;
String noteDraftTitle;
String noteDraftContent;
String noteLines[NOTE_MAX_LINES];
uint8_t noteLineCount = 0;
uint8_t notePage = 0;

// -----------------------------------------------------------------------------
// UI state
// -----------------------------------------------------------------------------
enum ScreenState : uint8_t {
  SCREEN_HOME_LOCKED,
  SCREEN_MENU,
  SCREEN_NOTIFICATIONS,
  SCREEN_LIGHTS,
  SCREEN_CLIMATE,
  SCREEN_AI_KEYBOARD,
  SCREEN_AI_WAIT,
  SCREEN_AI_RESPONSE,
  SCREEN_FLAPPY,
  SCREEN_NOTES_LIST,
  SCREEN_NOTE_EDITOR,
  SCREEN_NOTE_VIEW
};

ScreenState screen = SCREEN_HOME_LOCKED;
bool unlocked = false;
uint8_t menuIndex = 0;
static const uint8_t MENU_COUNT = 6;
static const uint8_t UNLOCK_SEQ[3] = {0x02, 0x01, 0x04}; // CENTER, LEFT, RIGHT
uint8_t unlockPos = 0;

uint32_t lastInteractionMs = 0;
uint32_t lastUiDrawMs = 0;

// Non-game screens are event-driven to avoid visible TFT flicker.
// uiDirty is set whenever something on screen changes.
bool uiDirty = true;
bool toastWasVisible = false;
int lastHomeMinute = -1;

// Display sleep is explicit only; inactivity never turns display off.
bool displaySleeping = false;
bool sleepCanWake = false;
bool discardButtonsUntilRelease = false;

// -----------------------------------------------------------------------------
// BLE state
// -----------------------------------------------------------------------------
BLEServer *bleServer = nullptr;
BLECharacteristic *bleRx = nullptr;
BLECharacteristic *bleTx = nullptr;

volatile bool bleConnected = false;
volatile bool bleAuthenticated = false;
volatile bool bleTxSubscribed = false;

volatile bool bleAuthEventPending = false;
volatile bool bleAuthEventSuccess = false;
volatile bool blePasskeyEventPending = false;
volatile uint32_t blePasskeyEventValue = 0;
volatile bool bleTransportResetPending = false;

uint32_t lastMobileSeenMs = 0;

// Incoming stream: Android sends chunks, logical JSON messages end with '\n'.
String bleRxStream;
String pendingBleLine;
bool pendingBleLineReady = false;

// Outgoing messages are queued until Android has enabled notifications on TX.
// This prevents losing hello_ack/status during the GATT subscription race.
static const uint8_t BLE_TX_QUEUE_MAX = 8;
String bleTxQueue[BLE_TX_QUEUE_MAX];
uint8_t bleTxQueueHead = 0;
uint8_t bleTxQueueTail = 0;
uint8_t bleTxQueueCount = 0;

// -----------------------------------------------------------------------------
// Crypto
// -----------------------------------------------------------------------------
bool cryptoKnown = false;
double cryptoPrice = 0.0;
double cryptoChange24h = 0.0;
bool cryptoChangeKnown = false;
String cryptoLastError;

// -----------------------------------------------------------------------------
// Home Assistant
// -----------------------------------------------------------------------------
uint8_t lightIndex = 0;
bool lightKnown = false;
bool lightOn = false;

float climateTarget = NAN;
float climateMin = 5.0f;
float climateMax = 35.0f;
bool climateKnown = false;

String haLastError;

// -----------------------------------------------------------------------------
// AI
// -----------------------------------------------------------------------------
String aiSessionId;
String aiQuery;
bool aiUpper = false;
uint8_t keyboardIndex = 0;
uint32_t aiRequestId = 0;

String aiResponse;
String aiError;
String aiLines[AI_MAX_LINES];
uint8_t aiLineCount = 0;
uint8_t aiPage = 0;

// -----------------------------------------------------------------------------
// Toast
// -----------------------------------------------------------------------------
String toastText;
uint32_t toastUntilMs = 0;

// -----------------------------------------------------------------------------
// Buttons
// -----------------------------------------------------------------------------
uint8_t stableMask = 0;
uint8_t lastRawMask = 0;
uint32_t rawChangedAt = 0;

bool gestureCollecting = false;
uint8_t gestureMask = 0;
uint32_t gestureStartedAt = 0;
bool gestureEmitted = false;

// -----------------------------------------------------------------------------
// Flappy Bird state
// -----------------------------------------------------------------------------
struct Pipe {
  float x = 0;
  int16_t gapY = 0;
  bool scored = false;
};

bool gameRunning = false;
bool gameOver = false;
float birdY = 150.0f;
float birdV = 0.0f;
Pipe pipes[2];
uint32_t gameScore = 0;
uint32_t gameLastFrameMs = 0;

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------
String chipHex() {
  uint64_t mac = ESP.getEfuseMac();
  char buf[13];
  snprintf(buf, sizeof(buf), "%04X%08X",
           (uint16_t)(mac >> 32),
           (uint32_t)(mac & 0xFFFFFFFFULL));
  return String(buf);
}

String shortChip() {
  String s = chipHex();
  return s.substring(s.length() - 4);
}

String normalizeBaseUrl(String url) {
  url.trim();
  while (url.endsWith("/")) url.remove(url.length() - 1);
  return url;
}

bool validEntityId(const String &entityId) {
  int separator = entityId.indexOf('.');
  return separator > 0 && separator < (int)entityId.length() - 1;
}

String entityDomain(const String &entityId) {
  int separator = entityId.indexOf('.');
  if (separator <= 0) return "";
  return entityId.substring(0, separator);
}

String asciiSafe(const String &src) {
  String out;
  out.reserve(src.length());

  for (size_t i = 0; i < src.length(); i++) {
    uint8_t c = (uint8_t)src[i];

    if (c < 0x80) {
      out += (char)c;
      continue;
    }

    // Common Spanish UTF-8 characters -> readable ASCII.
    if (c == 0xC3 && i + 1 < src.length()) {
      uint8_t n = (uint8_t)src[++i];
      switch (n) {
        case 0x81: case 0xA1: out += 'a'; break;
        case 0x89: case 0xA9: out += 'e'; break;
        case 0x8D: case 0xAD: out += 'i'; break;
        case 0x93: case 0xB3: out += 'o'; break;
        case 0x9A: case 0xBA: case 0x9C: case 0xBC: out += 'u'; break;
        case 0x91: case 0xB1: out += 'n'; break;
        default: out += '?'; break;
      }
      continue;
    }

    out += '?';
  }

  return out;
}

String ellipsize(const String &text, uint16_t maxChars) {
  String s = asciiSafe(text);
  if (s.length() <= maxChars) return s;
  if (maxChars <= 3) return s.substring(0, maxChars);
  return s.substring(0, maxChars - 3) + "...";
}

String formatPrice(double p) {
  if (p >= 100000.0) return String(p, 0);
  if (p >= 10000.0)  return String(p, 0);
  if (p >= 1000.0)   return String(p, 1);
  if (p >= 1.0)      return String(p, 2);
  if (p >= 0.01)     return String(p, 4);
  return String(p, 6);
}

void showToast(const String &text, uint32_t durationMs = 1500) {
  toastText = text;
  toastUntilMs = millis() + durationMs;
  uiDirty = true;
}

bool mobileConnected() {
  return bleConnected &&
         bleAuthenticated &&
         bleTxSubscribed &&
         lastMobileSeenMs != 0 &&
         (millis() - lastMobileSeenMs) < MOBILE_TIMEOUT_MS;
}

void setBacklight(uint8_t value) {
  if (value > 127) value = 127; // hard safety cap: <= ~50%
  ledcWrite(LCD_BL, value);
}

void flushIfNeeded() {
  // Direct display object; no canvas flush required.
}

void centerText(const String &text, int16_t y, uint8_t size, uint16_t color = C_TEXT) {
  String safe = asciiSafe(text);
  gfx->setTextSize(size);
  gfx->setTextColor(color);
  int16_t x = (LCD_W - (int16_t)safe.length() * 6 * size) / 2;
  if (x < 2) x = 2;
  gfx->setCursor(x, y);
  gfx->print(safe);
}

void drawStatusDot(int16_t x, int16_t y, bool ok, uint16_t okColor = C_MINT) {
  gfx->fillCircle(x, y, 4, ok ? okColor : C_RED);
  gfx->drawCircle(x, y, 5, C_SURFACE2);
}

void drawCard(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t fill = C_SURFACE) {
  gfx->fillRoundRect(x, y, w, h, 9, fill);
}

void drawDivider(int16_t y) {
  gfx->drawFastHLine(10, y, LCD_W - 20, C_SURFACE2);
}

void drawHeader(const String &title, uint16_t accent = C_MINT) {
  gfx->fillRect(0, 0, LCD_W, 29, C_BG);
  gfx->fillRoundRect(10, 7, 5, 16, 3, accent);
  gfx->setTextSize(2);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(23, 7);
  gfx->print(ellipsize(title, 19));

  drawStatusDot(285, 14, WiFi.status() == WL_CONNECTED, C_MINT);
  drawStatusDot(306, 14, mobileConnected(), C_BLUE);
}

void drawFooterHint(const String &left, const String &center, const String &right) {
  gfx->fillRect(0, 150, LCD_W, 22, C_BG);
  gfx->drawFastHLine(8, 149, LCD_W - 16, C_SURFACE2);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);

  gfx->setCursor(10, 158);
  gfx->print(left);

  int16_t cx = (LCD_W - (int16_t)center.length() * 6) / 2;
  gfx->setCursor(cx, 158);
  gfx->print(center);

  int16_t rx = LCD_W - 10 - (int16_t)right.length() * 6;
  gfx->setCursor(rx, 158);
  gfx->print(right);
}

void drawToastOverlay() {
  if (toastUntilMs == 0 || (int32_t)(toastUntilMs - millis()) <= 0) return;

  String t = ellipsize(toastText, 42);
  int16_t desiredW = (int16_t)t.length() * 6 + 20;
  int16_t w = (desiredW < (LCD_W - 20)) ? desiredW : (LCD_W - 20);
  int16_t x = (LCD_W - w) / 2;

  gfx->fillRoundRect(x, 121, w, 24, 8, C_SURFACE2);
  gfx->drawRoundRect(x, 121, w, 24, 8, C_BLUE);
  gfx->setTextSize(1);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(x + 10, 129);
  gfx->print(t);
}

// -----------------------------------------------------------------------------
// Persistence (NVS Preferences)
// -----------------------------------------------------------------------------
void loadConfig() {
  prefs.begin("smartpanel", false);

  cfg.ssid         = prefs.getString("ssid", "");
  cfg.wifiPassword = prefs.getString("wifiPass", "");
  cfg.deviceName   = prefs.getString("devName", "SmartPanel C6");
  cfg.haBaseUrl    = prefs.getString("haUrl", "");
  cfg.haToken      = prefs.getString("haToken", "");
  cfg.cmcApiKey    = prefs.getString("cmcKey", "");
  cfg.cmcId        = prefs.getULong("cmcId", 4424);
  cfg.cmcSymbol    = prefs.getString("cmcSym", "XDAG");
  cfg.fiat         = prefs.getString("fiat", "EUR");
  cfg.timezone     = prefs.getString("tz", "CET-1CEST,M3.5.0,M10.5.0/3");
  cfg.brightness   = prefs.getUChar("bright", BACKLIGHT_DEFAULT);
  if (cfg.brightness > 127) cfg.brightness = 127;

  // One-time migration for the old inconsistent default (CoinMarketCap ID 1
  // paired with XDAG, or the older BTC default). User-selected values are left
  // untouched after this marker is stored.
  if (!prefs.getBool("cmcXdagV1", false)) {
    if (cfg.cmcId == 1 &&
        (cfg.cmcSymbol.equalsIgnoreCase("XDAG") ||
         cfg.cmcSymbol.equalsIgnoreCase("BTC"))) {
      cfg.cmcId = 4424;
      cfg.cmcSymbol = "XDAG";
      prefs.putULong("cmcId", cfg.cmcId);
      prefs.putString("cmcSym", cfg.cmcSymbol);
    }
    prefs.putBool("cmcXdagV1", true);
  }

  cfg.climateId   = prefs.getString("climId", "");
  cfg.climateName = prefs.getString("climName", "Termostato");

  cfg.lightCount = prefs.getUChar("lightCnt", 0);
  if (cfg.lightCount > MAX_LIGHTS) cfg.lightCount = MAX_LIGHTS;

  for (uint8_t i = 0; i < cfg.lightCount; i++) {
    String kId = "lId" + String(i);
    String kNm = "lNm" + String(i);
    cfg.lightIds[i] = prefs.getString(kId.c_str(), "");
    cfg.lightNames[i] = prefs.getString(kNm.c_str(), "Luz");
  }

  blePasskey = prefs.getULong("blePin", 0);
  if (blePasskey < 100000 || blePasskey > 999999) {
    blePasskey = 100000 + (esp_random() % 900000);
    prefs.putULong("blePin", blePasskey);
  }

  flappyHighScore = prefs.getULong("flapHi", 0);

  noteCount = prefs.getUChar("noteCnt", 0);
  if (noteCount > MAX_NOTES) noteCount = MAX_NOTES;
  for (uint8_t i = 0; i < noteCount; i++) {
    String titleKey = "nTitle" + String(i);
    String bodyKey = "nBody" + String(i);
    notes[i].title = prefs.getString(titleKey.c_str(), "Sin titulo");
    notes[i].content = prefs.getString(bodyKey.c_str(), "");
    if (notes[i].title.length() > MAX_NOTE_TITLE) {
      notes[i].title = notes[i].title.substring(0, MAX_NOTE_TITLE);
    }
    if (notes[i].content.length() > MAX_NOTE_CONTENT) {
      notes[i].content = notes[i].content.substring(0, MAX_NOTE_CONTENT);
    }
  }
}

void saveNotes() {
  prefs.putUChar("noteCnt", noteCount);
  for (uint8_t i = 0; i < MAX_NOTES; i++) {
    String titleKey = "nTitle" + String(i);
    String bodyKey = "nBody" + String(i);
    if (i < noteCount) {
      prefs.putString(titleKey.c_str(), notes[i].title);
      prefs.putString(bodyKey.c_str(), notes[i].content);
    } else {
      prefs.remove(titleKey.c_str());
      prefs.remove(bodyKey.c_str());
    }
  }
}

void saveConfig() {
  prefs.putString("ssid", cfg.ssid);
  prefs.putString("wifiPass", cfg.wifiPassword);
  prefs.putString("devName", cfg.deviceName);
  prefs.putString("haUrl", cfg.haBaseUrl);
  prefs.putString("haToken", cfg.haToken);
  prefs.putString("cmcKey", cfg.cmcApiKey);
  prefs.putULong("cmcId", cfg.cmcId);
  prefs.putString("cmcSym", cfg.cmcSymbol);
  prefs.putString("fiat", cfg.fiat);
  prefs.putString("tz", cfg.timezone);
  prefs.putUChar("bright", cfg.brightness);
  prefs.putString("climId", cfg.climateId);
  prefs.putString("climName", cfg.climateName);
  prefs.putUChar("lightCnt", cfg.lightCount);

  for (uint8_t i = 0; i < MAX_LIGHTS; i++) {
    String kId = "lId" + String(i);
    String kNm = "lNm" + String(i);
    if (i < cfg.lightCount) {
      prefs.putString(kId.c_str(), cfg.lightIds[i]);
      prefs.putString(kNm.c_str(), cfg.lightNames[i]);
    } else {
      prefs.remove(kId.c_str());
      prefs.remove(kNm.c_str());
    }
  }
}

void factoryResetConfig() {
  prefs.clear();

  cfg = PanelConfig();
  blePasskey = 100000 + (esp_random() % 900000);
  prefs.putULong("blePin", blePasskey);
  flappyHighScore = 0;

  delay(200);
  ESP.restart();
}

// -----------------------------------------------------------------------------
// Time / Wi-Fi
// -----------------------------------------------------------------------------
void startTimeSync() {
  configTzTime(
    cfg.timezone.c_str(),
    "pool.ntp.org",
    "time.google.com",
    "time.cloudflare.com"
  );
}

void connectWiFiNonBlocking() {
  if (cfg.ssid.isEmpty()) return;

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(cfg.ssid.c_str(), cfg.wifiPassword.c_str());
}

void serviceWiFi() {
  static wl_status_t lastStatus = WL_NO_SHIELD;
  static uint32_t lastRetry = 0;

  wl_status_t st = WiFi.status();

  if (st == WL_CONNECTED && lastStatus != WL_CONNECTED) {
    startTimeSync();
    showToast("Wi-Fi conectado", 1200);
  }

  if (st != WL_CONNECTED &&
      !cfg.ssid.isEmpty() &&
      millis() - lastRetry >= WIFI_RETRY_MS) {
    lastRetry = millis();
    WiFi.disconnect();
    WiFi.begin(cfg.ssid.c_str(), cfg.wifiPassword.c_str());
  }

  lastStatus = st;
}

// -----------------------------------------------------------------------------
// BLE transport
// -----------------------------------------------------------------------------
void bleClearTxQueue() {
  for (uint8_t i = 0; i < BLE_TX_QUEUE_MAX; i++) {
    bleTxQueue[i] = "";
  }
  bleTxQueueHead = 0;
  bleTxQueueTail = 0;
  bleTxQueueCount = 0;
}

bool bleQueueRawLine(const String &lineWithoutNewline) {
  if (bleTxQueueCount >= BLE_TX_QUEUE_MAX) {
    Serial.println("[BLE] TX queue full; dropping oldest message");
    bleTxQueue[bleTxQueueHead] = "";
    bleTxQueueHead = (bleTxQueueHead + 1) % BLE_TX_QUEUE_MAX;
    bleTxQueueCount--;
  }

  bleTxQueue[bleTxQueueTail] = lineWithoutNewline;
  bleTxQueueTail = (bleTxQueueTail + 1) % BLE_TX_QUEUE_MAX;
  bleTxQueueCount++;
  return true;
}

void bleNotifyRawLineNow(const String &lineWithoutNewline) {
  if (!bleConnected || !bleTxSubscribed || bleTx == nullptr) return;

  String wire = lineWithoutNewline;
  wire += '\n';

  for (size_t i = 0; i < wire.length(); i += BLE_CHUNK_BYTES) {
    if (!bleConnected || !bleTxSubscribed) return;

    size_t remaining = wire.length() - i;
    size_t n = (remaining < BLE_CHUNK_BYTES) ? remaining : BLE_CHUNK_BYTES;
    String chunk = wire.substring(i, i + n);

    bleTx->setValue(chunk);
    bleTx->notify();

    // A small pacing delay avoids flooding Android/NimBLE while still being fast.
    delay(10);
  }
}

void bleSendRawLine(const String &lineWithoutNewline) {
  if (!bleConnected || bleTx == nullptr) return;

  if (!bleTxSubscribed) {
    bleQueueRawLine(lineWithoutNewline);
    return;
  }

  bleNotifyRawLineNow(lineWithoutNewline);
}

void serviceBleTxQueue() {
  if (!bleConnected || !bleTxSubscribed || bleTx == nullptr) return;

  while (bleTxQueueCount > 0 && bleConnected && bleTxSubscribed) {
    String line = bleTxQueue[bleTxQueueHead];
    bleTxQueue[bleTxQueueHead] = "";
    bleTxQueueHead = (bleTxQueueHead + 1) % BLE_TX_QUEUE_MAX;
    bleTxQueueCount--;

    bleNotifyRawLineNow(line);
  }
}

void bleSendJson(JsonDocument &doc) {
  String out;
  serializeJson(doc, out);
  bleSendRawLine(out);
}

void sendStatus() {
  JsonDocument doc;
  doc["type"] = "status";
  doc["protocol"] = PROTOCOL_VERSION;
  doc["chip_id"] = chipHex();
  doc["device_name"] = cfg.deviceName;
  doc["wifi_connected"] = (WiFi.status() == WL_CONNECTED);
  doc["wifi_rssi"] = (WiFi.status() == WL_CONNECTED) ? WiFi.RSSI() : 0;
  doc["ble_connected"] = bleConnected;
  doc["ble_authenticated"] = bleAuthenticated;
  doc["ble_tx_subscribed"] = bleTxSubscribed;
  doc["notification_count"] = notificationCount;
  doc["unlocked"] = unlocked;
  doc["screen"] = (uint8_t)screen;
  doc["has_ha"] = !cfg.haBaseUrl.isEmpty() && !cfg.haToken.isEmpty();
  doc["has_cmc"] = !cfg.cmcApiKey.isEmpty();
  doc["brightness"] = cfg.brightness;
  doc["flappy_high_score"] = flappyHighScore;
  bleSendJson(doc);
}

void sendConfigState() {
  JsonDocument doc;
  doc["type"] = "config_state";
  doc["protocol"] = PROTOCOL_VERSION;
  doc["device_name"] = cfg.deviceName;
  doc["chip_id"] = chipHex();

  doc["wifi_ssid"] = cfg.ssid;
  doc["wifi_configured"] = !cfg.ssid.isEmpty();

  doc["ha_base_url"] = cfg.haBaseUrl;
  doc["has_ha_token"] = !cfg.haToken.isEmpty();

  doc["cmc_id"] = cfg.cmcId;
  doc["cmc_symbol"] = cfg.cmcSymbol;
  doc["fiat"] = cfg.fiat;
  doc["has_cmc_key"] = !cfg.cmcApiKey.isEmpty();

  doc["timezone"] = cfg.timezone;
  doc["brightness"] = cfg.brightness;

  doc["climate_id"] = cfg.climateId;
  doc["climate_name"] = cfg.climateName;

  JsonArray lights = doc["lights"].to<JsonArray>();
  for (uint8_t i = 0; i < cfg.lightCount; i++) {
    JsonObject o = lights.add<JsonObject>();
    o["id"] = cfg.lightIds[i];
    o["name"] = cfg.lightNames[i];
  }

  bleSendJson(doc);
}

class PanelSecurityCallbacks : public BLESecurityCallbacks {
public:
  bool onSecurityRequest() override {
    Serial.println("[BLE] Security request accepted");
    return true;
  }

  void onPassKeyNotify(uint32_t passkey) override {
    Serial.printf("[BLE] Pairing passkey: %06lu\n", (unsigned long)passkey);
    blePasskeyEventValue = passkey;
    blePasskeyEventPending = true;
  }

  bool onConfirmPIN(uint32_t pin) override {
    // With ESP_IO_CAP_OUT Android should normally use passkey entry, not
    // numeric comparison. If a phone negotiates numeric comparison anyway,
    // accept it and show the number on the panel for visibility.
    Serial.printf("[BLE] Numeric comparison PIN: %06lu\n", (unsigned long)pin);
    blePasskeyEventValue = pin;
    blePasskeyEventPending = true;
    return true;
  }

#if defined(CONFIG_NIMBLE_ENABLED)
  void onAuthenticationComplete(ble_gap_conn_desc *desc) override {
    bool encrypted = desc != nullptr && desc->sec_state.encrypted;
    bool authenticated = desc != nullptr && desc->sec_state.authenticated;
    bool bonded = desc != nullptr && desc->sec_state.bonded;

    bleAuthEventSuccess = encrypted && authenticated;
    bleAuthenticated = bleAuthEventSuccess;
    bleAuthEventPending = true;

    Serial.printf(
      "[BLE] Authentication complete: encrypted=%d authenticated=%d bonded=%d\n",
      encrypted ? 1 : 0,
      authenticated ? 1 : 0,
      bonded ? 1 : 0
    );
  }
#endif

#if defined(CONFIG_BLUEDROID_ENABLED)
  void onAuthenticationComplete(esp_ble_auth_cmpl_t desc) override {
    bleAuthEventSuccess = desc.success;
    bleAuthenticated = desc.success;
    bleAuthEventPending = true;

    if (desc.success) {
      Serial.println("[BLE] Authentication complete: success");
    } else {
      Serial.printf("[BLE] Authentication failed, reason=%d\n", desc.fail_reason);
    }
  }
#endif
};

class PanelServerCallbacks : public BLEServerCallbacks {
public:
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    bleAuthenticated = false;
    bleTxSubscribed = false;
    lastMobileSeenMs = millis();

    // Do not clear RX/TX buffers here: Android can start GATT operations
    // immediately after onConnect, and a deferred clear could erase hello.
    Serial.println("[BLE] GATT client connected; authentication starting");
  }

#if defined(CONFIG_NIMBLE_ENABLED)
  void onConnect(BLEServer *server, ble_gap_conn_desc *desc) override {
    if (desc != nullptr) {
      bool encrypted = desc->sec_state.encrypted;
      bool authenticated = desc->sec_state.authenticated;
      bool bonded = desc->sec_state.bonded;

      // On a reconnect to an already bonded phone the link may already be
      // secured by the time this callback runs.
      if (encrypted && authenticated) {
        bleAuthenticated = true;
      }

      Serial.printf(
        "[BLE] Connect security state: encrypted=%d authenticated=%d bonded=%d\n",
        encrypted ? 1 : 0,
        authenticated ? 1 : 0,
        bonded ? 1 : 0
      );
    }
  }
#endif

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    bleAuthenticated = false;
    bleTxSubscribed = false;
    lastMobileSeenMs = 0;
    bleTransportResetPending = true;

    // Do NOT call BLEDevice::startAdvertising() here.
    // advertiseOnDisconnect(true) already restarts advertising in BLEServer.
    Serial.println("[BLE] Client disconnected; advertising will restart automatically");
  }
};

class PanelRxCallbacks : public BLECharacteristicCallbacks {
public:
  void onWrite(BLECharacteristic *characteristic) override {
    String chunk = characteristic->getValue();
    if (chunk.isEmpty()) return;

    lastMobileSeenMs = millis();

    // Bound the stream so a malformed client cannot exhaust RAM.
    if (bleRxStream.length() + chunk.length() > 4096) {
      Serial.println("[BLE] RX stream overflow; resetting frame buffer");
      bleRxStream = "";
    }

    bleRxStream += chunk;

    int newline;
    while (!pendingBleLineReady &&
           (newline = bleRxStream.indexOf('\n')) >= 0) {
      pendingBleLine = bleRxStream.substring(0, newline);
      bleRxStream.remove(0, newline + 1);
      pendingBleLineReady = true;
    }
  }
};

class PanelTxCallbacks : public BLECharacteristicCallbacks {
public:
#if defined(CONFIG_NIMBLE_ENABLED)
  void onSubscribe(
    BLECharacteristic *characteristic,
    ble_gap_conn_desc *desc,
    uint16_t subValue
  ) override {
    bleTxSubscribed = (subValue & 0x01) != 0;
    Serial.printf(
      "[BLE] TX notifications %s\n",
      bleTxSubscribed ? "ENABLED" : "DISABLED"
    );
  }
#endif

  void onStatus(
    BLECharacteristic *characteristic,
    Status status,
    uint32_t code
  ) override {
    if (status == ERROR_GATT ||
        status == ERROR_NO_CLIENT ||
        status == ERROR_NO_SUBSCRIBER ||
        status == ERROR_NOTIFY_DISABLED) {
      Serial.printf("[BLE] TX notify status=%d code=%lu\n",
                    (int)status, (unsigned long)code);
    }
  }
};

void serviceBleEvents() {
  if (bleTransportResetPending) {
    bleTransportResetPending = false;

    bleRxStream = "";
    pendingBleLine = "";
    pendingBleLineReady = false;
    bleClearTxQueue();
    uiDirty = true;
  }

  if (blePasskeyEventPending) {
    noInterrupts();
    uint32_t pin = blePasskeyEventValue;
    blePasskeyEventPending = false;
    interrupts();

    char pinBuf[28];
    snprintf(pinBuf, sizeof(pinBuf), "BLE PIN %06lu", (unsigned long)pin);
    showToast(String(pinBuf), 8000);
  }

  if (bleAuthEventPending) {
    bool ok = bleAuthEventSuccess;
    bleAuthEventPending = false;

    showToast(
      ok ? "BLE emparejado correctamente" : "Error de autenticacion BLE",
      ok ? 1800 : 3500
    );
    uiDirty = true;
  }

  serviceBleTxQueue();
}

void setupBLE() {
  String bleName = "SmartPanel-C6-" + shortChip();

  Serial.println();
  Serial.println("========== SMARTPANEL BLE ==========");
  Serial.print("[BLE] Stack: ");
  Serial.println(BLEDevice::getBLEStackString());
  Serial.print("[BLE] Device name: ");
  Serial.println(bleName);
  Serial.printf("[BLE] Static passkey: %06lu\n", (unsigned long)blePasskey);
  Serial.println("[BLE] Service UUID: " + String(BLE_SERVICE_UUID));

  if (!BLEDevice::init(bleName)) {
    Serial.println("[BLE] ERROR: BLEDevice::init failed");
    showToast("ERROR iniciando BLE", 4000);
    return;
  }

  // The protocol always chunks at 18 bytes, so it works even if Android keeps
  // MTU 23. Advertising a larger local MTU is still useful when the phone asks.
  esp_err_t mtuResult = BLEDevice::setMTU(517);
  Serial.printf("[BLE] setMTU(517) result=%d\n", (int)mtuResult);

  BLESecurity *security = new BLESecurity();
  security->setPassKey(true, blePasskey);

  // Display Only is the correct model for SmartPanel: the ESP displays the
  // six-digit passkey and Android enters it.
  security->setCapability(ESP_IO_CAP_OUT);
  security->setAuthenticationMode(true, true, true); // bond + MITM + Secure Connections

  // ESP32-C6/NimBLE: start security immediately after GATT connection instead
  // of waiting for the first protected characteristic access/subscription.
  // This removes a race with Android's service-discovery/subscription flow.
  BLESecurity::setForceAuthentication(true);

  BLEDevice::setSecurityCallbacks(new PanelSecurityCallbacks());

  bleServer = BLEDevice::createServer();
  if (bleServer == nullptr) {
    Serial.println("[BLE] ERROR: createServer returned nullptr");
    showToast("ERROR creando BLE server", 4000);
    return;
  }

  bleServer->setCallbacks(new PanelServerCallbacks());
  bleServer->advertiseOnDisconnect(true);

  BLEService *service = bleServer->createService(BLE_SERVICE_UUID);
  if (service == nullptr) {
    Serial.println("[BLE] ERROR: createService returned nullptr");
    showToast("ERROR creando BLE service", 4000);
    return;
  }

  uint32_t rxProps =
    BLECharacteristic::PROPERTY_WRITE |
    BLECharacteristic::PROPERTY_WRITE_NR |
    BLECharacteristic::PROPERTY_WRITE_AUTHEN;

  uint32_t txProps =
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY |
    BLECharacteristic::PROPERTY_READ_AUTHEN;

  bleRx = service->createCharacteristic(BLE_RX_UUID, rxProps);
  bleTx = service->createCharacteristic(BLE_TX_UUID, txProps);

  if (bleRx == nullptr || bleTx == nullptr) {
    Serial.println("[BLE] ERROR: characteristic creation failed");
    showToast("ERROR creando BLE GATT", 4000);
    return;
  }

  // Bluedroid uses access permissions; NimBLE (ESP32-C6) uses *_AUTHEN
  // characteristic properties. Keeping both follows Espressif's cross-stack
  // recommendation.
  bleRx->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  bleTx->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);

  bleRx->setCallbacks(new PanelRxCallbacks());
  bleTx->setCallbacks(new PanelTxCallbacks());

  // Do not manually add BLE2902 on ESP32-C6/NimBLE. NimBLE automatically
  // creates the CCCD for a characteristic that has PROPERTY_NOTIFY.

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SERVICE_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);
  adv->setMaxPreferred(0x12);

  BLEDevice::startAdvertising();

  Serial.println("[BLE] Advertising started");
  Serial.println("====================================");
}

// -----------------------------------------------------------------------------
// HTTP helpers
// -----------------------------------------------------------------------------
bool performGet(
  const String &url,
  const String &bearer,
  const String &extraHeaderName,
  const String &extraHeaderValue,
  String &response,
  int &statusCode
) {
  HTTPClient http;
  http.setConnectTimeout(6000);
  http.setTimeout(8000);
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);

  if (url.startsWith("https://")) {
    WiFiClientSecure client;
    client.setInsecure();

    if (!http.begin(client, url)) return false;
    if (!bearer.isEmpty()) http.addHeader("Authorization", "Bearer " + bearer);
    if (!extraHeaderName.isEmpty()) http.addHeader(extraHeaderName, extraHeaderValue);
    http.addHeader("Accept", "application/json");

    statusCode = http.GET();
    response = statusCode > 0 ? http.getString() : "";
    http.end();
    return statusCode > 0;
  }

  WiFiClient client;
  if (!http.begin(client, url)) return false;
  if (!bearer.isEmpty()) http.addHeader("Authorization", "Bearer " + bearer);
  if (!extraHeaderName.isEmpty()) http.addHeader(extraHeaderName, extraHeaderValue);
  http.addHeader("Accept", "application/json");

  statusCode = http.GET();
  response = statusCode > 0 ? http.getString() : "";
  http.end();
  return statusCode > 0;
}

bool performPost(
  const String &url,
  const String &bearer,
  const String &body,
  String &response,
  int &statusCode
) {
  HTTPClient http;
  http.setConnectTimeout(6000);
  http.setTimeout(8000);
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);

  if (url.startsWith("https://")) {
    WiFiClientSecure client;
    client.setInsecure();

    if (!http.begin(client, url)) return false;
    if (!bearer.isEmpty()) http.addHeader("Authorization", "Bearer " + bearer);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("Accept", "application/json");

    statusCode = http.POST((uint8_t *)body.c_str(), body.length());
    response = statusCode > 0 ? http.getString() : "";
    http.end();
    return statusCode > 0;
  }

  WiFiClient client;
  if (!http.begin(client, url)) return false;
  if (!bearer.isEmpty()) http.addHeader("Authorization", "Bearer " + bearer);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Accept", "application/json");

  statusCode = http.POST((uint8_t *)body.c_str(), body.length());
  response = statusCode > 0 ? http.getString() : "";
  http.end();
  return statusCode > 0;
}

// -----------------------------------------------------------------------------
// CoinMarketCap
// -----------------------------------------------------------------------------
void drawLoading(const String &title, const String &subtitle, uint16_t accent) {
  gfx->fillScreen(C_BG);
  drawHeader(title, accent);

  gfx->fillCircle(74, 94, 28, C_SURFACE2);
  gfx->drawCircle(74, 94, 29, accent);
  gfx->drawArc(74, 94, 22, 25, 20, 140, accent);
  gfx->drawArc(74, 94, 22, 25, 200, 320, C_MUTED);

  gfx->setTextSize(2);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(120, 72);
  gfx->print(ellipsize(subtitle, 16));
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(121, 102);
  gfx->print("Espera un momento...");
}

bool refreshCrypto() {
  if (WiFi.status() != WL_CONNECTED) {
    cryptoLastError = "Sin Wi-Fi";
    return false;
  }

  if (cfg.cmcApiKey.isEmpty() || cfg.cmcId == 0) {
    cryptoLastError = "Configura CoinMarketCap";
    return false;
  }

  drawLoading("MERCADO", "Actualizando CoinMarketCap...", C_GOLD);

  String fiat = cfg.fiat;
  fiat.toUpperCase();

  String url =
    "https://pro-api.coinmarketcap.com/v3/cryptocurrency/quotes/latest?id=" +
    String(cfg.cmcId) + "&convert=" + fiat;

  String body;
  int code = 0;

  if (!performGet(url, "", "X-CMC_PRO_API_KEY", cfg.cmcApiKey, body, code)) {
    cryptoLastError = "Error de red CMC";
    return false;
  }

  if (code != 200) {
    cryptoLastError = "CMC HTTP " + String(code);
    return false;
  }

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, body);
  if (err) {
    cryptoLastError = "JSON CMC invalido";
    return false;
  }

  bool found = false;
  double price = 0.0;
  double change = 0.0;
  bool changeFound = false;

  JsonVariant data = doc["data"];

  // v3 normally returns an array; keep object fallback for compatibility.
  if (data.is<JsonArray>()) {
    JsonArray arr = data.as<JsonArray>();
    if (!arr.isNull() && arr.size() > 0) {
      JsonObject item = arr[0].as<JsonObject>();
      JsonVariant quote = item["quote"];

      if (quote.is<JsonArray>()) {
        for (JsonObject q : quote.as<JsonArray>()) {
          String sym = String((const char *)(q["symbol"] | ""));
          if (sym == fiat || quote.as<JsonArray>().size() == 1) {
            if (!q["price"].isNull()) {
              price = q["price"].as<double>();
              found = true;
            }
            if (!q["percent_change_24h"].isNull()) {
              change = q["percent_change_24h"].as<double>();
              changeFound = true;
            }
            break;
          }
        }
      } else if (quote.is<JsonObject>()) {
        JsonObject q = quote[fiat].as<JsonObject>();
        if (!q.isNull() && !q["price"].isNull()) {
          price = q["price"].as<double>();
          found = true;
          if (!q["percent_change_24h"].isNull()) {
            change = q["percent_change_24h"].as<double>();
            changeFound = true;
          }
        }
      }
    }
  } else if (data.is<JsonObject>()) {
    String id = String(cfg.cmcId);
    JsonObject item = data[id].as<JsonObject>();
    if (!item.isNull()) {
      JsonObject q = item["quote"][fiat].as<JsonObject>();
      if (!q.isNull() && !q["price"].isNull()) {
        price = q["price"].as<double>();
        found = true;
        if (!q["percent_change_24h"].isNull()) {
          change = q["percent_change_24h"].as<double>();
          changeFound = true;
        }
      }
    }
  }

  if (!found) {
    cryptoLastError = "Precio no encontrado";
    return false;
  }

  cryptoPrice = price;
  cryptoKnown = true;
  cryptoChange24h = change;
  cryptoChangeKnown = changeFound;
  cryptoLastError = "";
  return true;
}

// -----------------------------------------------------------------------------
// Home Assistant — direct ESP32 -> HA REST API
// -----------------------------------------------------------------------------
bool haReady() {
  return WiFi.status() == WL_CONNECTED &&
         !cfg.haBaseUrl.isEmpty() &&
         !cfg.haToken.isEmpty();
}

bool haTestConnection(String &message) {
  if (WiFi.status() != WL_CONNECTED) {
    message = "ESP sin Wi-Fi";
    return false;
  }

  if (cfg.haBaseUrl.isEmpty()) {
    message = "Falta URL de Home Assistant";
    return false;
  }

  if (cfg.haToken.isEmpty()) {
    message = "Falta token de Home Assistant";
    return false;
  }

  String body;
  int code = 0;
  String url = normalizeBaseUrl(cfg.haBaseUrl) + "/api/";

  if (!performGet(url, cfg.haToken, "", "", body, code)) {
    message = "No se pudo conectar desde el ESP";
    return false;
  }

  if (code == 401) {
    message = "Token rechazado (401)";
    return false;
  }

  if (code != 200) {
    message = "Home Assistant HTTP " + String(code);
    return false;
  }

  JsonDocument doc;
  if (deserializeJson(doc, body)) {
    message = "Respuesta HA no valida";
    return false;
  }

  message = String((const char *)(doc["message"] | "Home Assistant conectado"));
  return true;
}

bool haGetEntity(const String &entityId, JsonDocument &doc) {
  haLastError = "";

  if (!haReady()) {
    haLastError = "HA sin configurar";
    return false;
  }

  if (entityId.isEmpty()) {
    haLastError = "Entidad vacia";
    return false;
  }

  String body;
  int code = 0;
  String url = normalizeBaseUrl(cfg.haBaseUrl) + "/api/states/" + entityId;

  if (!performGet(url, cfg.haToken, "", "", body, code)) {
    haLastError = "Error de red HA";
    return false;
  }

  if (code == 401) {
    haLastError = "Token HA invalido";
    return false;
  }

  if (code == 404) {
    haLastError = "Entidad no existe";
    return false;
  }

  if (code != 200) {
    haLastError = "HA HTTP " + String(code);
    return false;
  }

  DeserializationError err = deserializeJson(doc, body);
  if (err) {
    haLastError = "JSON HA invalido";
    return false;
  }

  return true;
}

bool haCallService(
  const String &domain,
  const String &service,
  JsonDocument &payload
) {
  haLastError = "";

  if (!haReady()) {
    haLastError = "HA sin configurar";
    return false;
  }

  String body;
  serializeJson(payload, body);

  String response;
  int code = 0;
  String url =
    normalizeBaseUrl(cfg.haBaseUrl) +
    "/api/services/" + domain + "/" + service;

  if (!performPost(url, cfg.haToken, body, response, code)) {
    haLastError = "Error de red HA";
    return false;
  }

  if (code == 401) {
    haLastError = "Token HA invalido";
    return false;
  }

  if (code != 200 && code != 201) {
    haLastError = "HA HTTP " + String(code);
    return false;
  }

  return true;
}

bool refreshCurrentLight() {
  lightKnown = false;

  if (cfg.lightCount == 0 || lightIndex >= cfg.lightCount) {
    haLastError = "Sin luces configuradas";
    return false;
  }

  JsonDocument doc;
  if (!haGetEntity(cfg.lightIds[lightIndex], doc)) return false;

  String state = String((const char *)(doc["state"] | "unknown"));

  if (state == "on") {
    lightOn = true;
    lightKnown = true;
    return true;
  }

  if (state == "off") {
    lightOn = false;
    lightKnown = true;
    return true;
  }

  haLastError = "Estado: " + state;
  return false;
}

bool toggleCurrentLight() {
  if (cfg.lightCount == 0 || lightIndex >= cfg.lightCount) return false;

  if (!lightKnown && !refreshCurrentLight()) return false;

  bool newState = !lightOn;

  JsonDocument payload;
  payload["entity_id"] = cfg.lightIds[lightIndex];

  String domain = entityDomain(cfg.lightIds[lightIndex]);
  if (domain.isEmpty()) {
    haLastError = "Entidad invalida";
    return false;
  }

  if (!haCallService(domain, newState ? "turn_on" : "turn_off", payload)) {
    return false;
  }

  // Verify the actual state instead of blindly trusting the command.
  delay(180);

  if (refreshCurrentLight()) {
    return lightOn == newState;
  }

  // Service succeeded but state verification could not be fetched.
  lightOn = newState;
  lightKnown = true;
  return true;
}

bool refreshClimate() {
  climateKnown = false;

  if (cfg.climateId.isEmpty()) {
    haLastError = "Sin termostato";
    return false;
  }

  JsonDocument doc;
  if (!haGetEntity(cfg.climateId, doc)) return false;

  JsonObject attrs = doc["attributes"].as<JsonObject>();
  if (attrs.isNull() || attrs["temperature"].isNull()) {
    haLastError = "HA no expone temperatura objetivo";
    return false;
  }

  climateTarget = attrs["temperature"].as<float>();

  if (!attrs["min_temp"].isNull()) climateMin = attrs["min_temp"].as<float>();
  if (!attrs["max_temp"].isNull()) climateMax = attrs["max_temp"].as<float>();

  climateKnown = true;
  return true;
}

bool setClimateTarget(float target) {
  if (cfg.climateId.isEmpty()) return false;

  if (!climateKnown && !refreshClimate()) return false;

  target = constrain(target, climateMin, climateMax);
  target = roundf(target * 2.0f) / 2.0f;

  JsonDocument payload;
  payload["entity_id"] = cfg.climateId;
  payload["temperature"] = target;

  String domain = entityDomain(cfg.climateId);
  if (domain.isEmpty()) {
    haLastError = "Entidad invalida";
    return false;
  }

  if (!haCallService(domain, "set_temperature", payload)) return false;

  delay(180);

  // Prefer the state HA actually reports.
  if (refreshClimate()) {
    return fabsf(climateTarget - target) < 0.26f;
  }

  climateTarget = target;
  climateKnown = true;
  return true;
}

// -----------------------------------------------------------------------------
// Notifications
// -----------------------------------------------------------------------------
int findNotificationByKey(const String &key) {
  for (uint8_t i = 0; i < notificationCount; i++) {
    if (notifications[i].key == key) return i;
  }
  return -1;
}

void removeNotificationAt(uint8_t index) {
  if (index >= notificationCount) return;

  for (uint8_t i = index; i + 1 < notificationCount; i++) {
    notifications[i] = notifications[i + 1];
  }

  notificationCount--;

  if (notificationCount == 0) {
    notificationIndex = 0;
  } else if (notificationIndex >= notificationCount) {
    notificationIndex = notificationCount - 1;
  }
}

void addOrUpdateNotification(
  const String &key,
  const String &app,
  const String &title,
  const String &text,
  const String &timeText
) {
  if (key.isEmpty()) return;

  int existing = findNotificationByKey(key);

  NotificationItem item;
  item.key = key;
  item.app = app;
  item.title = title;
  item.text = text;
  item.timeText = timeText;

  if (existing >= 0) {
    notifications[existing] = item;
    return;
  }

  // Newest first.
  if (notificationCount < MAX_NOTIFICATIONS) notificationCount++;

  for (int i = notificationCount - 1; i > 0; i--) {
    notifications[i] = notifications[i - 1];
  }

  notifications[0] = item;
  notificationIndex = 0;
}

void dismissCurrentNotification() {
  if (notificationCount == 0 || notificationIndex >= notificationCount) return;

  String key = notifications[notificationIndex].key;

  JsonDocument doc;
  doc["type"] = "notification_dismiss";
  doc["key"] = key;
  bleSendJson(doc);

  removeNotificationAt(notificationIndex);
  showToast("Notificacion eliminada", 1200);
}

// -----------------------------------------------------------------------------
// AI
// -----------------------------------------------------------------------------
void buildAiWrappedLines() {
  aiLineCount = 0;

  String source = asciiSafe(aiError.isEmpty() ? aiResponse : aiError);
  source.replace("\r", "");

  const uint8_t width = 47;
  String line;
  String word;

  auto pushLine = [&](const String &s) {
    if (aiLineCount < AI_MAX_LINES) aiLines[aiLineCount++] = s;
  };

  for (size_t i = 0; i <= source.length(); i++) {
    char c = (i < source.length()) ? source[i] : ' ';

    if (c == '\n') {
      if (!word.isEmpty()) {
        if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
          pushLine(line);
          line = word;
        } else {
          if (!line.isEmpty()) line += ' ';
          line += word;
        }
        word = "";
      }
      pushLine(line);
      line = "";
      continue;
    }

    if (c == ' ' || i == source.length()) {
      if (!word.isEmpty()) {
        if (word.length() > width) {
          if (!line.isEmpty()) {
            pushLine(line);
            line = "";
          }
          while (word.length() > width) {
            pushLine(word.substring(0, width));
            word.remove(0, width);
          }
        }

        if (!word.isEmpty()) {
          if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
            pushLine(line);
            line = word;
          } else {
            if (!line.isEmpty()) line += ' ';
            line += word;
          }
        }
      }
      word = "";
    } else {
      word += c;
    }
  }

  if (!line.isEmpty()) pushLine(line);
  if (aiLineCount == 0) pushLine("");
}

void startAiSession() {
  if (!mobileConnected()) {
    showToast("Movil BLE desconectado", 1800);
    return;
  }

  aiSessionId = chipHex() + "-" + String(millis());
  aiQuery = "";
  aiUpper = false;
  keyboardIndex = 0;
  aiResponse = "";
  aiError = "";
  aiPage = 0;

  JsonDocument doc;
  doc["type"] = "ai_session_start";
  doc["session_id"] = aiSessionId;
  bleSendJson(doc);

  screen = SCREEN_AI_KEYBOARD;
}

void endAiSession() {
  if (!aiSessionId.isEmpty()) {
    JsonDocument doc;
    doc["type"] = "ai_session_end";
    doc["session_id"] = aiSessionId;
    bleSendJson(doc);
  }

  aiSessionId = "";
  aiQuery = "";
  aiResponse = "";
  aiError = "";
  aiLineCount = 0;
}

void sendAiRequest() {
  if (aiQuery.isEmpty()) {
    showToast("Escribe una pregunta");
    return;
  }

  if (!mobileConnected()) {
    showToast("Movil BLE desconectado", 1800);
    return;
  }

  aiRequestId++;
  if (aiRequestId == 0) aiRequestId = 1;

  JsonDocument doc;
  doc["type"] = "ai_request";
  doc["session_id"] = aiSessionId;
  doc["request_id"] = aiRequestId;
  doc["prompt"] = aiQuery;
  bleSendJson(doc);

  aiResponse = "";
  aiError = "";
  screen = SCREEN_AI_WAIT;
}

char keyboardLetter(uint8_t idx) {
  char c = 'a' + idx;
  if (aiUpper) c = toupper(c);
  return c;
}

String keyboardLabel(uint8_t idx) {
  if (idx < 26) return String(keyboardLetter(idx));

  switch (idx) {
    case 26: return "^"; // shift
    case 27: return "_"; // space
    case 28: return "<"; // delete
    case 29: return ".";
    case 30: return ",";
    case 31: return "?";
    case 32: return "!";
    case 33: return ">"; // enter/send
    default: return "";
  }
}

void aiKeyboardSelect() {
  if (keyboardIndex < 26) {
    if (aiQuery.length() < MAX_AI_QUERY) aiQuery += keyboardLetter(keyboardIndex);
    return;
  }

  switch (keyboardIndex) {
    case 26:
      aiUpper = !aiUpper;
      break;

    case 27:
      if (aiQuery.length() < MAX_AI_QUERY) aiQuery += ' ';
      break;

    case 28:
      if (!aiQuery.isEmpty()) aiQuery.remove(aiQuery.length() - 1);
      break;

    case 29:
      if (aiQuery.length() < MAX_AI_QUERY) aiQuery += '.';
      break;

    case 30:
      if (aiQuery.length() < MAX_AI_QUERY) aiQuery += ',';
      break;

    case 31:
      if (aiQuery.length() < MAX_AI_QUERY) aiQuery += '?';
      break;

    case 32:
      if (aiQuery.length() < MAX_AI_QUERY) aiQuery += '!';
      break;

    case 33:
      sendAiRequest();
      break;
  }
}

// -----------------------------------------------------------------------------
// Local notes
// -----------------------------------------------------------------------------
void pushNoteLine(const String &line) {
  if (noteLineCount < NOTE_MAX_LINES) noteLines[noteLineCount++] = line;
}

void buildNoteWrappedLines() {
  noteLineCount = 0;
  if (noteIndex >= noteCount) {
    pushNoteLine("");
    return;
  }

  String source = asciiSafe(notes[noteIndex].content);
  source.replace("\r", "");
  if (source.isEmpty()) {
    pushNoteLine("(Sin contenido)");
    return;
  }

  const uint8_t width = 47;
  String line;
  String word;

  for (size_t i = 0; i <= source.length(); i++) {
    char c = (i < source.length()) ? source[i] : ' ';

    if (c == '\n') {
      if (!word.isEmpty()) {
        if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
          pushNoteLine(line);
          line = word;
        } else {
          if (!line.isEmpty()) line += ' ';
          line += word;
        }
        word = "";
      }
      pushNoteLine(line);
      line = "";
      continue;
    }

    if (c == ' ' || i == source.length()) {
      if (!word.isEmpty()) {
        if (word.length() > width) {
          if (!line.isEmpty()) {
            pushNoteLine(line);
            line = "";
          }
          while (word.length() > width && noteLineCount < NOTE_MAX_LINES) {
            pushNoteLine(word.substring(0, width));
            word.remove(0, width);
          }
        }

        if (!word.isEmpty()) {
          if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
            pushNoteLine(line);
            line = word;
          } else {
            if (!line.isEmpty()) line += ' ';
            line += word;
          }
        }
      }
      word = "";
    } else {
      word += c;
    }
  }

  if (!line.isEmpty()) pushNoteLine(line);
  if (noteLineCount == 0) pushNoteLine("");
}

char noteKeyboardLetter(uint8_t idx) {
  char c = 'a' + idx;
  return noteUpper ? toupper(c) : c;
}

String noteKeyboardLabel(uint8_t idx) {
  if (idx < 26) return String(noteKeyboardLetter(idx));
  switch (idx) {
    case 26: return "^";
    case 27: return "_";
    case 28: return "<";
    case 29: return ".";
    case 30: return ",";
    case 31: return "?";
    case 32: return "!";
    case 33: return "NL";
    default: return "";
  }
}

void beginNewNote() {
  if (noteCount >= MAX_NOTES) {
    showToast("Limite de 8 notas", 1800);
    return;
  }

  noteEditingIndex = noteCount;
  noteEditingNew = true;
  noteEditingTitle = true;
  noteUpper = false;
  noteKeyboardIndex = 0;
  noteDraftTitle = "";
  noteDraftContent = "";
  screen = SCREEN_NOTE_EDITOR;
}

void beginEditNote() {
  if (noteIndex >= noteCount) return;
  noteEditingIndex = noteIndex;
  noteEditingNew = false;
  noteEditingTitle = true;
  noteUpper = false;
  noteKeyboardIndex = 0;
  noteDraftTitle = notes[noteIndex].title;
  noteDraftContent = notes[noteIndex].content;
  screen = SCREEN_NOTE_EDITOR;
}

void openCurrentNote() {
  if (noteIndex >= noteCount) return;
  notePage = 0;
  buildNoteWrappedLines();
  screen = SCREEN_NOTE_VIEW;
}

void saveEditedNote() {
  if (noteEditingIndex >= MAX_NOTES) return;

  noteDraftTitle.trim();
  if (noteDraftTitle.isEmpty()) noteDraftTitle = "Sin titulo";
  if (noteDraftTitle.length() > MAX_NOTE_TITLE) {
    noteDraftTitle = noteDraftTitle.substring(0, MAX_NOTE_TITLE);
  }
  if (noteDraftContent.length() > MAX_NOTE_CONTENT) {
    noteDraftContent = noteDraftContent.substring(0, MAX_NOTE_CONTENT);
  }

  notes[noteEditingIndex].title = noteDraftTitle;
  notes[noteEditingIndex].content = noteDraftContent;
  if (noteEditingNew && noteEditingIndex == noteCount) noteCount++;
  saveNotes();

  noteIndex = noteEditingIndex;
  noteEditingNew = false;
  notePage = 0;
  buildNoteWrappedLines();
  screen = SCREEN_NOTE_VIEW;
  showToast("Nota guardada", 1200);
}

void toggleNoteEditorField() {
  noteEditingTitle = !noteEditingTitle;
  showToast(noteEditingTitle ? "Editando titulo" : "Editando contenido", 900);
}

void noteKeyboardSelect() {
  String *target = noteEditingTitle ? &noteDraftTitle : &noteDraftContent;
  size_t limit = noteEditingTitle ? MAX_NOTE_TITLE : MAX_NOTE_CONTENT;

  if (noteKeyboardIndex < 26) {
    if (target->length() < limit) *target += noteKeyboardLetter(noteKeyboardIndex);
    return;
  }

  switch (noteKeyboardIndex) {
    case 26:
      noteUpper = !noteUpper;
      break;
    case 27:
      if (target->length() < limit) *target += ' ';
      break;
    case 28:
      if (!target->isEmpty()) target->remove(target->length() - 1);
      break;
    case 29:
      if (target->length() < limit) *target += '.';
      break;
    case 30:
      if (target->length() < limit) *target += ',';
      break;
    case 31:
      if (target->length() < limit) *target += '?';
      break;
    case 32:
      if (target->length() < limit) *target += '!';
      break;
    case 33:
      if (!noteEditingTitle && target->length() < limit) {
        *target += '\n';
      } else if (noteEditingTitle) {
        showToast("NL solo en contenido", 1000);
      }
      break;
  }
}

// -----------------------------------------------------------------------------
// BLE JSON message handler
// -----------------------------------------------------------------------------
void applyConfigFromJson(JsonVariantConst root) {
  if (root["device_name"].is<const char *>()) {
    cfg.deviceName = String(root["device_name"].as<const char *>());
  }

  if (root["ha_base_url"].is<const char *>()) {
    cfg.haBaseUrl = normalizeBaseUrl(
      String(root["ha_base_url"].as<const char *>())
    );
  }

  // Sensitive fields are only changed if present.
  if (root["ha_token"].is<const char *>()) {
    cfg.haToken = String(root["ha_token"].as<const char *>());
  }

  if (root["cmc_api_key"].is<const char *>()) {
    cfg.cmcApiKey = String(root["cmc_api_key"].as<const char *>());
  }

  if (!root["cmc_id"].isNull()) {
    cfg.cmcId = root["cmc_id"].as<uint32_t>();
  }

  if (root["cmc_symbol"].is<const char *>()) {
    cfg.cmcSymbol = String(root["cmc_symbol"].as<const char *>());
  }

  if (root["fiat"].is<const char *>()) {
    cfg.fiat = String(root["fiat"].as<const char *>());
    cfg.fiat.toUpperCase();
  }

  if (root["timezone"].is<const char *>()) {
    cfg.timezone = String(root["timezone"].as<const char *>());
  }

  if (!root["brightness"].isNull()) {
    int b = root["brightness"].as<int>();
    cfg.brightness = constrain(b, 0, 127);
    if (!displaySleeping) setBacklight(cfg.brightness);
  }

  if (root["climate_id"].is<const char *>()) {
    String id = String(root["climate_id"].as<const char *>());
    cfg.climateId = id.isEmpty() || validEntityId(id) ? id : "";
  }

  if (root["climate_name"].is<const char *>()) {
    cfg.climateName = String(root["climate_name"].as<const char *>());
  }

  if (root["lights"].is<JsonArrayConst>()) {
    cfg.lightCount = 0;

    for (JsonObjectConst o : root["lights"].as<JsonArrayConst>()) {
      if (cfg.lightCount >= MAX_LIGHTS) break;

      String id = String((const char *)(o["id"] | ""));
      if (!validEntityId(id)) continue;

      cfg.lightIds[cfg.lightCount] = id;
      cfg.lightNames[cfg.lightCount] =
        String((const char *)(o["name"] | "Luz"));
      cfg.lightCount++;
    }
  }

  saveConfig();
  startTimeSync();
}

void handleBleJson(const String &line) {
  if (line.isEmpty()) return;

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, line);

  if (err) {
    JsonDocument out;
    out["type"] = "error";
    out["code"] = "invalid_json";
    bleSendJson(out);
    return;
  }

  lastMobileSeenMs = millis();

  String type = String((const char *)(doc["type"] | ""));

  if (type == "hello") {
    JsonDocument out;
    out["type"] = "hello_ack";
    out["protocol"] = PROTOCOL_VERSION;
    out["chip_id"] = chipHex();
    out["device_name"] = cfg.deviceName;
    out["board"] = "Waveshare ESP32-C6-LCD-1.47";
    out["wifi_connected"] = WiFi.status() == WL_CONNECTED;
    out["ble_authenticated"] = bleAuthenticated;
    out["ble_tx_subscribed"] = bleTxSubscribed;
    out["manual_entity_roles"] = true;
    bleSendJson(out);
    sendStatus();
    return;
  }

  if (type == "heartbeat") {
    JsonDocument out;
    out["type"] = "heartbeat_ack";
    out["uptime_ms"] = millis();
    out["wifi_connected"] = WiFi.status() == WL_CONNECTED;
    bleSendJson(out);
    return;
  }

  if (type == "time_sync") {
    uint64_t epoch = doc["epoch"] | 0ULL;
    if (epoch > 1600000000ULL && time(nullptr) < 1600000000) {
      struct timeval tv;
      tv.tv_sec = (time_t)epoch;
      tv.tv_usec = 0;
      settimeofday(&tv, nullptr);
      setenv("TZ", cfg.timezone.c_str(), 1);
      tzset();
    }
    return;
  }

  if (type == "wifi_config") {
    String ssid = String((const char *)(doc["ssid"] | ""));
    String password = String((const char *)(doc["password"] | ""));

    JsonDocument out;
    out["type"] = "wifi_result";

    if (ssid.isEmpty() || ssid.length() > 32 || password.length() > 64) {
      out["ok"] = false;
      out["error"] = "invalid_credentials";
      bleSendJson(out);
      return;
    }

    cfg.ssid = ssid;
    cfg.wifiPassword = password;
    saveConfig();

    WiFi.disconnect(true, false);
    delay(80);
    connectWiFiNonBlocking();

    uint32_t started = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - started < 12000) {
      delay(100);
    }

    if (WiFi.status() == WL_CONNECTED) {
      startTimeSync();
      out["ok"] = true;
      out["ip"] = WiFi.localIP().toString();
      out["rssi"] = WiFi.RSSI();
      showToast("Wi-Fi configurado", 1500);
    } else {
      out["ok"] = false;
      out["error"] = "wifi_connection_failed";
      showToast("No conecta al Wi-Fi", 1800);
    }

    bleSendJson(out);
    return;
  }

  if (type == "config_get") {
    sendConfigState();
    return;
  }

  if (type == "config_set") {
    applyConfigFromJson(doc.as<JsonVariantConst>());

    JsonDocument out;
    out["type"] = "config_saved";
    out["ok"] = true;
    bleSendJson(out);
    return;
  }

  if (type == "ha_test") {
    String message;
    bool ok = haTestConnection(message);

    JsonDocument out;
    out["type"] = "ha_test_result";
    out["ok"] = ok;
    out["message"] = message;
    bleSendJson(out);

    showToast(ok ? "Home Assistant OK" : message, 1800);
    return;
  }

  if (type == "ha_entity_test") {
    String entity = String((const char *)(doc["entity_id"] | ""));
    JsonDocument entityDoc;
    bool ok = haGetEntity(entity, entityDoc);

    JsonDocument out;
    out["type"] = "ha_entity_test_result";
    out["entity_id"] = entity;
    out["ok"] = ok;
    out["state"] = ok ? String((const char *)(entityDoc["state"] | "")) : "";
    out["error"] = ok ? "" : haLastError;
    bleSendJson(out);
    return;
  }

  if (type == "notification_clear") {
    notificationCount = 0;
    notificationIndex = 0;
    return;
  }

  if (type == "notification_add") {
    addOrUpdateNotification(
      String((const char *)(doc["key"] | "")),
      String((const char *)(doc["app"] | "")),
      String((const char *)(doc["title"] | "")),
      String((const char *)(doc["text"] | "")),
      String((const char *)(doc["time"] | ""))
    );
    return;
  }

  if (type == "notification_remove") {
    String key = String((const char *)(doc["key"] | ""));
    int idx = findNotificationByKey(key);
    if (idx >= 0) removeNotificationAt((uint8_t)idx);
    return;
  }

  if (type == "ai_response") {
    String session = String((const char *)(doc["session_id"] | ""));
    uint32_t requestId = doc["request_id"] | 0;

    if (session != aiSessionId || requestId != aiRequestId) return;

    aiResponse = String((const char *)(doc["text"] | ""));
    aiError = String((const char *)(doc["error"] | ""));

    if (aiResponse.length() > MAX_AI_RESPONSE) {
      aiResponse = aiResponse.substring(0, MAX_AI_RESPONSE);
    }

    buildAiWrappedLines();
    aiPage = 0;
    screen = SCREEN_AI_RESPONSE;
    return;
  }

  if (type == "status_request") {
    sendStatus();
    return;
  }

  if (type == "factory_reset") {
    String confirm = String((const char *)(doc["confirm"] | ""));
    if (confirm == "ERASE") {
      JsonDocument out;
      out["type"] = "factory_reset_ack";
      out["ok"] = true;
      bleSendJson(out);
      delay(200);
      factoryResetConfig();
    }
    return;
  }

  JsonDocument out;
  out["type"] = "error";
  out["code"] = "unknown_message_type";
  out["received_type"] = type;
  bleSendJson(out);
}

void serviceBleIncoming() {
  if (!pendingBleLineReady) return;

  String line = pendingBleLine;
  pendingBleLine = "";
  pendingBleLineReady = false;

  handleBleJson(line);
  uiDirty = true;

  // If callback already accumulated another complete line, expose it next loop.
  int newline = bleRxStream.indexOf('\n');
  if (!pendingBleLineReady && newline >= 0) {
    pendingBleLine = bleRxStream.substring(0, newline);
    bleRxStream.remove(0, newline + 1);
    pendingBleLineReady = true;
  }
}

// -----------------------------------------------------------------------------
// UI drawing
// -----------------------------------------------------------------------------
void drawSetupScreen() {
  gfx->fillScreen(C_BG);

  gfx->fillRoundRect(10, 10, 132, 152, 12, C_SURFACE);
  gfx->fillRoundRect(22, 22, 5, 44, 3, C_MINT);
  gfx->setTextSize(2);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(37, 23);
  gfx->print("SMART");
  gfx->setCursor(37, 45);
  gfx->print("PANEL");

  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(22, 82);
  gfx->print("ESP32-C6 + BLE");
  gfx->setCursor(22, 99);
  gfx->print("1.47 TFT 320x172");
  gfx->setCursor(22, 124);
  gfx->print(bleConnected ? "Movil BLE conectado" : "Esperando movil BLE");
  drawStatusDot(25, 147, bleConnected, C_BLUE);

  gfx->fillRoundRect(153, 10, 157, 152, 12, C_SURFACE2);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(169, 25);
  gfx->print("EMPAREJAMIENTO");

  gfx->setTextColor(C_BLUE);
  gfx->setCursor(169, 47);
  gfx->print("SmartPanel-C6-");
  gfx->print(shortChip());

  char pinBuf[7];
  snprintf(pinBuf, sizeof(pinBuf), "%06lu", (unsigned long)blePasskey);
  gfx->setTextSize(3);
  gfx->setTextColor(C_MINT);
  gfx->setCursor(177, 72);
  gfx->print(pinBuf);

  gfx->setTextSize(1);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(169, 115);
  gfx->print("Introduce este PIN");
  gfx->setCursor(169, 129);
  gfx->print("en Android y envia");
  gfx->setCursor(169, 143);
  gfx->print("despues tu Wi-Fi.");
}

void drawHome() {
  gfx->fillScreen(C_BG);

  time_t now = time(nullptr);
  struct tm localTm;
  bool timeOk = now > 1600000000 && localtime_r(&now, &localTm);

  char timeBuf[8] = "--:--";
  char dateBuf[24] = "SIN HORA";

  if (timeOk) {
    snprintf(timeBuf, sizeof(timeBuf), "%02d:%02d", localTm.tm_hour, localTm.tm_min);
    static const char *DAYS[] = {"DOM", "LUN", "MAR", "MIE", "JUE", "VIE", "SAB"};
    static const char *MONTHS[] = {"ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC"};
    snprintf(dateBuf, sizeof(dateBuf), "%s %02d %s", DAYS[localTm.tm_wday], localTm.tm_mday, MONTHS[localTm.tm_mon]);
  }

  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(12, 9);
  gfx->print(dateBuf);
  gfx->setCursor(230, 9);
  gfx->print("W");
  drawStatusDot(246, 12, WiFi.status() == WL_CONNECTED, C_MINT);
  gfx->setCursor(260, 9);
  gfx->print("B");
  drawStatusDot(276, 12, mobileConnected(), C_BLUE);

  drawCard(10, 28, 132, 103, C_SURFACE);
  gfx->setTextSize(4);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(17, 43);
  gfx->print(timeBuf);

  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(20, 92);
  gfx->print("NOTIFICACIONES");
  gfx->setTextSize(3);
  gfx->setTextColor(notificationCount ? C_BLUE : C_TEXT);
  gfx->setCursor(20, 107);
  gfx->print(notificationCount);

  drawCard(150, 28, 160, 103, C_SURFACE2);
  gfx->setTextSize(1);
  gfx->setTextColor(C_GOLD);
  gfx->setCursor(163, 42);
  gfx->print("MERCADO  ");
  gfx->print(ellipsize(cfg.cmcSymbol.isEmpty() ? "CRYPTO" : cfg.cmcSymbol, 8));

  if (cryptoKnown) {
    String price = formatPrice(cryptoPrice) + " " + cfg.fiat;
    gfx->setTextSize(3);
    gfx->setTextColor(C_TEXT);
    gfx->setCursor(163, 66);
    gfx->setTextSize(2);
    gfx->print(ellipsize(price, 11));

    if (cryptoChangeKnown) {
      gfx->setTextSize(1);
      gfx->setTextColor(cryptoChange24h >= 0 ? C_GREEN : C_RED);
      gfx->setCursor(163, 101);
      if (cryptoChange24h >= 0) gfx->print("+");
      gfx->print(String(cryptoChange24h, 2));
      gfx->print("% 24h");
    }
  } else {
    gfx->setTextSize(2);
    gfx->setTextColor(C_MUTED);
    gfx->setCursor(163, 66);
    gfx->print("SIN PRECIO");
    gfx->setTextSize(1);
    gfx->setCursor(163, 99);
    gfx->print("IZQ+OK actualiza");
  }

  drawToastOverlay();
}

void drawMenuIcon(uint8_t idx, int16_t cx, int16_t cy, uint16_t color) {
  switch (idx) {
    case 0:
      gfx->drawCircle(cx, cy - 2, 10, color);
      gfx->fillRoundRect(cx - 12, cy + 5, 24, 4, 2, color);
      gfx->fillCircle(cx, cy + 12, 2, color);
      break;
    case 1:
      gfx->drawCircle(cx, cy - 4, 11, color);
      gfx->drawFastVLine(cx - 4, cy + 6, 8, color);
      gfx->drawFastVLine(cx + 4, cy + 6, 8, color);
      gfx->drawFastHLine(cx - 4, cy + 14, 9, color);
      break;
    case 2:
      gfx->drawRoundRect(cx - 4, cy - 14, 8, 22, 4, color);
      gfx->fillCircle(cx, cy + 10, 7, color);
      gfx->drawFastVLine(cx, cy - 8, 18, color);
      break;
    case 3:
      gfx->drawLine(cx, cy - 14, cx, cy + 14, color);
      gfx->drawLine(cx - 14, cy, cx + 14, cy, color);
      gfx->drawLine(cx - 9, cy - 9, cx + 9, cy + 9, color);
      gfx->drawLine(cx + 9, cy - 9, cx - 9, cy + 9, color);
      gfx->fillCircle(cx, cy, 4, color);
      break;
    case 4:
      gfx->fillCircle(cx - 3, cy, 9, C_BIRD);
      gfx->fillCircle(cx + 6, cy - 3, 5, C_BIRD);
      gfx->fillCircle(cx + 8, cy - 4, 2, C_WHITE);
      gfx->fillTriangle(cx + 10, cy, cx + 18, cy + 2, cx + 10, cy + 4, C_BIRD_OR);
      break;
    case 5:
      gfx->fillRoundRect(cx - 13, cy - 15, 26, 30, 4, C_SURFACE2);
      gfx->drawRoundRect(cx - 13, cy - 15, 26, 30, 4, color);
      gfx->drawFastHLine(cx - 7, cy - 7, 15, color);
      gfx->drawFastHLine(cx - 7, cy, 15, color);
      gfx->drawFastHLine(cx - 7, cy + 7, 11, color);
      break;
  }
}

void drawMenu() {
  static const char *NAMES[MENU_COUNT] = {"Notificaciones", "Luces", "Termostato", "IA", "Flappy Bird", "Notas"};
  static const uint16_t COLORS[MENU_COUNT] = {C_BLUE, C_GOLD, C_RED, C_PURPLE, C_GREEN, C_MINT};

  gfx->fillScreen(C_BG);
  drawHeader("PANEL", COLORS[menuIndex]);

  uint8_t prev = (menuIndex + MENU_COUNT - 1) % MENU_COUNT;
  uint8_t next = (menuIndex + 1) % MENU_COUNT;

  drawCard(10, 38, 67, 94, C_SURFACE);
  drawMenuIcon(prev, 43, 76, COLORS[prev]);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(17, 111);
  gfx->print(ellipsize(NAMES[prev], 9));

  drawCard(86, 33, 148, 105, C_SURFACE2);
  gfx->drawRoundRect(86, 33, 148, 105, 10, COLORS[menuIndex]);
  drawMenuIcon(menuIndex, 160, 73, COLORS[menuIndex]);
  String activeName = NAMES[menuIndex];
  uint8_t activeSize = activeName.length() <= 10 ? 2 : 1;
  gfx->setTextSize(activeSize);
  gfx->setTextColor(C_TEXT);
  int16_t activeX = 160 - ((int16_t)activeName.length() * 6 * activeSize) / 2;
  gfx->setCursor(activeX, activeSize == 2 ? 109 : 114);
  gfx->print(activeName);

  drawCard(243, 38, 67, 94, C_SURFACE);
  drawMenuIcon(next, 276, 76, COLORS[next]);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(250, 111);
  gfx->print(ellipsize(NAMES[next], 9));

  drawFooterHint("<", "OK", ">");
  drawToastOverlay();
}

void drawNotifications() {
  gfx->fillScreen(C_BG);
  drawHeader("AVISOS", C_BLUE);

  if (notificationCount == 0) {
    drawCard(10, 38, 300, 96, C_SURFACE);
    centerText("Sin notificaciones", 64, 2, C_MUTED);
    centerText("Todo al dia", 96, 2, C_MINT);
    drawFooterHint("", "", "");
    return;
  }

  NotificationItem &n = notifications[notificationIndex];
  drawCard(10, 35, 300, 106, C_SURFACE);

  gfx->setTextSize(1);
  gfx->setTextColor(C_BLUE);
  gfx->setCursor(22, 47);
  gfx->print(ellipsize(n.app, 18));
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(266, 47);
  gfx->print(ellipsize(n.timeText, 6));

  gfx->setTextSize(2);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(22, 65);
  gfx->print(ellipsize(n.title, 22));

  gfx->drawFastHLine(22, 87, 276, C_SURFACE2);
  String txt = asciiSafe(n.text);
  gfx->setTextSize(1);
  gfx->setTextColor(C_TEXT);
  for (uint8_t line = 0; line < 4; line++) {
    size_t st = line * 45;
    if (st >= txt.length()) break;
    size_t en = st + 45;
    if (en > txt.length()) en = txt.length();
    gfx->setCursor(22, 96 + line * 11);
    gfx->print(txt.substring(st, en));
  }

  gfx->setTextColor(C_MUTED);
  gfx->setCursor(250, 129);
  gfx->print(String(notificationIndex + 1) + "/" + String(notificationCount));

  drawFooterHint("< anterior", "OK leer/eliminar", "siguiente >");
  drawToastOverlay();
}

void drawNotesList() {
  gfx->fillScreen(C_BG);
  drawHeader("NOTAS", C_MINT);

  if (noteCount == 0) {
    drawCard(10, 38, 300, 96, C_SURFACE);
    centerText("Sin notas", 62, 2, C_MUTED);
    centerText("CENTRO+DER para crear", 96, 1, C_MINT);
    drawFooterHint("", "", "");
    drawToastOverlay();
    return;
  }

  if (noteIndex >= noteCount) noteIndex = noteCount - 1;
  uint8_t first = 0;
  if (noteCount > 4) {
    first = noteIndex > 1 ? noteIndex - 1 : 0;
    if (first + 4 > noteCount) first = noteCount - 4;
  }

  for (uint8_t row = 0; row < 4 && first + row < noteCount; row++) {
    uint8_t index = first + row;
    int16_t y = 34 + row * 27;
    bool selected = index == noteIndex;
    gfx->fillRoundRect(10, y, 300, 23, 6, selected ? C_SURFACE2 : C_SURFACE);
    if (selected) gfx->drawRoundRect(10, y, 300, 23, 6, C_MINT);
    gfx->setTextSize(1);
    gfx->setTextColor(selected ? C_MINT : C_MUTED);
    gfx->setCursor(18, y + 8);
    gfx->print(String(index + 1));
    gfx->setTextColor(C_TEXT);
    gfx->setCursor(38, y + 8);
    gfx->print(ellipsize(notes[index].title, 39));
  }

  drawFooterHint("<", "OK abrir  OK+DER +", ">");
  drawToastOverlay();
}

void drawNoteEditor() {
  gfx->fillScreen(C_BG);
  drawHeader(noteEditingTitle ? "NOTA > TITULO" : "NOTA > CONTENIDO", C_MINT);

  gfx->fillRoundRect(8, 31, 304, 20, 5, C_SURFACE);
  gfx->drawRoundRect(8, 31, 304, 20, 5, noteEditingTitle ? C_MINT : C_SURFACE2);
  gfx->setTextSize(1);
  gfx->setTextColor(noteEditingTitle ? C_MINT : C_MUTED);
  gfx->setCursor(14, 38);
  gfx->print("T: ");
  gfx->setTextColor(C_TEXT);
  gfx->print(ellipsize(noteDraftTitle, 45));

  gfx->fillRoundRect(8, 54, 304, 20, 5, C_SURFACE);
  gfx->drawRoundRect(8, 54, 304, 20, 5, noteEditingTitle ? C_SURFACE2 : C_MINT);
  String contentPreview = asciiSafe(noteDraftContent);
  contentPreview.replace('\n', ' ');
  if (contentPreview.length() > 44) contentPreview = contentPreview.substring(contentPreview.length() - 44);
  gfx->setTextColor(noteEditingTitle ? C_MUTED : C_MINT);
  gfx->setCursor(14, 61);
  gfx->print("C: ");
  gfx->setTextColor(C_TEXT);
  gfx->print(contentPreview);

  const uint8_t cols = 7;
  const int16_t keyW = 40;
  const int16_t keyH = 13;
  const int16_t x0 = 12;
  const int16_t y0 = 79;
  for (uint8_t idx = 0; idx < 34; idx++) {
    uint8_t row = idx / cols;
    uint8_t col = idx % cols;
    int16_t x = x0 + col * 43;
    int16_t y = y0 + row * 14;
    bool selected = idx == noteKeyboardIndex;
    gfx->fillRoundRect(x, y, keyW, keyH, 4, selected ? C_MINT : C_SURFACE2);
    String label = noteKeyboardLabel(idx);
    gfx->setTextSize(1);
    gfx->setTextColor(selected ? C_BG : C_TEXT);
    int16_t lx = x + (keyW - (int16_t)label.length() * 6) / 2;
    gfx->setCursor(lx, y + 3);
    gfx->print(label);
  }

  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(6, 154);
  gfx->print("OK+IZQ campo");
  gfx->setCursor(215, 154);
  gfx->print("IZQ+DER guardar");
  drawToastOverlay();
}

void drawNoteView() {
  gfx->fillScreen(C_BG);
  drawHeader("NOTA", C_MINT);
  if (noteIndex >= noteCount) {
    screen = SCREEN_NOTES_LIST;
    drawNotesList();
    return;
  }

  uint8_t pages = noteLineCount == 0
    ? 1
    : (uint8_t)((noteLineCount + NOTE_LINES_PER_PAGE - 1) / NOTE_LINES_PER_PAGE);
  if (notePage >= pages) notePage = pages - 1;

  drawCard(8, 31, 304, 22, C_SURFACE2);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MINT);
  gfx->setCursor(15, 39);
  gfx->print(ellipsize(notes[noteIndex].title, 38));
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(276, 39);
  gfx->print(String(notePage + 1) + "/" + String(pages));

  drawCard(8, 56, 304, 88, C_SURFACE);
  uint8_t start = notePage * NOTE_LINES_PER_PAGE;
  uint8_t candidateEnd = start + NOTE_LINES_PER_PAGE;
  uint8_t endLine = noteLineCount < candidateEnd ? noteLineCount : candidateEnd;
  gfx->setTextSize(1);
  gfx->setTextColor(C_TEXT);
  int16_t y = 64;
  for (uint8_t i = start; i < endLine; i++) {
    gfx->setCursor(15, y);
    gfx->print(ellipsize(noteLines[i], 47));
    y += 9;
  }

  drawFooterHint("< subir", "OK+IZQ editar", "bajar >");
  drawToastOverlay();
}

void drawLights() {
  gfx->fillScreen(C_BG);
  drawHeader("LUCES", C_GOLD);

  if (cfg.lightCount == 0) {
    drawCard(10, 39, 300, 94, C_SURFACE);
    centerText("Sin luces configuradas", 65, 2, C_MUTED);
    centerText("Anadelas desde la app", 96, 1, C_TEXT);
    drawFooterHint("", "", "");
    return;
  }

  String name = cfg.lightNames[lightIndex].isEmpty() ? cfg.lightIds[lightIndex] : cfg.lightNames[lightIndex];

  drawCard(12, 39, 188, 94, C_SURFACE);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(24, 52);
  gfx->print("LUZ SELECCIONADA");
  gfx->setTextSize(2);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(24, 76);
  gfx->print(ellipsize(name, 14));
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(24, 111);
  gfx->print(String(lightIndex + 1) + " / " + String(cfg.lightCount));

  drawCard(210, 39, 98, 94, C_SURFACE2);
  if (lightKnown) {
    gfx->fillCircle(259, 76, 25, lightOn ? C_GOLD : C_SURFACE);
    gfx->drawCircle(259, 76, 26, lightOn ? C_GOLD : C_MUTED);
    String stateLabel = lightOn ? "ON" : "OFF";
    gfx->setTextSize(2);
    gfx->setTextColor(lightOn ? C_GREEN : C_MUTED);
    int16_t stateX = 259 - ((int16_t)stateLabel.length() * 12) / 2;
    gfx->setCursor(stateX, 108);
    gfx->print(stateLabel);
  } else {
    gfx->setTextSize(4);
    gfx->setTextColor(C_MUTED);
    gfx->setCursor(247, 58);
    gfx->print("?");
    gfx->setTextSize(1);
    gfx->setCursor(230, 108);
    gfx->print("Sin estado");
  }

  drawFooterHint("<", "OK ON/OFF", ">");
  drawToastOverlay();
}

void drawClimate() {
  gfx->fillScreen(C_BG);
  drawHeader("TERMOSTATO", C_RED);

  if (cfg.climateId.isEmpty()) {
    drawCard(10, 39, 300, 94, C_SURFACE);
    centerText("Sin termostato", 65, 2, C_MUTED);
    centerText("Configuralo desde la app", 96, 1, C_TEXT);
    drawFooterHint("", "", "");
    return;
  }

  drawCard(12, 39, 296, 94, C_SURFACE);
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(24, 52);
  gfx->print(ellipsize(cfg.climateName.isEmpty() ? "Termostato" : cfg.climateName, 22));

  if (climateKnown && !isnan(climateTarget)) {
    String t = String(climateTarget, 1);
    gfx->setTextSize(5);
    gfx->setTextColor(C_TEXT);
    gfx->setCursor(92, 67);
    gfx->print(t);
    gfx->setTextSize(2);
    gfx->setTextColor(C_RED);
    gfx->setCursor(226, 72);
    gfx->print("C");
  } else {
    centerText("--.- C", 70, 4, C_MUTED);
  }

  gfx->fillRoundRect(23, 92, 58, 25, 7, C_SURFACE2);
  gfx->fillRoundRect(239, 92, 58, 25, 7, C_SURFACE2);
  gfx->setTextSize(2);
  gfx->setTextColor(C_BLUE);
  gfx->setCursor(31, 98);
  gfx->print("-0.5");
  gfx->setCursor(247, 98);
  gfx->print("+0.5");

  drawFooterHint("< bajar", "", "subir >");
  drawToastOverlay();
}

void drawAiKeyboard() {
  gfx->fillScreen(C_BG);
  drawHeader("IA", C_PURPLE);

  drawCard(8, 31, 304, 43, C_SURFACE);
  String safe = asciiSafe(aiQuery);
  if (safe.length() > 92) safe = safe.substring(safe.length() - 92);
  gfx->setTextSize(1);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(16, 42);
  size_t firstLineEnd = safe.length() < 46 ? safe.length() : 46;
  gfx->print(safe.substring(0, firstLineEnd));
  if (safe.length() > 46) {
    gfx->setCursor(16, 56);
    gfx->print(safe.substring(46));
  }

  const uint8_t cols = 7;
  const int16_t keyW = 40;
  const int16_t keyH = 13;
  const int16_t x0 = 12;
  const int16_t y0 = 80;

  for (uint8_t idx = 0; idx < 34; idx++) {
    uint8_t row = idx / cols;
    uint8_t col = idx % cols;
    int16_t x = x0 + col * 43;
    int16_t y = y0 + row * 14;
    bool selected = idx == keyboardIndex;
    gfx->fillRoundRect(x, y, keyW, keyH, 4, selected ? C_PURPLE : C_SURFACE2);
    String label = keyboardLabel(idx);
    gfx->setTextSize(1);
    gfx->setTextColor(selected ? C_BG : C_TEXT);
    int16_t lx = x + (keyW - (int16_t)label.length() * 6) / 2;
    gfx->setCursor(lx, y + 3);
    gfx->print(label);
  }

  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(255, 154);
  gfx->print(aiUpper ? "MAYUS" : "minus");
  drawToastOverlay();
}

void drawAiWait() {
  gfx->fillScreen(C_BG);
  drawHeader("IA", C_PURPLE);
  gfx->fillCircle(88, 91, 30, C_SURFACE2);
  gfx->fillCircle(77, 91, 4, C_PURPLE);
  gfx->fillCircle(88, 91, 4, C_BLUE);
  gfx->fillCircle(99, 91, 4, C_MINT);
  gfx->setTextSize(3);
  gfx->setTextColor(C_TEXT);
  gfx->setCursor(137, 71);
  gfx->print("Pensando");
  gfx->setTextSize(1);
  gfx->setTextColor(C_MUTED);
  gfx->setCursor(139, 105);
  gfx->print("OpenRouter en Android");
}

void drawAiResponse() {
  gfx->fillScreen(C_BG);
  drawHeader("RESPUESTA IA", C_PURPLE);
  drawCard(8, 31, 304, 113, C_SURFACE);

  uint8_t pages = aiLineCount == 0 ? 1 : (uint8_t)((aiLineCount + AI_LINES_PER_PAGE - 1) / AI_LINES_PER_PAGE);
  if (aiPage >= pages) aiPage = pages - 1;
  uint8_t start = aiPage * AI_LINES_PER_PAGE;
  uint8_t candidateEnd = start + AI_LINES_PER_PAGE;
  uint8_t endLine = (aiLineCount < candidateEnd) ? aiLineCount : candidateEnd;

  gfx->setTextSize(1);
  gfx->setTextColor(aiError.isEmpty() ? C_TEXT : C_RED);
  int16_t y = 39;
  for (uint8_t i = start; i < endLine; i++) {
    gfx->setCursor(16, y);
    gfx->print(ellipsize(aiLines[i], 47));
    y += 9;
  }

  gfx->setTextColor(C_MUTED);
  gfx->setCursor(252, 133);
  gfx->print(String(aiPage + 1) + "/" + String(pages));
  drawFooterHint("< pagina", "OK nueva", "pagina >");
  drawToastOverlay();
}

// -----------------------------------------------------------------------------
// Flappy Bird
// -----------------------------------------------------------------------------
void resetGame() {
  gameRunning = true;
  gameOver = false;
  birdY = 84.0f;
  birdV = -1.0f;
  gameScore = 0;

  pipes[0].x = 340;
  pipes[0].gapY = random(66, 114);
  pipes[0].scored = false;

  pipes[1].x = 505;
  pipes[1].gapY = random(66, 114);
  pipes[1].scored = false;

  gameLastFrameMs = millis();
}

void flap() {
  if (!gameRunning || gameOver) return;
  birdV = -5.1f;
}

bool gameCollision() {
  const float birdX = 78.0f;
  const float birdR = 7.0f;
  const int16_t ceiling = 20;
  const int16_t floorY = 158;
  if (birdY - birdR <= ceiling || birdY + birdR >= floorY) return true;

  const int16_t pipeW = 34;
  const int16_t gapHalf = 27;
  for (uint8_t i = 0; i < 2; i++) {
    float px = pipes[i].x;
    bool xOverlap = birdX + birdR > px && birdX - birdR < px + pipeW;
    if (!xOverlap) continue;
    int16_t gapTop = pipes[i].gapY - gapHalf;
    int16_t gapBottom = pipes[i].gapY + gapHalf;
    if (birdY - birdR < gapTop || birdY + birdR > gapBottom) return true;
  }
  return false;
}

void updateGame() {
  if (screen != SCREEN_FLAPPY || gameOver || !gameRunning) return;
  uint32_t now = millis();
  if (now - gameLastFrameMs < GAME_FRAME_MS) return;

  float dt = (now - gameLastFrameMs) / 33.0f;
  gameLastFrameMs = now;
  dt = constrain(dt, 0.5f, 2.0f);

  birdV += 0.40f * dt;
  birdY += birdV * dt;

  const float speed = 3.0f * dt;
  const int16_t pipeW = 34;
  for (uint8_t i = 0; i < 2; i++) {
    pipes[i].x -= speed;
    if (!pipes[i].scored && pipes[i].x + pipeW < 78) {
      pipes[i].scored = true;
      gameScore++;
      if (gameScore > flappyHighScore) {
        flappyHighScore = gameScore;
        prefs.putULong("flapHi", flappyHighScore);
      }
    }
    if (pipes[i].x < -pipeW - 4) {
      float otherX = pipes[(i + 1) % 2].x;
      float nextX = otherX + 165.0f;
      pipes[i].x = (nextX > 340.0f) ? nextX : 340.0f;
      pipes[i].gapY = random(66, 114);
      pipes[i].scored = false;
    }
  }

  if (gameCollision()) {
    gameOver = true;
    gameRunning = false;
  }
}

void drawBird(int16_t x, int16_t y) {
  gfx->fillCircle(x, y, 8, C_BIRD);
  gfx->fillCircle(x + 6, y - 3, 5, C_BIRD);
  gfx->fillCircle(x + 8, y - 4, 2, C_WHITE);
  gfx->fillCircle(x + 9, y - 4, 1, C_BLACK);
  gfx->fillTriangle(x + 11, y - 1, x + 19, y + 2, x + 11, y + 5, C_BIRD_OR);
  gfx->fillTriangle(x - 5, y, x - 14, y - 7, x - 12, y + 6, C_BIRD_OR);
}

void drawPipe(const Pipe &p) {
  int16_t x = (int16_t)p.x;
  const int16_t pipeW = 34;
  const int16_t gapHalf = 27;
  const int16_t ceiling = 20;
  const int16_t floorY = 158;
  int16_t gapTop = p.gapY - gapHalf;
  int16_t gapBottom = p.gapY + gapHalf;

  if (gapTop > ceiling) {
    gfx->fillRect(x + 4, ceiling, pipeW - 8, gapTop - ceiling - 5, C_PIPE);
    gfx->fillRect(x, gapTop - 9, pipeW, 9, C_PIPE);
    int16_t h = gapTop - ceiling; if (h < 1) h = 1;
    gfx->drawFastVLine(x + 5, ceiling, h, C_PIPE_DK);
  }
  if (gapBottom < floorY) {
    gfx->fillRect(x, gapBottom, pipeW, 9, C_PIPE);
    gfx->fillRect(x + 4, gapBottom + 9, pipeW - 8, floorY - gapBottom - 9, C_PIPE);
    int16_t h = floorY - gapBottom; if (h < 1) h = 1;
    gfx->drawFastVLine(x + 5, gapBottom, h, C_PIPE_DK);
  }
}

void drawFlappy() {
  gfx->fillScreen(RGB565(38, 105, 148));
  gfx->fillCircle(34, 39, 11, RGB565(212, 238, 244));
  gfx->fillCircle(46, 35, 15, RGB565(212, 238, 244));
  gfx->fillCircle(60, 40, 9, RGB565(212, 238, 244));
  gfx->fillCircle(246, 54, 10, RGB565(212, 238, 244));
  gfx->fillCircle(258, 50, 14, RGB565(212, 238, 244));

  gfx->fillRoundRect(8, 5, 77, 19, 7, RGB565(20, 63, 91));
  gfx->setTextSize(1);
  gfx->setTextColor(C_WHITE);
  gfx->setCursor(16, 11);
  gfx->print("SCORE "); gfx->print(gameScore);

  gfx->fillRoundRect(235, 5, 77, 19, 7, RGB565(20, 63, 91));
  gfx->setCursor(243, 11);
  gfx->print("BEST "); gfx->print(flappyHighScore);

  for (uint8_t i = 0; i < 2; i++) drawPipe(pipes[i]);

  gfx->fillRect(0, 158, LCD_W, 14, RGB565(208, 191, 86));
  gfx->drawFastHLine(0, 158, LCD_W, C_WHITE);
  gfx->drawFastHLine(0, 161, LCD_W, RGB565(106, 167, 74));
  drawBird(78, (int16_t)birdY);

  if (gameOver) {
    gfx->fillRoundRect(93, 51, 134, 72, 10, RGB565(20, 63, 91));
    gfx->drawRoundRect(93, 51, 134, 72, 10, C_WHITE);
    centerText("GAME OVER", 61, 2, C_WHITE);
    centerText("Score " + String(gameScore), 88, 1, C_WHITE);
    centerText("DER reinicia", 104, 1, C_GOLD);
  } else {
    gfx->setTextSize(1);
    gfx->setTextColor(C_WHITE);
    gfx->setCursor(127, 145);
    gfx->print("OK = VOLAR");
  }
}

void drawCurrentScreen() {
  if (displaySleeping) return;

  if (cfg.ssid.isEmpty() && screen == SCREEN_HOME_LOCKED) {
    drawSetupScreen();
    uiDirty = false;
    return;
  }

  switch (screen) {
    case SCREEN_HOME_LOCKED:    drawHome(); break;
    case SCREEN_MENU:           drawMenu(); break;
    case SCREEN_NOTIFICATIONS:  drawNotifications(); break;
    case SCREEN_LIGHTS:         drawLights(); break;
    case SCREEN_CLIMATE:        drawClimate(); break;
    case SCREEN_NOTES_LIST:     drawNotesList(); break;
    case SCREEN_NOTE_EDITOR:    drawNoteEditor(); break;
    case SCREEN_NOTE_VIEW:      drawNoteView(); break;
    case SCREEN_AI_KEYBOARD:    drawAiKeyboard(); break;
    case SCREEN_AI_WAIT:        drawAiWait(); break;
    case SCREEN_AI_RESPONSE:    drawAiResponse(); break;
    case SCREEN_FLAPPY:         drawFlappy(); break;
  }

  uiDirty = false;
}

// -----------------------------------------------------------------------------
// Buttons / navigation
// -----------------------------------------------------------------------------
uint8_t readButtonMaskRaw() {
  uint8_t mask = 0;
  if (digitalRead(PIN_LEFT) == LOW)   mask |= 0x01;
  if (digitalRead(PIN_CENTER) == LOW) mask |= 0x02;
  if (digitalRead(PIN_RIGHT) == LOW)  mask |= 0x04;
  return mask;
}

void resetButtonGestureState() {
  gestureCollecting = false;
  gestureMask = 0;
  gestureStartedAt = 0;
  gestureEmitted = false;
}

void lockPanel() {
  uiDirty = true;

  if (screen == SCREEN_AI_KEYBOARD ||
      screen == SCREEN_AI_WAIT ||
      screen == SCREEN_AI_RESPONSE) {
    endAiSession();
  }

  if (screen == SCREEN_NOTE_EDITOR) {
    noteDraftTitle = "";
    noteDraftContent = "";
    noteEditingNew = false;
  }

  unlocked = false;
  screen = SCREEN_HOME_LOCKED;
  menuIndex = 0;
  unlockPos = 0;
}

void turnDisplayOffAndLock() {
  lockPanel();

  displaySleeping = true;
  sleepCanWake = false;
  discardButtonsUntilRelease = false;

  setBacklight(0);
  gfx->fillScreen(C_BLACK);

  resetButtonGestureState();
}

void wakeDisplayAndConsumePress() {
  displaySleeping = false;
  uiDirty = true;
  sleepCanWake = false;
  discardButtonsUntilRelease = true;

  setBacklight(cfg.brightness);
  unlockPos = 0;

  drawCurrentScreen();
}

void unlockAnimation() {
  gfx->fillScreen(C_BG);

  centerText("DESBLOQUEADO", 120, 2, C_MINT);

  for (uint8_t i = 0; i < 4; i++) {
    gfx->fillRoundRect(33 + i * 28, 165, 18, 5, 2, i < 4 ? C_MINT : C_SURFACE2);
    delay(45);
  }
}

void handleUnlockSingle(uint8_t keyMask) {
  if (keyMask == UNLOCK_SEQ[unlockPos]) {
    unlockPos++;

    if (unlockPos >= 3) {
      unlockPos = 0;
      unlocked = true;
      screen = SCREEN_MENU;
      lastInteractionMs = millis();

      unlockAnimation();
      delay(100);
    }
  } else {
    unlockPos = (keyMask == UNLOCK_SEQ[0]) ? 1 : 0;
  }
}

void activateMenuItem() {
  switch (menuIndex) {
    case 0:
      notificationIndex = 0;
      screen = SCREEN_NOTIFICATIONS;
      break;

    case 1:
      lightIndex = 0;
      lightKnown = false;
      screen = SCREEN_LIGHTS;
      drawCurrentScreen();

      if (cfg.lightCount > 0 && !refreshCurrentLight()) {
        showToast(haLastError, 1800);
      }
      break;

    case 2:
      climateKnown = false;
      screen = SCREEN_CLIMATE;
      drawCurrentScreen();

      if (!cfg.climateId.isEmpty() && !refreshClimate()) {
        showToast(haLastError, 1800);
      }
      break;

    case 3:
      startAiSession();
      break;

    case 4:
      screen = SCREEN_FLAPPY;
      resetGame();
      break;

    case 5:
      noteIndex = 0;
      screen = SCREEN_NOTES_LIST;
      break;
  }
}

void handleBackGesture() {
  if (!unlocked) return;

  switch (screen) {
    case SCREEN_MENU:
      lockPanel();
      break;

    case SCREEN_AI_KEYBOARD:
    case SCREEN_AI_WAIT:
    case SCREEN_AI_RESPONSE:
      endAiSession();
      screen = SCREEN_MENU;
      break;

    case SCREEN_FLAPPY:
      gameRunning = false;
      gameOver = false;
      screen = SCREEN_MENU;
      break;

    default:
      screen = SCREEN_MENU;
      break;
  }
}

void handleButtonGesture(uint8_t mask) {
  if (mask == 0) return;

  lastInteractionMs = millis();

  // Highest-priority global shortcut: all three buttons always lock the panel
  // and switch off the backlight, regardless of the current screen or mode.
  if (mask == (0x01 | 0x02 | 0x04)) {
    turnDisplayOffAndLock();
    return;
  }

  // Notes use chords that intentionally differ from the rest of the UI.
  if (unlocked && screen == SCREEN_NOTES_LIST && mask == (0x02 | 0x04)) {
    beginNewNote();
    return;
  }
  if (unlocked && screen == SCREEN_NOTE_EDITOR && mask == (0x01 | 0x02)) {
    toggleNoteEditorField();
    return;
  }
  if (unlocked && screen == SCREEN_NOTE_VIEW && mask == (0x01 | 0x02)) {
    beginEditNote();
    return;
  }
  if (unlocked && screen == SCREEN_NOTE_EDITOR && mask == (0x01 | 0x04)) {
    saveEditedNote();
    return;
  }
  if (unlocked && screen == SCREEN_NOTE_VIEW && mask == (0x01 | 0x04)) {
    screen = SCREEN_NOTES_LIST;
    return;
  }

  // LEFT+RIGHT is Back while unlocked. It deliberately does nothing on the
  // locked home screen, so the unlock sequence is never displayed there.
  if (mask == (0x01 | 0x04)) {
    if (unlocked) handleBackGesture();
    return;
  }

  // Manual CoinMarketCap update ONLY on locked home.
  if (mask == (0x01 | 0x02)) {
    if (screen == SCREEN_HOME_LOCKED && !unlocked) {
      bool ok = refreshCrypto();
      showToast(ok ? "Precio actualizado" : cryptoLastError, 1800);
    }
    return;
  }

  // Ignore other multi-button chords.
  if ((mask & (mask - 1)) != 0) return;

  if (!unlocked || screen == SCREEN_HOME_LOCKED) {
    handleUnlockSingle(mask);
    return;
  }

  switch (screen) {
    case SCREEN_MENU:
      if (mask == 0x01) {
        menuIndex = (menuIndex + MENU_COUNT - 1) % MENU_COUNT;
      } else if (mask == 0x04) {
        menuIndex = (menuIndex + 1) % MENU_COUNT;
      } else if (mask == 0x02) {
        activateMenuItem();
      }
      break;

    case SCREEN_NOTIFICATIONS:
      if (notificationCount == 0) break;

      if (mask == 0x01) {
        notificationIndex =
          (notificationIndex + notificationCount - 1) % notificationCount;
      } else if (mask == 0x04) {
        notificationIndex = (notificationIndex + 1) % notificationCount;
      } else if (mask == 0x02) {
        dismissCurrentNotification();
      }
      break;

    case SCREEN_LIGHTS:
      if (cfg.lightCount == 0) break;

      if (mask == 0x01 || mask == 0x04) {
        if (mask == 0x01) {
          lightIndex = (lightIndex + cfg.lightCount - 1) % cfg.lightCount;
        } else {
          lightIndex = (lightIndex + 1) % cfg.lightCount;
        }

        lightKnown = false;
        drawCurrentScreen();

        if (!refreshCurrentLight()) {
          showToast(haLastError, 1800);
        }
      } else if (mask == 0x02) {
        if (!toggleCurrentLight()) {
          showToast(haLastError, 1800);
        } else {
          showToast(lightOn ? "Luz encendida" : "Luz apagada", 1200);
        }
      }
      break;

    case SCREEN_CLIMATE:
      if (mask == 0x01 || mask == 0x04) {
        if (!climateKnown && !refreshClimate()) {
          showToast(haLastError, 1800);
          break;
        }

        float next =
          climateTarget + (mask == 0x01 ? -0.5f : 0.5f);

        if (!setClimateTarget(next)) {
          showToast(haLastError, 1800);
        } else {
          showToast("Temperatura " + String(climateTarget, 1) + " C", 1000);
        }
      }
      break;

    case SCREEN_NOTES_LIST:
      if (noteCount == 0) break;
      if (mask == 0x01) {
        noteIndex = (noteIndex + noteCount - 1) % noteCount;
      } else if (mask == 0x04) {
        noteIndex = (noteIndex + 1) % noteCount;
      } else if (mask == 0x02) {
        openCurrentNote();
      }
      break;

    case SCREEN_NOTE_EDITOR:
      if (mask == 0x01) {
        noteKeyboardIndex = (noteKeyboardIndex + 33) % 34;
      } else if (mask == 0x04) {
        noteKeyboardIndex = (noteKeyboardIndex + 1) % 34;
      } else if (mask == 0x02) {
        noteKeyboardSelect();
      }
      break;

    case SCREEN_NOTE_VIEW: {
      uint8_t pages = noteLineCount == 0
        ? 1
        : (uint8_t)((noteLineCount + NOTE_LINES_PER_PAGE - 1) / NOTE_LINES_PER_PAGE);
      if (mask == 0x01 && notePage > 0) {
        notePage--;
      } else if (mask == 0x04 && notePage + 1 < pages) {
        notePage++;
      }
      break;
    }

    case SCREEN_AI_KEYBOARD:
      if (mask == 0x01) {
        keyboardIndex = (keyboardIndex + 33) % 34;
      } else if (mask == 0x04) {
        keyboardIndex = (keyboardIndex + 1) % 34;
      } else if (mask == 0x02) {
        aiKeyboardSelect();
      }
      break;

    case SCREEN_AI_WAIT:
      break;

    case SCREEN_AI_RESPONSE: {
      uint8_t pages =
        aiLineCount == 0
          ? 1
          : (uint8_t)((aiLineCount + AI_LINES_PER_PAGE - 1) / AI_LINES_PER_PAGE);

      if (mask == 0x01) {
        aiPage = (aiPage + pages - 1) % pages;
      } else if (mask == 0x04) {
        aiPage = (aiPage + 1) % pages;
      } else if (mask == 0x02) {
        aiQuery = "";
        keyboardIndex = 0;
        screen = SCREEN_AI_KEYBOARD;
      }
      break;
    }

    case SCREEN_FLAPPY:
      if (mask == 0x02 && !gameOver) {
        flap();
      } else if (mask == 0x04 && gameOver) {
        resetGame();
      }
      break;

    default:
      break;
  }
}


void printButtonDiagnostic(uint8_t previousMask, uint8_t currentMask) {
  uint8_t changed = previousMask ^ currentMask;

  if (changed & 0x01) {
    Serial.print("[BOTON] IZQUIERDA GPIO3  -> ");
    Serial.println((currentMask & 0x01) ? "PULSADO" : "SUELTO");
  }

  if (changed & 0x02) {
    Serial.print("[BOTON] CENTRO GPIO23    -> ");
    Serial.println((currentMask & 0x02) ? "PULSADO" : "SUELTO");
  }

  if (changed & 0x04) {
    Serial.print("[BOTON] DERECHA GPIO0    -> ");
    Serial.println((currentMask & 0x04) ? "PULSADO" : "SUELTO");
  }

  Serial.print("[BOTON] Estado estable: ");

  if (currentMask == 0) {
    Serial.println("NINGUNO");
    return;
  }

  bool first = true;

  if (currentMask & 0x01) {
    Serial.print("IZQUIERDA(GPIO3)");
    first = false;
  }

  if (currentMask & 0x02) {
    if (!first) Serial.print(" + ");
    Serial.print("CENTRO(GPIO23)");
    first = false;
  }

  if (currentMask & 0x04) {
    if (!first) Serial.print(" + ");
    Serial.print("DERECHA(GPIO0)");
  }

  Serial.println();
}

void updateButtons() {
  uint8_t nowRaw = readButtonMaskRaw();
  uint32_t now = millis();

  if (nowRaw != lastRawMask) {
    lastRawMask = nowRaw;
    rawChangedAt = now;
  }

  bool stableChanged = false;

  if ((now - rawChangedAt) >= BUTTON_DEBOUNCE_MS &&
      stableMask != nowRaw) {
    uint8_t previousStableMask = stableMask;
    stableMask = nowRaw;
    stableChanged = true;

    // Diagnóstico por Monitor Serie: muestra exactamente lo que el firmware
    // considera pulsado DESPUÉS del debounce.
    printButtonDiagnostic(previousStableMask, stableMask);
  }

  // Screen off: require complete release first.
  if (displaySleeping) {
    resetButtonGestureState();

    if (stableMask == 0) {
      sleepCanWake = true;
      return;
    }

    if (sleepCanWake) {
      wakeDisplayAndConsumePress();
    }
    return;
  }

  // Wake press is discarded until every button is released.
  if (discardButtonsUntilRelease) {
    resetButtonGestureState();
    if (stableMask == 0) discardButtonsUntilRelease = false;
    return;
  }

  // All three buttons have immediate global priority over every screen and
  // gesture. While they remain held, sleepCanWake stays false until release.
  if (stableMask == (0x01 | 0x02 | 0x04)) {
    turnDisplayOffAndLock();
    return;
  }

  if (stableChanged) {
    if (stableMask != 0 && !gestureCollecting) {
      gestureCollecting = true;
      gestureMask = stableMask;
      gestureStartedAt = now;
      gestureEmitted = false;
    } else if (gestureCollecting && stableMask != 0) {
      gestureMask |= stableMask;
    } else if (gestureCollecting && stableMask == 0) {
      if (!gestureEmitted) {
        handleButtonGesture(gestureMask);
        uiDirty = true;
      }
      resetButtonGestureState();
    }
  }

  if (gestureCollecting &&
      !gestureEmitted &&
      stableMask != 0 &&
      now - gestureStartedAt >= CHORD_WINDOW_MS) {
    gestureMask |= stableMask;
    handleButtonGesture(gestureMask);
    uiDirty = true;
    gestureEmitted = true;
  }
}

// -----------------------------------------------------------------------------
// Setup / loop
// -----------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(80);

  pinMode(PIN_LEFT, INPUT_PULLUP);
  pinMode(PIN_CENTER, INPUT_PULLUP);
  pinMode(PIN_RIGHT, INPUT_PULLUP);

  Serial.println();
  Serial.println("==============================================");
  Serial.println(" SmartPanel - DIAGNOSTICO DE BOTONES ACTIVO");
  Serial.println(" Monitor Serie: 115200 baudios");
  Serial.println(" IZQUIERDA = GPIO3");
  Serial.println(" CENTRO    = GPIO23");
  Serial.println(" DERECHA   = GPIO0");
  Serial.println(" Cada boton debe conectar su GPIO con GND.");
  Serial.println("==============================================");
  Serial.println();

  // Backlight PWM using Arduino-ESP32 core 3.x API.
  ledcAttach(LCD_BL, 5000, 8);
  setBacklight(0);

  if (!gfx->begin()) {
    Serial.println("ERROR: LCD init failed");
  }
  gfx->invertDisplay(LCD_INVERT_COLORS);

  gfx->fillScreen(C_BG);

  loadConfig();

  setBacklight(cfg.brightness);

  centerText("SMARTPANEL", 58, 3, C_TEXT);
  centerText("ESP32-C6  LANDSCAPE", 96, 1, C_MINT);
  centerText("Iniciando...", 116, 1, C_MUTED);

  setupBLE();
  connectWiFiNonBlocking();

  lastInteractionMs = millis();
  randomSeed(esp_random());

  delay(450);
  drawCurrentScreen();

  time_t bootNow = time(nullptr);
  if (bootNow > 1600000000) {
    struct tm bootTm;
    if (localtime_r(&bootNow, &bootTm)) {
      lastHomeMinute = bootTm.tm_min;
    }
  }
}

void loop() {
  serviceBleEvents();
  serviceBleIncoming();
  serviceWiFi();
  updateButtons();

  if (unlocked &&
      screen != SCREEN_FLAPPY &&
      millis() - lastInteractionMs >= AUTO_LOCK_MS) {
    lockPanel();
    showToast("Bloqueado", 1000);
  }

  updateGame();

  if (!displaySleeping) {
    // Flappy Bird genuinely needs continuous animation.
    if (screen == SCREEN_FLAPPY) {
      if (millis() - lastUiDrawMs >= GAME_FRAME_MS) {
        lastUiDrawMs = millis();
        drawCurrentScreen();
      }
    } else {
      // Refresh the home clock only when the displayed minute changes.
      if (screen == SCREEN_HOME_LOCKED && !cfg.ssid.isEmpty()) {
        time_t now = time(nullptr);
        if (now > 1600000000) {
          struct tm localTm;
          if (localtime_r(&now, &localTm)) {
            if (localTm.tm_min != lastHomeMinute) {
              lastHomeMinute = localTm.tm_min;
              uiDirty = true;
            }
          }
        }
      }

      // When a toast expires, repaint once to remove it.
      bool toastVisible =
        toastUntilMs != 0 &&
        (int32_t)(toastUntilMs - millis()) > 0;

      if (toastWasVisible && !toastVisible) {
        uiDirty = true;
      }
      toastWasVisible = toastVisible;

      // All ordinary screens redraw only when state actually changed.
      if (uiDirty) {
        drawCurrentScreen();
      }
    }
  }

  delay(1);
}

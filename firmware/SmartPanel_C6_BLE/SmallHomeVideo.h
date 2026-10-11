#pragma once

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include <Preferences.h>
#include <SPI.h>
#include <SD.h>
#include <esp_heap_caps.h>
#include "SmallHomeMjpeg.h"

class SmallHomeVideo {
public:
  enum Mode : uint8_t { LIBRARY, RESUME_PROMPT, PLAYBACK };

  void begin(Arduino_GFX *display, Preferences *preferences, SPIClass *spiBus,
             uint8_t sdChipSelect, int16_t screenW, int16_t screenH) {
    gfx = display;
    prefs = preferences;
    spi = spiBus;
    sdCS = sdChipSelect;
    width = screenW;
    height = screenH;
  }

  bool enter() {
    mode = LIBRARY;
    selected = 0;
    lastError = "";
    if (!ensureStorage()) return false;
    scanFiles();
    return true;
  }

  void leave() {
    stopPlayback(true);
    closeIndex();
    mode = LIBRARY;
  }

  bool isPlaying() const { return mode == PLAYBACK; }
  Mode getMode() const { return mode; }
  uint8_t count() const { return fileCount; }

  void handleButton(uint8_t mask) {
    if (mode == LIBRARY) {
      if (fileCount == 0) {
        if (mask == 0x02) {
          storageReady = false;
          enter();
        }
        return;
      }
      if (mask == 0x01) {
        selected = (selected + fileCount - 1) % fileCount;
      } else if (mask == 0x04) {
        selected = (selected + 1) % fileCount;
      } else if (mask == 0x02) {
        uint32_t saved = loadProgress(items[selected].path);
        uint32_t totalSec = items[selected].durationMs / 1000UL;
        if (items[selected].indexValid && saved >= 5 && totalSec > 10 && saved + 5 < totalSec) {
          pendingResumeSec = saved;
          mode = RESUME_PROMPT;
        } else {
          openSelected(0);
        }
      }
      return;
    }

    if (mode == RESUME_PROMPT) {
      if (mask == 0x01) {
        clearProgress(items[selected].path);
        openSelected(0);
      } else if (mask == 0x02) {
        openSelected(pendingResumeSec);
      } else if (mask == 0x04) {
        mode = LIBRARY;
      }
      return;
    }

    if (mode == PLAYBACK) {
      if (mask == 0x01) {
        seekRelative(-10);
      } else if (mask == 0x04) {
        seekRelative(10);
      } else if (mask == 0x02) {
        paused = !paused;
        showOverlay();
        saveProgress(true);
      }
    }
  }

  // Returns true when playback ended and a full-screen library redraw is needed.
  bool update() {
    if (mode != PLAYBACK) return false;

    uint32_t nowMs = millis();
    bool overlayVisible = (int32_t)(overlayUntilMs - nowMs) > 0;

    if (paused) {
      if (overlayWasVisible && !overlayVisible && decoder.hasFrame()) {
        decoder.redrawFrame();
        overlayWasVisible = false;
      }
      return false;
    }

    uint32_t nowUs = micros();
    if ((int32_t)(nowUs - nextFrameUs) < 0) return false;

    if (currentFrame >= activeHeader.frameCount || !decoder.readFrame()) {
      finishPlayback();
      return true;
    }

    if (!decoder.drawFrame()) {
      lastError = decoder.overflowed() ? "Frame MJPEG demasiado grande" : "Error decodificando MJPEG";
      finishPlayback(false);
      return true;
    }

    currentFrame++;
    if (overlayVisible) drawOverlay();
    overlayWasVisible = overlayVisible;

    uint32_t intervalUs = frameIntervalUs();
    nextFrameUs += intervalUs;
    // Never burst frames after a long stall; continue from the current time.
    if ((int32_t)(nowUs - nextFrameUs) > (int32_t)(intervalUs * 2UL)) {
      nextFrameUs = nowUs + intervalUs;
    }

    if (nowMs - lastProgressSaveMs >= 30000UL) {
      saveProgress(false);
    }
    return false;
  }

  void draw() {
    if (!gfx) return;
    if (mode == LIBRARY) drawLibrary();
    else if (mode == RESUME_PROMPT) drawResumePrompt();
    else drawPlaybackCurrent();
  }

private:
  static const uint8_t MAX_FILES = 24;
  static const size_t FRAME_BUFFER_BYTES = 48UL * 1024UL;
  static const uint32_t OVERLAY_MS = 3000UL;
  static const char *videoFolder() { return "/mjpeg"; }

  struct __attribute__((packed)) IndexHeader {
    char magic[4];       // SHVI
    uint16_t version;    // 1
    uint16_t fps100;     // e.g. 1500 = 15.00 fps
    uint32_t frameCount;
    uint32_t durationMs;
    uint16_t videoWidth;
    uint16_t videoHeight;
    uint16_t intervalMs; // normally 1000
    uint32_t entryCount;
  };

  static_assert(sizeof(IndexHeader) == 26, "Small-Home .mji header layout changed");

  struct Item {
    String name;
    String path;
    String indexPath;
    uint32_t size = 0;
    uint16_t fps100 = 0;
    uint32_t frameCount = 0;
    uint32_t durationMs = 0;
    bool indexValid = false;
  };

  Arduino_GFX *gfx = nullptr;
  Preferences *prefs = nullptr;
  SPIClass *spi = nullptr;
  uint8_t sdCS = 4;
  int16_t width = 320;
  int16_t height = 172;

  bool storageReady = false;
  Item items[MAX_FILES];
  uint8_t fileCount = 0;
  uint8_t selected = 0;
  Mode mode = LIBRARY;
  String lastError;
  uint32_t pendingResumeSec = 0;

  File videoFile;
  File indexFile;
  IndexHeader activeHeader{};
  uint8_t *frameBuffer = nullptr;
  SmallHomeMjpeg decoder;
  uint32_t currentFrame = 0;
  bool paused = false;
  uint32_t nextFrameUs = 0;
  uint32_t overlayUntilMs = 0;
  bool overlayWasVisible = false;
  uint32_t lastProgressSaveMs = 0;

  static int jpegDrawThunk(JPEGDRAW *pDraw) {
    if (!activeInstance || !activeInstance->gfx) return 0;
    activeInstance->gfx->draw16bitBeRGBBitmap(
      pDraw->x, pDraw->y, pDraw->pPixels, pDraw->iWidth, pDraw->iHeight
    );
    return 1;
  }

  static SmallHomeVideo *activeInstance;

  bool ensureStorage() {
    if (storageReady) return true;
    if (!spi) {
      lastError = "Bus SPI no disponible";
      return false;
    }
    pinMode(sdCS, OUTPUT);
    digitalWrite(sdCS, HIGH);
    if (!SD.begin(sdCS, *spi, 40000000, "/sd")) {
      lastError = "No se pudo montar la microSD";
      storageReady = false;
      return false;
    }
    storageReady = true;
    return true;
  }

  void scanFiles() {
    fileCount = 0;
    File dir = SD.open(videoFolder());
    if (!dir || !dir.isDirectory()) {
      lastError = "Crea /mjpeg en la microSD";
      if (dir) dir.close();
      return;
    }

    File f = dir.openNextFile();
    while (f && fileCount < MAX_FILES) {
      if (!f.isDirectory()) {
        String name = baseName(String(f.name()));
        String lower = name;
        lower.toLowerCase();
        if (lower.endsWith(".mjpeg")) {
          Item &it = items[fileCount];
          it.name = name;
          it.path = String(videoFolder()) + "/" + name;
          it.indexPath = it.path.substring(0, it.path.length() - 6) + ".mji";
          it.size = f.size();
          it.indexValid = readMetadata(it.indexPath, it);
          fileCount++;
        }
      }
      f.close();
      f = dir.openNextFile();
    }
    dir.close();
    if (fileCount == 0 && lastError.isEmpty()) lastError = "No hay peliculas .mjpeg";
  }

  bool readMetadata(const String &path, Item &item) {
    File idx = SD.open(path, FILE_READ);
    if (!idx) return false;
    IndexHeader h{};
    bool ok = idx.read((uint8_t *)&h, sizeof(h)) == sizeof(h) && validHeader(h);
    idx.close();
    if (!ok) return false;
    item.fps100 = h.fps100;
    item.frameCount = h.frameCount;
    item.durationMs = h.durationMs;
    return true;
  }

  bool validHeader(const IndexHeader &h) const {
    return h.magic[0] == 'S' && h.magic[1] == 'H' && h.magic[2] == 'V' && h.magic[3] == 'I' &&
           h.version == 1 && h.fps100 >= 100 && h.frameCount > 0 && h.durationMs > 0 &&
           h.intervalMs > 0 && h.entryCount > 0;
  }

  bool openSelected(uint32_t startSec) {
    if (selected >= fileCount) return false;
    Item &it = items[selected];
    if (!it.indexValid) {
      lastError = "Falta el .mji: usa el conversor Small-Home";
      mode = LIBRARY;
      return false;
    }

    stopPlayback(false);
    closeIndex();

    videoFile = SD.open(it.path, FILE_READ);
    indexFile = SD.open(it.indexPath, FILE_READ);
    if (!videoFile || !indexFile) {
      lastError = "No se pudo abrir la pelicula";
      if (videoFile) videoFile.close();
      if (indexFile) indexFile.close();
      mode = LIBRARY;
      return false;
    }

    if (indexFile.read((uint8_t *)&activeHeader, sizeof(activeHeader)) != sizeof(activeHeader) ||
        !validHeader(activeHeader)) {
      lastError = "Indice .mji no valido";
      videoFile.close();
      indexFile.close();
      mode = LIBRARY;
      return false;
    }

    if (!frameBuffer) {
      frameBuffer = (uint8_t *)heap_caps_malloc(FRAME_BUFFER_BYTES, MALLOC_CAP_8BIT);
      if (!frameBuffer) {
        lastError = "Sin RAM para el buffer de video";
        videoFile.close();
        indexFile.close();
        mode = LIBRARY;
        return false;
      }
    }

    activeInstance = this;
    if (!seekToSecond(startSec, false)) {
      lastError = "No se pudo posicionar el video";
      videoFile.close();
      indexFile.close();
      mode = LIBRARY;
      return false;
    }

    paused = false;
    mode = PLAYBACK;
    lastError = "";
    nextFrameUs = micros();
    lastProgressSaveMs = millis();
    showOverlay();

    // Draw the first frame immediately so entering the player feels instant.
    if (decoder.readFrame() && decoder.drawFrame()) {
      currentFrame++;
      drawOverlay();
      overlayWasVisible = true;
      nextFrameUs = micros() + frameIntervalUs();
    }
    return true;
  }

  void stopPlayback(bool persist) {
    if (mode == PLAYBACK && persist) saveProgress(true);
    if (videoFile) videoFile.close();
    closeIndex();
    paused = false;
    overlayUntilMs = 0;
    overlayWasVisible = false;
  }

  void finishPlayback(bool completed = true) {
    if (completed && selected < fileCount) clearProgress(items[selected].path);
    else saveProgress(true);
    if (videoFile) videoFile.close();
    closeIndex();
    mode = LIBRARY;
    paused = false;
    overlayUntilMs = 0;
    overlayWasVisible = false;
  }

  void closeIndex() {
    if (indexFile) indexFile.close();
  }

  uint32_t frameIntervalUs() const {
    if (activeHeader.fps100 == 0) return 66667UL;
    return (uint32_t)(100000000ULL / activeHeader.fps100);
  }

  uint32_t currentSecond() const {
    if (activeHeader.fps100 == 0) return 0;
    return (uint32_t)(((uint64_t)currentFrame * 100ULL) / activeHeader.fps100);
  }

  uint32_t totalSeconds() const {
    return activeHeader.durationMs / 1000UL;
  }

  bool seekToSecond(uint32_t sec, bool drawImmediately) {
    uint32_t totalSec = activeHeader.durationMs / 1000UL;
    if (totalSec == 0) return false;
    if (sec >= totalSec) sec = totalSec - 1;

    uint32_t entry = ((uint64_t)sec * 1000ULL) / activeHeader.intervalMs;
    if (entry >= activeHeader.entryCount) entry = activeHeader.entryCount - 1;
    uint32_t offsetPos = sizeof(IndexHeader) + entry * sizeof(uint32_t);
    if (!indexFile.seek(offsetPos)) return false;

    uint32_t byteOffset = 0;
    if (indexFile.read((uint8_t *)&byteOffset, sizeof(byteOffset)) != sizeof(byteOffset)) return false;
    if (!videoFile.seek(byteOffset)) return false;

    decoder.begin(&videoFile, frameBuffer, FRAME_BUFFER_BYTES, jpegDrawThunk, true, width, height);
    currentFrame = (uint32_t)(((uint64_t)sec * activeHeader.fps100) / 100ULL);
    nextFrameUs = micros();

    if (drawImmediately && decoder.readFrame()) {
      if (!decoder.drawFrame()) return false;
      currentFrame++;
      drawOverlay();
      nextFrameUs = micros() + frameIntervalUs();
    }
    return true;
  }

  void seekRelative(int deltaSec) {
    int64_t target = (int64_t)currentSecond() + deltaSec;
    if (target < 0) target = 0;
    uint32_t totalSec = totalSeconds();
    if (totalSec > 0 && target >= (int64_t)totalSec) target = totalSec - 1;
    showOverlay();
    if (!seekToSecond((uint32_t)target, true)) {
      lastError = "Error al mover el video";
      finishPlayback(false);
    } else {
      saveProgress(true);
    }
  }

  void showOverlay() {
    overlayUntilMs = millis() + OVERLAY_MS;
    overlayWasVisible = true;
  }

  void drawPlaybackCurrent() {
    if (decoder.hasFrame()) decoder.redrawFrame();
    else gfx->fillScreen(C_BG());
    if ((int32_t)(overlayUntilMs - millis()) > 0) drawOverlay();
  }

  void drawOverlay() {
    uint32_t elapsed = currentSecond();
    uint32_t total = totalSeconds();
    if (elapsed > total) elapsed = total;
    uint32_t remaining = total > elapsed ? total - elapsed : 0;

    const int x = 8;
    const int y = height - 48;
    const int w = width - 16;
    const int h = 40;
    gfx->fillRoundRect(x, y, w, h, 8, C_OVERLAY());
    gfx->drawRoundRect(x, y, w, h, 8, C_GOLD());

    gfx->setTextSize(1);
    gfx->setTextColor(C_WHITE());
    gfx->setCursor(x + 9, y + 7);
    gfx->print(formatTime(elapsed));

    String rem = "-" + formatTime(remaining);
    int16_t remX = width / 2 - (int16_t)rem.length() * 3;
    gfx->setCursor(remX, y + 7);
    gfx->setTextColor(C_MUTED());
    gfx->print(rem);

    String tot = formatTime(total);
    int16_t totX = x + w - 9 - (int16_t)tot.length() * 6;
    gfx->setCursor(totX, y + 7);
    gfx->setTextColor(C_WHITE());
    gfx->print(tot);

    int barX = x + 10;
    int barY = y + 27;
    int barW = w - 20;
    gfx->fillRoundRect(barX, barY, barW, 5, 3, C_TRACK());
    int filled = total ? (int)(((uint64_t)barW * elapsed) / total) : 0;
    if (filled > 0) gfx->fillRoundRect(barX, barY, filled, 5, 3, C_GOLD());
    int knobX = barX + filled;
    knobX = min(barX + barW - 3, max(barX + 3, knobX));
    gfx->fillCircle(knobX, barY + 2, 3, C_WHITE());

    if (paused) {
      gfx->fillRect(width - 29, 8, 5, 15, C_GOLD());
      gfx->fillRect(width - 19, 8, 5, 15, C_GOLD());
    }
  }

  void drawLibrary() {
    gfx->fillScreen(C_BG());
    drawHeader("PELICULAS");

    if (!storageReady || fileCount == 0) {
      gfx->fillRoundRect(12, 39, width - 24, 91, 10, C_SURFACE());
      gfx->drawRoundRect(12, 39, width - 24, 91, 10, C_GOLD());
      centerText(!storageReady ? "MICROSD NO DISPONIBLE" : "SIN PELICULAS", 55, 2, C_WHITE());
      String msg = lastError.isEmpty() ? "Copia .mjpeg + .mji en /mjpeg" : lastError;
      centerText(ellipsizeLocal(msg, 46), 87, 1, C_MUTED());
      centerText("OK = REINTENTAR", 111, 1, C_GOLD());
      drawFooter("", "OK", "");
      return;
    }

    Item &it = items[selected];
    uint8_t prev = (selected + fileCount - 1) % fileCount;
    uint8_t next = (selected + 1) % fileCount;

    gfx->fillRoundRect(8, 43, 54, 78, 9, C_SURFACE());
    gfx->drawRoundRect(8, 43, 54, 78, 9, C_TRACK());
    drawPlayIcon(35, 75, C_MUTED());
    gfx->setTextSize(1);
    gfx->setTextColor(C_MUTED());
    gfx->setCursor(13, 102);
    gfx->print(ellipsizeLocal(cleanTitle(items[prev].name), 7));

    gfx->fillRoundRect(70, 35, 180, 100, 11, C_SURFACE2());
    gfx->drawRoundRect(70, 35, 180, 100, 11, C_GOLD());
    drawPlayIcon(93, 62, C_GOLD());
    gfx->setTextSize(2);
    gfx->setTextColor(C_WHITE());
    gfx->setCursor(114, 53);
    gfx->print(ellipsizeLocal(cleanTitle(it.name), 10));

    gfx->setTextSize(1);
    gfx->setTextColor(C_MUTED());
    gfx->setCursor(84, 83);
    if (it.indexValid) {
      gfx->print(formatTime(it.durationMs / 1000UL));
      gfx->print("  |  ");
      gfx->print(String(it.fps100 / 100.0f, 0));
      gfx->print(" FPS");
    } else {
      gfx->setTextColor(C_ERROR());
      gfx->print("Falta indice .mji");
    }

    uint32_t resume = loadProgress(it.path);
    gfx->setCursor(84, 101);
    if (resume >= 5 && it.indexValid) {
      gfx->setTextColor(C_GOLD());
      gfx->print("Continuar: ");
      gfx->print(formatTime(resume));
    } else {
      gfx->setTextColor(C_MUTED());
      gfx->print(String((it.size + 1024UL * 1024UL - 1) / (1024UL * 1024UL)) + " MB");
    }

    gfx->setTextColor(C_MUTED());
    gfx->setCursor(84, 118);
    gfx->print(String(selected + 1) + " / " + String(fileCount));

    gfx->fillRoundRect(258, 43, 54, 78, 9, C_SURFACE());
    gfx->drawRoundRect(258, 43, 54, 78, 9, C_TRACK());
    drawPlayIcon(285, 75, C_MUTED());
    gfx->setTextSize(1);
    gfx->setTextColor(C_MUTED());
    gfx->setCursor(263, 102);
    gfx->print(ellipsizeLocal(cleanTitle(items[next].name), 7));

    drawFooter("<", "OK ABRIR", ">");
  }

  void drawResumePrompt() {
    gfx->fillScreen(C_BG());
    drawHeader("CONTINUAR");
    gfx->fillRoundRect(20, 42, width - 40, 88, 12, C_SURFACE2());
    gfx->drawRoundRect(20, 42, width - 40, 88, 12, C_GOLD());
    centerText(ellipsizeLocal(cleanTitle(items[selected].name), 24), 54, 2, C_WHITE());
    centerText(String("Guardado en ") + formatTime(pendingResumeSec), 83, 1, C_MUTED());
    centerText("OK continua", 102, 1, C_GOLD());
    drawFooter("INICIO", "OK", "CANCELAR");
  }

  void drawHeader(const String &title) {
    gfx->fillRect(0, 0, width, 29, C_BG());
    gfx->fillRoundRect(10, 7, 5, 16, 3, C_GOLD());
    gfx->setTextSize(2);
    gfx->setTextColor(C_WHITE());
    gfx->setCursor(23, 7);
    gfx->print(ellipsizeLocal(title, 19));
    gfx->drawFastHLine(10, 28, width - 20, C_TRACK());
  }

  void drawFooter(const String &left, const String &center, const String &right) {
    gfx->fillRect(0, 149, width, 23, C_BG());
    gfx->drawFastHLine(8, 149, width - 16, C_TRACK());
    gfx->setTextSize(1);
    gfx->setTextColor(C_MUTED());
    gfx->setCursor(10, 158);
    gfx->print(left);
    gfx->setTextColor(C_GOLD());
    int16_t cx = (width - (int16_t)center.length() * 6) / 2;
    gfx->setCursor(cx, 158);
    gfx->print(center);
    gfx->setTextColor(C_MUTED());
    int16_t rx = width - 10 - (int16_t)right.length() * 6;
    gfx->setCursor(rx, 158);
    gfx->print(right);
  }

  void drawPlayIcon(int16_t cx, int16_t cy, uint16_t color) {
    gfx->drawRoundRect(cx - 15, cy - 13, 30, 26, 5, color);
    gfx->fillTriangle(cx - 3, cy - 7, cx - 3, cy + 7, cx + 9, cy, color);
  }

  void centerText(const String &text, int16_t y, uint8_t size, uint16_t color) {
    String safe = text;
    gfx->setTextSize(size);
    gfx->setTextColor(color);
    int16_t x = (width - (int16_t)safe.length() * 6 * size) / 2;
    if (x < 2) x = 2;
    gfx->setCursor(x, y);
    gfx->print(safe);
  }

  String cleanTitle(const String &name) const {
    String s = name;
    String lower = s;
    lower.toLowerCase();
    if (lower.endsWith(".mjpeg") && s.length() >= 6) s.remove(s.length() - 6);
    s.replace('_', ' ');
    return s;
  }

  String ellipsizeLocal(const String &text, uint16_t maxChars) const {
    if (text.length() <= maxChars) return text;
    if (maxChars <= 3) return text.substring(0, maxChars);
    return text.substring(0, maxChars - 3) + "...";
  }

  String baseName(const String &path) const {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  String formatTime(uint32_t seconds) const {
    uint32_t h = seconds / 3600UL;
    uint32_t m = (seconds % 3600UL) / 60UL;
    uint32_t s = seconds % 60UL;
    char buf[16];
    if (h > 0) snprintf(buf, sizeof(buf), "%lu:%02lu:%02lu", (unsigned long)h, (unsigned long)m, (unsigned long)s);
    else snprintf(buf, sizeof(buf), "%02lu:%02lu", (unsigned long)m, (unsigned long)s);
    return String(buf);
  }

  uint32_t pathHash(const String &path) const {
    uint32_t h = 2166136261UL;
    for (size_t i = 0; i < path.length(); i++) {
      h ^= (uint8_t)path[i];
      h *= 16777619UL;
    }
    return h;
  }

  String progressKey(const String &path) const {
    char key[12];
    snprintf(key, sizeof(key), "v%08lx", (unsigned long)pathHash(path));
    return String(key);
  }

  uint32_t loadProgress(const String &path) const {
    if (!prefs) return 0;
    String key = progressKey(path);
    return prefs->getULong(key.c_str(), 0);
  }

  void clearProgress(const String &path) {
    if (!prefs) return;
    String key = progressKey(path);
    prefs->putULong(key.c_str(), 0);
  }

  void saveProgress(bool force) {
    if (!prefs || mode != PLAYBACK || selected >= fileCount) return;
    uint32_t now = millis();
    if (!force && now - lastProgressSaveMs < 30000UL) return;
    uint32_t sec = currentSecond();
    uint32_t total = totalSeconds();
    String key = progressKey(items[selected].path);
    if (sec > 0 && total > 5 && sec + 5 < total) prefs->putULong(key.c_str(), sec);
    else if (sec + 5 >= total) prefs->putULong(key.c_str(), 0);
    lastProgressSaveMs = now;
  }

  // White/gold Small-Home video palette.
  static uint16_t C_BG()       { return RGB565(8, 8, 7); }
  static uint16_t C_SURFACE()  { return RGB565(22, 21, 18); }
  static uint16_t C_SURFACE2() { return RGB565(31, 29, 24); }
  static uint16_t C_WHITE()    { return RGB565(248, 247, 242); }
  static uint16_t C_MUTED()    { return RGB565(166, 160, 146); }
  static uint16_t C_GOLD()     { return RGB565(218, 180, 91); }
  static uint16_t C_TRACK()    { return RGB565(67, 61, 49); }
  static uint16_t C_OVERLAY()  { return RGB565(13, 13, 12); }
  static uint16_t C_ERROR()    { return RGB565(235, 112, 105); }
};

// Header is included only by the main Arduino sketch; this C++11-compatible
// definition avoids requiring inline static variables.
SmallHomeVideo *SmallHomeVideo::activeInstance = nullptr;

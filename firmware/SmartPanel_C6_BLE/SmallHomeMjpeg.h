#pragma once

#include <Arduino.h>
#include <FS.h>
#include <JPEGDEC.h>

// Lightweight MJPEG stream reader for Small-Home.
// The file is expected to be a concatenation of baseline JPEG frames.
// It intentionally keeps only one compressed frame in RAM at a time.
class SmallHomeMjpeg {
public:
  bool begin(Stream *input,
             uint8_t *frameBuffer,
             size_t frameBufferCapacity,
             JPEG_DRAW_CALLBACK *drawCallback,
             bool bigEndian,
             int widthLimit,
             int heightLimit) {
    _input = input;
    _frame = frameBuffer;
    _capacity = frameBufferCapacity;
    _drawCallback = drawCallback;
    _bigEndian = bigEndian;
    _widthLimit = widthLimit;
    _heightLimit = heightLimit;
    _readPos = 0;
    _readLen = 0;
    _frameLen = 0;
    _frameValid = false;
    _overflow = false;
    _scale = -1;
    return _input && _frame && _capacity > 4 && _drawCallback;
  }

  void resetStream() {
    _readPos = 0;
    _readLen = 0;
    _frameLen = 0;
    _frameValid = false;
    _overflow = false;
    _scale = -1;
  }

  bool readFrame() {
    _frameLen = 0;
    _frameValid = false;
    _overflow = false;

    int previous = -1;
    int current = -1;

    // Find JPEG SOI (FF D8).
    while ((current = nextByte()) >= 0) {
      if (previous == 0xFF && current == 0xD8) {
        if (_capacity < 2) return false;
        _frame[0] = 0xFF;
        _frame[1] = 0xD8;
        _frameLen = 2;
        previous = current;
        break;
      }
      previous = current;
    }

    if (_frameLen == 0) return false; // EOF before a new frame.

    // Copy through JPEG EOI (FF D9). If a frame is too large, consume it
    // safely to the end rather than corrupting memory.
    while ((current = nextByte()) >= 0) {
      if (_frameLen < _capacity) {
        _frame[_frameLen++] = (uint8_t)current;
      } else {
        _overflow = true;
      }

      if (previous == 0xFF && current == 0xD9) {
        if (_overflow) {
          _frameLen = 0;
          return false;
        }
        _frameValid = true;
        return true;
      }
      previous = current;
    }

    _frameLen = 0;
    return false;
  }

  bool drawFrame() {
    if (!_frameValid || _frameLen < 4) return false;

    if (!_jpeg.openRAM(_frame, (int)_frameLen, _drawCallback)) {
      return false;
    }

    int scale = _scale;
    int x = 0;
    int y = 0;

    if (scale < 0) {
      int w = _jpeg.getWidth();
      int h = _jpeg.getHeight();
      float ratioW = _widthLimit > 0 ? (float)w / _widthLimit : 1.0f;
      float ratioH = _heightLimit > 0 ? (float)h / _heightLimit : 1.0f;
      float ratio = max(ratioW, ratioH);
      int iMaxMCUs = max(1, _widthLimit / 16);

      if (ratio <= 1.0f) {
        scale = 0;
        iMaxMCUs = max(1, _widthLimit / 16);
      } else if (ratio <= 2.0f) {
        scale = JPEG_SCALE_HALF;
        w /= 2;
        h /= 2;
        iMaxMCUs = max(1, _widthLimit / 8);
      } else if (ratio <= 4.0f) {
        scale = JPEG_SCALE_QUARTER;
        w /= 4;
        h /= 4;
        iMaxMCUs = max(1, _widthLimit / 4);
      } else {
        scale = JPEG_SCALE_EIGHTH;
        w /= 8;
        h /= 8;
        iMaxMCUs = max(1, _widthLimit / 2);
      }

      _jpeg.setMaxOutputSize(iMaxMCUs);
      x = max(0, (_widthLimit - w) / 2);
      y = max(0, (_heightLimit - h) / 2);
      _scale = scale;
      _drawX = x;
      _drawY = y;
    } else {
      x = _drawX;
      y = _drawY;
    }

    if (_bigEndian) _jpeg.setPixelType(RGB565_BIG_ENDIAN);
    int result = _jpeg.decode(x, y, scale);
    _jpeg.close();
    return result != 0;
  }

  bool redrawFrame() {
    return drawFrame();
  }

  bool overflowed() const { return _overflow; }
  bool hasFrame() const { return _frameValid; }
  size_t frameSize() const { return _frameLen; }

private:
  static const size_t READ_BUFFER_SIZE = 2048;

  int nextByte() {
    if (!_input) return -1;
    if (_readPos >= _readLen) {
      _readLen = _input->readBytes(_readBuffer, READ_BUFFER_SIZE);
      _readPos = 0;
      if (_readLen == 0) return -1;
    }
    return _readBuffer[_readPos++];
  }

  Stream *_input = nullptr;
  uint8_t *_frame = nullptr;
  size_t _capacity = 0;
  size_t _frameLen = 0;
  JPEG_DRAW_CALLBACK *_drawCallback = nullptr;
  bool _bigEndian = true;
  int _widthLimit = 0;
  int _heightLimit = 0;

  uint8_t _readBuffer[READ_BUFFER_SIZE];
  size_t _readPos = 0;
  size_t _readLen = 0;

  JPEGDEC _jpeg;
  int _scale = -1;
  int _drawX = 0;
  int _drawY = 0;
  bool _frameValid = false;
  bool _overflow = false;
};

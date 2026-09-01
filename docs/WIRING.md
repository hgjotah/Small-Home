# Wiring

Target board: Waveshare ESP32-C6-LCD-1.47 **NO TOUCH**.

The TFT and backlight are integrated on the board; do not add external TFT wiring.

Wire each external momentary button between its GPIO and GND. The firmware uses
`INPUT_PULLUP`, so no external pull-up resistor is required.

| Control | GPIO | Connection |
|---|---:|---|
| LEFT | GPIO18 | GPIO18 → button → GND |
| CENTER | GPIO19 | GPIO19 → button → GND |
| RIGHT | GPIO20 | GPIO20 → button → GND |

Power the board through USB-C. Disconnect power before changing wiring and check
for shorts before reconnecting.


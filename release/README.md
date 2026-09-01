# Small Home build artifacts

## Android

Install `Small-Home-debug.apk` on Android. It is a debug-signed build of
application ID `com.ia.smallhome`, version `1.0`.

## ESP32-C6 (reflash required)

The recommended update method is Arduino IDE:

1. Open `../firmware/SmartPanel_C6_BLE/SmartPanel_C6_BLE.ino`.
2. Select **ESP32C6 Dev Module**.
3. Select **Huge APP (3MB No OTA/1MB SPIFFS)** as Partition Scheme.
4. Select the board's serial port and press Upload.

This method normally preserves NVS configuration and the BLE passkey.

The precompiled images can instead be written with esptool. Replace `COMx` with
the actual serial port:

```text
python -m esptool --chip esp32c6 --port COMx --baud 460800 write-flash 0x0 SmallHome-ESP32C6-bootloader.bin 0x8000 SmallHome-ESP32C6-partitions.bin 0xe000 SmallHome-ESP32C6-boot_app0.bin 0x10000 SmallHome-ESP32C6-app.bin
```

That four-image command leaves the NVS partition at `0x9000` untouched. The
`SmallHome-ESP32C6-merged.bin` image is provided only for a clean recovery flash
at address `0x0`; because it spans the complete 4 MB flash, using it can erase
saved Wi-Fi/Home Assistant configuration, the panel passkey and BLE bond data.

If Android and the panel have stale or mismatched BLE bond keys after flashing,
forget `SmartPanel-C6-XXXX` once in Android Bluetooth settings and pair again
using the six-digit PIN shown by the panel.

## SHA-256

```text
60624fcae03a89ae9f888ddd70f342dcc2704c33eafc9771d019dba5d91d02b7  Small-Home-debug.apk
d975f1e0954ab5693492393f38fa17e2eeea574650a82dec1c6628a919bdda50  SmallHome-ESP32C6-app.bin
f94c5d786a7a8fab06ac5d10e33bf37711a6697636dc037559ea19cc410a17f0  SmallHome-ESP32C6-boot_app0.bin
02f53243e1ded001d79f109e938846d72bd85a91bee7a3513e4094d7c090975c  SmallHome-ESP32C6-bootloader.bin
989c0dab48c0d0ea902a1ab30ebd131d3453fb00c243d9f03a6c71e868a59a54  SmallHome-ESP32C6-merged.bin
aaae2888c5a6a348004b5b436f47abb25ae32e72d9003902955a998eda723edd  SmallHome-ESP32C6-partitions.bin
```

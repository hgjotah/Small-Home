# Small Home — SmartPanel C6

Small Home is the Android companion for SmartPanel C6: a compact physical panel
that puts Home Assistant lights, a thermostat, selected phone notifications,
CoinMarketCap, and OpenRouter-powered answers on a 1.47-inch display.

The name is a play on “Smart Home” and a literal description: useful parts of a
home fit into a very small device.

## Screenshots

The launcher artwork is available in `branding/`. Add real app screenshots under
`docs/images/` as `app-home.png`, `ble-pairing.png`, and `home-assistant.png` when
publishing store or release documentation.

## Hardware

- Waveshare ESP32-C6-LCD-1.47, **non-touch** version;
- integrated 172x320 ST7789 TFT;
- three external momentary buttons;
- USB-C power;
- 2.4 GHz Wi-Fi.

See [docs/WIRING.md](docs/WIRING.md) for the button wiring.

## Architecture

```text
Android Small Home <-- bonded BLE protocol v2 --> ESP32-C6
       |                                           |
       +--> OpenRouter                             +--> Wi-Fi: NTP
       +--> notification listener                  +--> Wi-Fi: Home Assistant
       +--> optional HA entity discovery           +--> Wi-Fi: CoinMarketCap
```

Android never requires the phone and panel to share a Wi-Fi network. BLE is the
only Android-to-SmartPanel transport. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/BLE_PROTOCOL_v2.md](docs/BLE_PROTOCOL_v2.md).

## Features

- secure BLE pairing with bonding, MITM, Secure Connections, and a display PIN;
- resilient newline-delimited JSON framing in 18-byte BLE chunks;
- foreground BLE connection, heartbeat, and bounded reconnect backoff;
- Wi-Fi provisioning sent to the ESP over encrypted BLE;
- the complete Home Assistant entity catalog, with manual assignment of up to
  ten entities to the light role and one to the thermostat role;
- Home Assistant connectivity test executed by the ESP itself;
- ten most recent selected Android notifications with mark-as-read/dismiss;
- OpenRouter sessions kept only in Android memory;
- manual CoinMarketCap refresh from physical LEFT+CENTER only;
- configurable safe TFT brightness;
- local Flappy Bird game and high score on the firmware.

## Firmware requirements

- Arduino IDE or Arduino CLI;
- `esp32 by Espressif Systems`, core 3.x;
- board: `ESP32C6 Dev Module`;
- `GFX Library for Arduino` (`Arduino_GFX`);
- `ArduinoJson` 7.x.

## Flashing the firmware

1. Open `firmware/SmartPanel_C6_BLE/SmartPanel_C6_BLE.ino`.
2. Select `ESP32C6 Dev Module` and the correct USB serial port.
3. In **Tools > Partition Scheme**, select **Huge APP (3MB No OTA/1MB SPIFFS)**.
   The default 1.2MB application partition is too small for the BLE, TLS and TFT
   features used by this firmware.
4. Install the two external libraries listed above.
5. Compile and upload.
6. Reboot the board. The TFT shows `SmartPanel-C6-XXXX` and its six-digit BLE
   pairing PIN when setup is required.

Equivalent Arduino CLI target: `esp32:esp32:esp32c6:PartitionScheme=huge_app`.

## Building Android

Requirements: JDK 17, Android SDK 36, and a recent Android Studio.

```text
gradlew.bat testDebugUnitTest
gradlew.bat lintDebug
gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Pairing

1. Open **Añadir panel** and grant Nearby Devices/Bluetooth permissions.
2. Search for SmartPanel. Discovery is filtered by the protocol service UUID,
   not only by the advertised name.
3. Select the device shown on the TFT.
4. Enter the six-digit PIN from the physical display in Android's system pairing
   dialog.
5. Android waits for bonding, connects GATT, subscribes to TX, sends `hello`, and
   validates protocol 2 plus the real `chip_id`.

If a replacement phone needs the PIN, press LEFT+RIGHT while the panel is locked.
If an existing Android bond is broken, forget the device in Android Bluetooth
settings and pair again.

## Configuring panel Wi-Fi

After BLE is connected, enter the 2.4 GHz SSID and password in **Añadir panel**.
The password is sent once through authenticated BLE and is not saved by Android.
Panel Wi-Fi and Android BLE are independent states: failed Wi-Fi does not break
the BLE link.

## Home Assistant

Enter the Home Assistant base URL and a Long-Lived Access Token. Android calls
`GET /api/states` and displays every valid entity, regardless of domain. You then
assign up to ten entities to the light role and one to the thermostat role, with
an optional name used only on SmartPanel. A light-role entity must provide
`turn_on`/`turn_off`; the thermostat role must provide a target `temperature`
and `set_temperature`. After saving, Android waits for `config_saved` and asks
the ESP to call `/api/` itself. Local HTTP is supported for local installations.
Flash the bundled firmware after updating the app: Android rejects older protocol-2
firmware that does not advertise manual entity roles, preventing unsupported
domains from being silently discarded.

## CoinMarketCap

Enter a CoinMarketCap API key and select EUR or USD. XDAG is preconfigured with
CoinMarketCap UCID `4424`; the search remains available to choose another asset.
The key and selection are sent to the ESP. There is no Android polling: the
quote is updated only when the user presses LEFT+CENTER on the locked home screen.

## OpenRouter

Enter an OpenRouter API key and model ID in Android. The key is encrypted with
Android Keystore and is never sent to the ESP. The firmware sends prompts over
BLE; Android maintains about ten recent messages in memory and returns a concise
answer to the TFT. Leaving the AI screen erases the session.

## Notifications

Grant Android's Notification Access and select applications individually. Small
Home forwards only text metadata and keeps at most ten active items. On reconnect
it clears the panel list and sends the newest ten oldest-first so firmware's
front insertion produces newest-first display order. Images and historical
notification archives are not stored.

## Physical controls

- unlock: CENTER, LEFT, RIGHT;
- back: LEFT+RIGHT while unlocked;
- show pairing PIN: LEFT+RIGHT while locked;
- refresh CoinMarketCap: LEFT+CENTER on locked home;
- lock and turn off backlight: hold CENTER+RIGHT for 1.2 seconds;
- wake: any button; the wake press is discarded;
- auto-lock: 60 seconds, without turning off the display;
- Flappy Bird: CENTER flaps, RIGHT restarts after game over, LEFT+RIGHT exits.

## Security and privacy

**Never commit or upload:** Home Assistant tokens, OpenRouter keys, CoinMarketCap
keys, Wi-Fi passwords, release signing keystores, or keystore passwords. Examples
must use `YOUR_TOKEN`, `YOUR_API_KEY`, and `YOUR_WIFI_PASSWORD`.

Retained Android secrets use Android Keystore plus AES/GCM. Wi-Fi passwords, AI
history, and historical notification content are not persisted. See
[SECURITY.md](SECURITY.md).

## Troubleshooting

- **No device found:** enable Bluetooth, grant scan/connect permissions, keep the
  panel nearby, and verify the v2 firmware is running.
- **PIN dialog fails:** use the PIN currently shown on the TFT; forget a stale
  Android bond before retrying.
- **BLE connects but Wi-Fi fails:** verify 2.4 GHz SSID/password. BLE remains usable.
- **Home Assistant discovery works but ESP test fails:** the ESP Wi-Fi cannot
  reach the URL, the token is invalid, or the URL is not routable from the ESP.
- **No notifications:** enable Notification Access and select the source app.
- **OpenRouter errors:** check the key, account credit, rate limits, and exact
  model ID.

## Repository structure

- `app/` — Kotlin/Compose Android application;
- `firmware/SmartPanel_C6_BLE/` — ESP32-C6 firmware;
- `docs/` — wire protocol, architecture, and wiring;
- `branding/` — Small Home logo masters;
- `.github/workflows/android.yml` — Android continuous integration.

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md). The project is licensed under the
[MIT License](LICENSE).

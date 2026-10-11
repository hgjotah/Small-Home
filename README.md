# Small Home — SmartPanel C6

Small Home is the Android companion for SmartPanel C6: a compact physical panel
that puts Home Assistant lights, a thermostat, selected phone notifications,
CoinMarketCap, OpenRouter-powered answers, local notes, games, and offline movie
playback on a 1.47-inch display.

## Hardware

- Waveshare ESP32-C6-LCD-1.47, **non-touch** version;
- integrated 172x320 ST7789 TFT;
- onboard microSD / TF slot (FAT32), used for movies;
- three external momentary buttons;
- USB-C power;
- 2.4 GHz Wi-Fi.

See [docs/WIRING.md](docs/WIRING.md) for the button wiring. The microSD slot is
part of the board and needs no extra wiring: it shares the LCD's SCLK and MOSI
lines (chip select GPIO4, MISO GPIO5).

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
- up to eight persistent local notes, written and read entirely on the ESP32-C6
  without the phone, BLE, Home Assistant, or Wi-Fi;
- local Flappy Bird game and high score on the firmware;
- offline movie player (**Peliculas**): MJPEG playback from the onboard microSD,
  10-second seek, pause/resume, a time overlay, and a resume position kept in NVS;
- `video_to_mjpeg.py`, a desktop converter that turns any video into the MJPEG
  and index files the player expects.

## Firmware requirements

- Arduino IDE or Arduino CLI;
- `esp32 by Espressif Systems`, core 3.x;
- board: `ESP32C6 Dev Module`;
- `GFX Library for Arduino` (`Arduino_GFX`);
- `ArduinoJson` 7.x;
- `JPEGDEC` 1.8.x (movie playback).

`SmallHomeVideo.h` (the movie player) must stay in the sketch folder next to
`SmartPanel_C6_BLE.ino`.

## Flashing the firmware

1. Open `firmware/SmartPanel_C6_BLE/SmartPanel_C6_BLE.ino`.
2. Select `ESP32C6 Dev Module` and the correct USB serial port.
3. In **Tools > Partition Scheme**, select **Huge APP (3MB No OTA/1MB SPIFFS)**.
   The default 1.2MB application partition is too small for the BLE, TLS and TFT
   features used by this firmware.
4. Install the three external libraries listed above.
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

If a replacement phone needs the PIN, begin pairing from Android; the panel
displays the PIN automatically when it receives the secure pairing request.
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
- refresh CoinMarketCap: LEFT+CENTER on locked home;
- lock and turn off backlight from any screen: press LEFT+CENTER+RIGHT together;
- wake: any button; the wake press is discarded;
- auto-lock: 60 seconds, without turning off the display;
- Flappy Bird: CENTER flaps, RIGHT restarts after game over, LEFT+RIGHT exits;
- Peliculas: LEFT/RIGHT seek 10 seconds, CENTER pauses/resumes, LEFT+RIGHT exits.

The locked home screen intentionally does not display the unlock sequence.

## Local notes

Notes are stored only in the ESP32-C6 NVS and do not depend on Android, BLE,
Home Assistant, or Wi-Fi. The firmware stores up to eight notes, with a title of
up to 36 characters and content of up to 900 characters.

- enter **Notas** from the panel menu;
- LEFT/RIGHT selects a note and CENTER opens it;
- CENTER+RIGHT in the notes list creates a note and starts in the title field;
- LEFT+CENTER in the notes list deletes the selected note immediately;
- in the editor, LEFT/RIGHT selects a keyboard key and CENTER types it;
- CENTER+LEFT alternates between title and content;
- the `NL` keyboard key inserts a new line while editing content;
- LEFT+RIGHT saves the note and opens the complete reading view;
- in reading view, LEFT scrolls up and RIGHT scrolls down;
- CENTER+LEFT edits the open note;
- LEFT+RIGHT returns from the reading view to the notes list.

## Movies

The **Peliculas** menu entry plays video from the onboard microSD card without the
phone, BLE, Home Assistant, or Wi-Fi. Playback is MJPEG with no audio, decoded on
the ESP32-C6 and drawn at 320x172.

### Preparing a movie

Use `video_to_mjpeg.py` on your computer. It needs Python 3 and
[FFmpeg](https://ffmpeg.org/) in `PATH`.

```text
python video_to_mjpeg.py                 # opens a file picker
python video_to_mjpeg.py movie.mkv       # converts that file directly
python video_to_mjpeg.py movie.mkv --name film --fps 15 --quality 12
```

The converter writes two files with the same base name:

- `<name>.mjpeg` — the baseline JPEG frames, concatenated;
- `<name>.mji` — the Small-Home Video Index v1: exact duration plus one offset per
  second, used for resuming and for the +/-10 s seek.

Copy **both** files to the `/mjpeg` folder of a FAT32 microSD card and insert it in
the board before opening **Peliculas**.

| Option | Default | Notes |
|---|---:|---|
| `--fps` | 15 | 10 to 24. Lower values give smaller files and smoother decoding. |
| `--quality` | 12 | FFmpeg JPEG quality, 2 to 31. A higher number means a smaller file. |
| `--width` / `--height` | 320 / 172 | Must be even; match the panel. Do not change unless the firmware changes. |
| `--name` | video name | ASCII only. Keep it to 8 characters if your FAT setup lacks long names. |
| `--output-dir` | next to the video | Output folder. |
| `--overwrite` | off | Overwrite existing outputs without asking. |

FAT32 limits one file to 4 GiB, so the converter refuses larger outputs. Lower
`--fps` or raise `--quality` for long movies. The `.mji` layout is shared by the
script and the firmware; change neither without the other.

### Playback

- LEFT / RIGHT seek 10 seconds back / forward;
- CENTER pauses and resumes;
- LEFT+RIGHT leaves the player;
- a time overlay appears for 3 seconds after any control is used;
- the resume position is stored in the ESP32-C6 NVS.

While a movie is actually playing, the firmware stops servicing BLE messages and
Wi-Fi/Home Assistant maintenance and disables auto-lock, so decoding is not
interrupted. The three-button lock combination still works. Pause or leave the
player to let the panel catch up with the phone.

## Security and privacy

**Never commit or upload:** Home Assistant tokens, OpenRouter keys, CoinMarketCap
keys, Wi-Fi passwords, release signing keystores, or keystore passwords. Examples
must use `YOUR_TOKEN`, `YOUR_API_KEY`, and `YOUR_WIFI_PASSWORD`.

Retained Android secrets use Android Keystore plus AES/GCM. Wi-Fi passwords, AI
history, and historical notification content are not persisted. Local note text
is persisted unencrypted in the ESP32-C6 NVS and never sent to Android or Wi-Fi. See
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
- **Firmware does not compile (`SmallHomeVideo.h` not found):** the movie player
  header must be in the same folder as the `.ino`; also install `JPEGDEC`.
- **Peliculas shows no movie or the card fails:** use a FAT32 card, put both the
  `.mjpeg` and `.mji` files in `/mjpeg`, and keep ASCII file names (short ones if
  needed). Re-run the converter if the index is missing.
- **Movie stutters:** convert again with a lower `--fps` or a higher `--quality`
  number.
- **Phone data is late after a movie:** BLE and Wi-Fi are paused during playback;
  pause or exit the player.

## Repository structure

- `app/` — Kotlin/Compose Android application;
- `firmware/SmartPanel_C6_BLE/` — ESP32-C6 firmware;
- `video_to_mjpeg.py` — video to MJPEG + index converter for the movie player;
- `docs/` — wire protocol, architecture, and wiring;
- `branding/` — Small Home logo masters;
- `Small Home 3D Case.stl` — 3D-printable case model;
- `release/` — flashing instructions and checksums for the prebuilt binaries;
- `.github/workflows/android.yml` — Android continuous integration.

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md). The project is licensed under the
[MIT License](LICENSE).

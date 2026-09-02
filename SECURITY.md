# Security policy

## Reporting a vulnerability

Do not publish exploitable details in a public issue. If GitHub private
vulnerability reporting is enabled, open a private security advisory. Otherwise,
contact the repository owner privately and include the affected version, impact,
reproduction steps, and a proposed mitigation when possible.

Never attach real credentials to a report. In particular, do not include:

- Home Assistant Long-Lived Access Tokens;
- OpenRouter or CoinMarketCap API keys;
- Wi-Fi passwords;
- Android signing keystores or their passwords;
- notification content containing personal information.

Replace credentials with `YOUR_TOKEN`, `YOUR_API_KEY`, or
`YOUR_WIFI_PASSWORD`. Revoke any credential that was accidentally exposed.

## Security design

Android and SmartPanel communicate through bonded BLE with MITM protection,
Secure Connections, and the six-digit passkey shown on the physical display.
Android stores retained API credentials with an AES/GCM key held by Android
Keystore. Wi-Fi passwords, AI conversation history, and notification history are
not persisted by the Android app.

Local notes are intentionally stored unencrypted in the ESP32-C6 NVS so they
remain available without a phone or network connection. They are never included
in the BLE protocol and are never transmitted over Wi-Fi. Anyone with physical
access to the device or its flash may be able to recover their contents; do not
store passwords or other high-value secrets in notes.

The firmware currently accepts self-signed HTTPS endpoints by using an insecure
TLS verifier. See the firmware header and install a trusted CA for hardened
deployments.

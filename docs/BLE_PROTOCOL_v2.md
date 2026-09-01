# SmartPanel C6 BLE protocol v2

This document is the wire contract between the Android app and the Waveshare
ESP32-C6-LCD-1.47 firmware.

## Transport

- BLE GATT service: `98d56a10-7c6d-4f5d-9af0-5a6b26aa1000`
- RX characteristic (Android -> ESP): `98d56a10-7c6d-4f5d-9af0-5a6b26aa1001`
- TX characteristic (ESP -> Android notifications): `98d56a10-7c6d-4f5d-9af0-5a6b26aa1002`
- Protocol version: `2`
- UTF-8 JSON messages.
- Every logical message ends with `\n`.
- The firmware transmits in 18-byte chunks so it also works with the minimum ATT MTU.
- Android should reconstruct a byte/string stream and split on newline before parsing JSON.
- Android deliberately keeps the negotiated/default MTU during setup. Requesting an
  MTU is unnecessary for 18-byte chunks and would add another asynchronous GATT
  operation to an already timing-sensitive discovery/subscription sequence.
- Android writes should also be chunked. 18 bytes is the safest universal value.

## Connection lifecycle

1. Android bonds first and lets the system show the six-digit PIN dialog.
2. A single GATT session is opened for the selected MAC address.
3. After the link reports connected, Android waits briefly before discovering services.
4. Android enables TX notifications and waits for the CCCD write callback.
5. Only then does it send `hello` and accept the link as connected after `hello_ack`.
6. A heartbeat is sent every 15 seconds. Forty-five seconds without an acknowledgement
   is treated as a dead link and triggers a clean reconnect.

Connection, discovery and subscription have independent timeouts. Recovery uses
bounded direct reconnect attempts, then scans for the saved panel again. The firmware
queues outgoing lines until TX notifications are subscribed and relies only on
`advertiseOnDisconnect(true)` to resume advertising, avoiding duplicate restart races.

## Security

The GATT RX/TX characteristics require authenticated BLE access. The ESP32-C6
uses bonding + MITM + Secure Connections and a six-digit passkey shown on the LCD.
The passkey is generated once and stored in NVS.

## Android -> ESP

### hello
```json
{"type":"hello","protocol":2,"app_version":"1.0.0"}
```

### heartbeat
```json
{"type":"heartbeat"}
```

### time_sync
UNIX UTC seconds. Used as fallback if NTP has not produced a valid time.
```json
{"type":"time_sync","epoch":1788123456}
```

### wifi_config
```json
{"type":"wifi_config","ssid":"MyWifi","password":"secret"}
```

### config_get
```json
{"type":"config_get"}
```

### config_set
Sensitive fields are omitted when unchanged. Do not send an empty token/key unless the user intentionally wants to clear it.
```json
{
  "type":"config_set",
  "device_name":"SmartPanel C6",
  "ha_base_url":"http://192.168.1.50:8123",
  "ha_token":"LONG_LIVED_TOKEN",
  "cmc_api_key":"CMC_KEY",
  "cmc_id":4424,
  "cmc_symbol":"XDAG",
  "fiat":"EUR",
  "timezone":"CET-1CEST,M3.5.0,M10.5.0/3",
  "brightness":110,
  "climate_id":"climate.salon",
  "climate_name":"Termostato",
  "lights":[
    {"id":"light.salon","name":"Salon"},
    {"id":"switch.lampara","name":"Habitacion"}
  ]
}
```

Android discovers the complete Home Assistant entity catalog. `lights` and
`climate_id` describe the role chosen by the user, not a mandatory entity-ID
domain. The firmware derives the real service domain from each `entity_id`.
An entity assigned as a light must support `turn_on`/`turn_off`; the thermostat
entity must expose a target `temperature` attribute and `set_temperature`.

### ha_test
Tests Home Assistant FROM THE ESP itself.
```json
{"type":"ha_test"}
```

### ha_entity_test
```json
{"type":"ha_entity_test","entity_id":"light.salon"}
```

### notification_clear
Send before a full active-notification resync.
```json
{"type":"notification_clear"}
```

### notification_add
```json
{
  "type":"notification_add",
  "key":"ANDROID_STATUS_BAR_NOTIFICATION_KEY",
  "app":"WhatsApp",
  "title":"Carlos",
  "text":"Vienes esta tarde?",
  "time":"18:37"
}
```

### notification_remove
```json
{"type":"notification_remove","key":"ANDROID_STATUS_BAR_NOTIFICATION_KEY"}
```

### ai_response
```json
{
  "type":"ai_response",
  "session_id":"...",
  "request_id":17,
  "text":"Respuesta...",
  "error":""
}
```

Error form:
```json
{
  "type":"ai_response",
  "session_id":"...",
  "request_id":17,
  "text":"",
  "error":"Descripcion del error"
}
```

### status_request
```json
{"type":"status_request"}
```

### factory_reset
Erases SmartPanel config in its own NVS namespace but does not intentionally erase BLE bond storage.
```json
{"type":"factory_reset","confirm":"ERASE"}
```

## ESP -> Android

### hello_ack
```json
{
  "type":"hello_ack",
  "protocol":2,
  "chip_id":"...",
  "device_name":"SmartPanel C6",
  "board":"Waveshare ESP32-C6-LCD-1.47",
  "wifi_connected":true,
  "manual_entity_roles":true
}
```

`manual_entity_roles:true` identifies firmware that accepts any valid Home
Assistant `entity_id` in the role selected by the user. Android must ask the
user to flash the bundled firmware if this capability is absent or false;
otherwise older firmware could silently discard non-`light.*` IDs.

### heartbeat_ack
```json
{"type":"heartbeat_ack","uptime_ms":123456,"wifi_connected":true}
```

### status
```json
{
  "type":"status",
  "protocol":2,
  "chip_id":"...",
  "device_name":"SmartPanel C6",
  "wifi_connected":true,
  "wifi_rssi":-54,
  "ble_connected":true,
  "notification_count":3,
  "unlocked":false,
  "screen":0,
  "has_ha":true,
  "has_cmc":true,
  "brightness":110,
  "flappy_high_score":12
}
```

### wifi_result
Success:
```json
{"type":"wifi_result","ok":true,"ip":"192.168.1.40","rssi":-55}
```

Failure:
```json
{"type":"wifi_result","ok":false,"error":"wifi_connection_failed"}
```

### config_state
Does not return secrets.
```json
{
  "type":"config_state",
  "protocol":2,
  "device_name":"SmartPanel C6",
  "chip_id":"...",
  "wifi_ssid":"MyWifi",
  "wifi_configured":true,
  "ha_base_url":"http://192.168.1.50:8123",
  "has_ha_token":true,
  "cmc_id":4424,
  "cmc_symbol":"XDAG",
  "fiat":"EUR",
  "has_cmc_key":true,
  "timezone":"CET-1CEST,M3.5.0,M10.5.0/3",
  "brightness":110,
  "climate_id":"climate.salon",
  "climate_name":"Termostato",
  "lights":[{"id":"light.salon","name":"Salon"}]
}
```

### config_saved
```json
{"type":"config_saved","ok":true}
```

### ha_test_result
```json
{"type":"ha_test_result","ok":true,"message":"API running."}
```

### ha_entity_test_result
```json
{
  "type":"ha_entity_test_result",
  "entity_id":"light.salon",
  "ok":true,
  "state":"on",
  "error":""
}
```

### notification_dismiss
Android should try MARK_AS_READ semantic action first when available, then call `cancelNotification(key)`.
```json
{"type":"notification_dismiss","key":"ANDROID_STATUS_BAR_NOTIFICATION_KEY"}
```

### ai_session_start
```json
{"type":"ai_session_start","session_id":"..."}
```

### ai_request
```json
{
  "type":"ai_request",
  "session_id":"...",
  "request_id":17,
  "prompt":"Quien fue Alan Turing?"
}
```

### ai_session_end
```json
{"type":"ai_session_end","session_id":"..."}
```

### factory_reset_ack
```json
{"type":"factory_reset_ack","ok":true}
```

### error
```json
{"type":"error","code":"invalid_json"}
```

# Architecture

## System boundaries

```mermaid
flowchart LR
    UI[Jetpack Compose UI] --> VM[SmallHomeViewModel]
    VM --> CM[PanelConnectionManager]
    CM --> BLE[SmartPanelBleManager]
    BLE <-->|Bonded GATT / protocol v2| ESP[Waveshare ESP32-C6]
    NL[NotificationListenerService] --> NG[NotificationGateway] --> CM
    ESP -->|Wi-Fi| HA[Home Assistant REST]
    ESP -->|Wi-Fi / manual only| CMC[CoinMarketCap]
    ESP -->|AI requests over BLE| CM --> AI[AiSessionManager]
    AI --> OR[OpenRouter HTTPS]
    VM --> HAD[Optional HA entity discovery]
    HAD --> HA
    DS[DataStore: non-secrets] --> VM
    KS[Android Keystore + AES/GCM] --> VM
```

## Android responsibilities

- request Bluetooth permissions and initiate system bonding;
- scan by the SmartPanel service UUID and present candidates;
- maintain GATT, TX notifications, an ordered RX write queue, heartbeat, and
  reconnect backoff;
- frame compact UTF-8 JSON with newline terminators and 18-byte chunks;
- validate protocol 2 and `chip_id` before accepting a panel;
- persist only non-secret device metadata in DataStore;
- encrypt retained HA, CMC, and OpenRouter credentials;
- capture selected notifications and route dismiss requests;
- keep OpenRouter session context only in memory;
- query Home Assistant for the full entity catalog and persist the user's explicit panel
  role assignment independently of the entity's native domain.

## ESP32-C6 responsibilities

- display and secure the BLE passkey, serve the authenticated GATT service, and
  persist panel configuration in NVS;
- use Wi-Fi directly for NTP, Home Assistant, and manual CoinMarketCap refresh;
- own light/thermostat roles, derive each selected entity's real HA service
  domain, and own lock/button behavior, brightness, and Flappy Bird;
- request notification dismissal and AI work from Android over BLE.

## Connection sequence

```mermaid
sequenceDiagram
    participant U as User
    participant A as Android
    participant E as ESP32-C6
    U->>A: Search and select panel
    A->>E: System bonding / six-digit PIN
    A->>E: Connect GATT, discover, subscribe TX
    A->>E: hello protocol=2
    E-->>A: hello_ack + chip_id
    A->>A: Validate protocol and saved identity
    A->>E: time_sync, status_request, config_get
    A->>E: notification_clear + active notifications
    loop about every 15 seconds
        A->>E: heartbeat
        E-->>A: heartbeat_ack
    end
```

The phone and panel do not need to be on the same Wi-Fi. Cleartext local-network
access in Android exists only for optional Home Assistant entity discovery.

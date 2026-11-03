# JARVIS Device API examples

All values are placeholders. Never commit actual credentials.

## 1. Create the JARVIS account once

The bootstrap secret is used only to create an account. Do not embed it in a public APK.

```http
POST /v1/accounts/create
Authorization: Bearer <DEVICE_ENROLLMENT_SECRET>
Content-Type: application/json

{}
```

The response contains an `accountId` and a long-lived `accountToken`. Store the account token in a secure password manager. Use it only to enroll devices and list devices owned by the account.

## 2. Enroll a device into the account

```http
POST /v1/devices/enroll
Authorization: Bearer <ACCOUNT_TOKEN>
Content-Type: application/json

{
  "deviceId": "android-rog6-test-0001",
  "platform": "android",
  "name": "ROG Phone 6",
  "osVersion": "Android 14",
  "appVersion": "1.0.0",
  "capabilities": ["files.read", "apps.list", "apps.launch", "system.read"]
}
```

Store the returned device-scoped token in Android Keystore or the platform's secure credential store. Do not reuse a device token on another device.

## 3. List devices in the account

```http
GET /v1/devices
Authorization: Bearer <ACCOUNT_TOKEN>
```

## 4. Queue a command

```http
POST /v1/device/command
Authorization: Bearer <ACCOUNT_TOKEN>
Content-Type: application/json

{
  "deviceId": "android-rog6-test-0001",
  "action": "files.search",
  "params": { "query": "laporan" }
}
```

The account token may queue commands only for devices owned by that account. The device connector polls its own queue using its device token:

```http
GET /v1/device/commands?deviceId=android-rog6-test-0001
Authorization: Bearer <DEVICE_TOKEN>
```

After local execution, the connector acknowledges the command:

```http
POST /v1/device/commands/ack
Authorization: Bearer <DEVICE_TOKEN>
Content-Type: application/json

{
  "deviceId": "android-rog6-test-0001",
  "commandId": "<COMMAND_ID>",
  "result": { "ok": true }
}
```

A queued command is not proof of execution. The device connector must enforce local OS permissions and user consent.

## 5. Enroll a desktop device

The Windows, macOS and Linux connector pair commands call this route with the account token. The platform name must match the connector.

Example Windows enrollment:

```http
POST /v1/devices/enroll
Authorization: Bearer <ACCOUNT_TOKEN>
Content-Type: application/json

{
  "deviceId": "windows-<UUID>",
  "platform": "windows",
  "name": "DESKTOP-01",
  "osVersion": "<Windows version>",
  "appVersion": "1.0.0",
  "capabilities": ["apps.list", "apps.launch", "system.read", "files.read"]
}
```

macOS uses "platform": "macos" and Linux uses "platform": "linux". The connector prints the returned device token once; store it in a secure OS credential store or secret manager. Do not commit it.

## 6. Queue a desktop app launch

Desktop connectors use the appId returned by that device's apps.list command. The account token can queue a typed command for a device owned by the account:

```http
POST /v1/device/command
Authorization: Bearer <ACCOUNT_TOKEN>
Content-Type: application/json

{
  "deviceId": "windows-<UUID>",
  "action": "apps.launch",
  "params": { "appId": "<APP_ID_FROM_APPS_LIST>" }
}
```

Desktop file commands use rootIndex and relativePath. The root index maps to one of the folders explicitly approved in JARVIS_ALLOWED_DIRS on that desktop device. A file read is limited to supported text formats and 64 KB.
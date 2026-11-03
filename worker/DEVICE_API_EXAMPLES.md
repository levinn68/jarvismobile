# JARVIS Device API examples

All values are placeholders. Never commit actual credentials.

## Bootstrap enrollment
```http
POST /v1/devices/enroll
Authorization: Bearer <DEVICE_ENROLLMENT_SECRET>
Content-Type: application/json

{"deviceId":"rog6-test-0001","platform":"android","name":"ROG Phone 6","osVersion":"Android 14","appVersion":"1.0.0","capabilities":["files.read","apps.list","apps.launch","system.read"]}
```

Store the returned device token in Android Keystore or the platform's secure credential store.

## Heartbeat
```http
POST /v1/devices/rog6-test-0001/heartbeat
Authorization: Bearer <DEVICE_TOKEN>
```

## Queue a file search
```http
POST /v1/device/command
Authorization: Bearer <DEVICE_TOKEN>
Content-Type: application/json

{"deviceId":"rog6-test-0001","action":"files.search","params":{"query":"laporan","scope":"user-selected"}}
```

The native connector polls `GET /v1/device/commands?deviceId=rog6-test-0001`, validates local permissions, executes the typed action, and reports results through a future result-ack endpoint. A queued command is not a completed action.

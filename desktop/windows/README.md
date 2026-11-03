# JARVIS Windows Connector

Node.js 20+ connector for the JARVIS account-scoped device API.

## Actions

- system.status: Windows host, OS, CPU, memory, and uptime metadata.
- apps.list and apps.launch: enumerate and launch Start Menu .lnk shortcuts only.
- files.list, files.search, and files.read: access only explicitly configured folder roots. Text reads are capped at 64 KB.

No arbitrary shell command, remote script execution, registry editing, or privilege escalation is provided.

## Pairing

1. Install Node.js 20 or newer.
2. Set JARVIS_ACCOUNT_TOKEN in a PowerShell session.
3. Run npm run pair.
4. Store the printed device token in a secure secret manager or Windows Credential Manager. Do not commit it or paste it into source files.
5. Set JARVIS_DEVICE_ID and JARVIS_DEVICE_TOKEN in the connector process environment.

Example PowerShell session:

    $env:JARVIS_ACCOUNT_TOKEN = "<account-token>"
    npm run pair

    $env:JARVIS_DEVICE_ID = "<device-id>"
    $env:JARVIS_DEVICE_TOKEN = "<device-token>"
    $env:JARVIS_ALLOWED_DIRS = "$HOME\Documents;$HOME\Desktop"
    npm start

JARVIS_ALLOWED_DIRS is a semicolon-separated list of folders the user explicitly approves. Do not set it to an entire drive or user profile unless that level of access is intended. File reads are text-only and limited to 64 KB.

The connector polls every 15 seconds by default. Set JARVIS_POLL_INTERVAL_MS to change the interval (minimum 5 seconds).

The default API base URL is the JARVIS Worker endpoint. Override it with JARVIS_API_BASE_URL when testing against another deployment.

The pair command prints the device token once. Keep it out of logs, screenshots, Git commits, and shared chat.

# JARVIS macOS and Linux Connector

Shared Node.js 20+ connector for macOS and Linux.

## Actions

- system.status: host, OS, CPU, memory, and uptime metadata.
- apps.list and apps.launch: macOS application bundles from /Applications and ~/Applications, or Linux .desktop entries from standard application directories.
- files.list, files.search, and files.read: access only explicitly configured folder roots. Text reads are capped at 64 KB.

App launching uses fixed OS commands with argument arrays. The connector does not execute arbitrary shell commands or scripts.

## Pairing

1. Install Node.js 20 or newer.
2. Set JARVIS_ACCOUNT_TOKEN in the current terminal session.
3. Run npm run pair.
4. Store the printed device token in a secure secret manager or OS keychain. Do not commit it or paste it into source files.
5. Set JARVIS_DEVICE_ID and JARVIS_DEVICE_TOKEN before starting the connector.

Example macOS shell:

    export JARVIS_ACCOUNT_TOKEN="<account-token>"
    npm run pair
    export JARVIS_DEVICE_ID="<device-id>"
    export JARVIS_DEVICE_TOKEN="<device-token>"
    export JARVIS_ALLOWED_DIRS="$HOME/Documents:$HOME/Desktop"
    npm start

Linux uses the same commands. JARVIS_ALLOWED_DIRS is a colon-separated list on macOS/Linux. Only add folders the user explicitly approves; do not set it to the entire home directory unless intended.

The connector polls every 15 seconds by default. Set JARVIS_POLL_INTERVAL_MS to change the interval (minimum 5 seconds). Set JARVIS_API_BASE_URL to override the Worker endpoint during testing.

The pair command prints the device token once. Keep it out of logs, screenshots, Git commits, and shared chat.

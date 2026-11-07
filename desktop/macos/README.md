# JARVIS Desktop Agent — macOS & Linux (Batch 5)

The JARVIS macOS Desktop Agent shares its canonical POSIX implementation with `desktop/unix/src/agent.js` and `desktop/unix/src/credentials.js`.

## Canonical Source & Synchronization

- **Canonical POSIX Source**: `desktop/unix/src/agent.js` and `desktop/unix/src/credentials.js`
- **macOS Package Target**: `desktop/macos/src/agent.js` and `desktop/macos/src/credentials.js`
- To synchronize and verify SHA-256 checksums, syntax (`node --check`), and automated tests:
  ```bash
  bash RECONSTRUCT_AGENT.sh
  ```

## Supported Capabilities on macOS

1. **`system.status`**: Reports hostname, Darwin kernel version, macOS ProductVersion (`/System/Library/CoreServices/SystemVersion.plist`), CPU architecture/model/count, memory usage, uptime, network connectivity status, and approved directory count.
2. **`apps.list` / `apps.launch` / `apps.launch_label`**:
   - Discovers `.app` bundles inside `/Applications`, `/System/Applications`, `/System/Applications/Utilities`, and `~/Applications`.
   - Parses `Contents/Info.plist` to extract `CFBundleIdentifier` (`bundleId`) and application names.
   - Launches applications via `/usr/bin/open` (`open -a <bundlePath>` or `open -b <bundleId>`) with `shell: false` and sanitized child environment variables.
3. **`files.list` / `files.search` / `files.read`**:
   - Strictly isolated to directories listed in `JARVIS_ALLOWED_DIRS`.
   - Enforces Unicode NFC normalization, symlink escape blocking, symlink loop prevention, TOCTOU file-descriptor inode verification (`O_NOFOLLOW`), binary file rejection, and a 64 KB read cap.

## Credential Security

- **Primary**: macOS Keychain via `/usr/bin/security` (`service: com.jarvis.desktop.device`), storing only non-sensitive metadata in `~/.config/jarvis/device-credentials.json`.
- **Fallback**: When Keychain is unavailable (e.g., headless CI), encrypts the token using `AES-256-GCM` with a machine/user-derived key and `0600` file permissions.

export const PLATFORM_CONNECTORS = Object.freeze({
  android: { transport: "https", permissions: "Android runtime permissions, SAF, supported system services" },
  windows: { transport: "https|websocket", permissions: "Windows user consent and Start Menu/approved-folder permissions" },
  macos: { transport: "https", permissions: "macOS user consent, /Applications allowlist, and JARVIS_ALLOWED_DIRS" },
  linux: { transport: "https", permissions: "Linux user consent, .desktop allowlist, and JARVIS_ALLOWED_DIRS" },
  ios: { transport: "https", permissions: "iOS sandbox and entitlement-controlled APIs" },
  ipados: { transport: "https", permissions: "iPadOS sandbox and entitlement-controlled APIs" },
  wearos: { transport: "https", permissions: "Wear OS runtime permissions and companion pairing" },
  web: { transport: "https", permissions: "Browser permission prompts and origin sandbox" },
  iot: { transport: "https|mqtt-bridge", permissions: "Device-specific pairing and ACL" }
});

export function connectorFor(platform) {
  return PLATFORM_CONNECTORS[String(platform || "").toLowerCase()] || null;
}

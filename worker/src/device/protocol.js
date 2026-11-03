export const DEVICE_PROTOCOL_VERSION = "1.0.0";

export const SUPPORTED_PLATFORMS = new Set([
  "android", "windows", "macos", "linux", "ios", "ipados", "wearos", "web", "iot"
]);

export const CAPABILITIES = new Set([
  "files.read", "files.write", "apps.list", "apps.launch", "screen.read",
  "camera.capture", "media.read", "notifications.read", "notifications.manage",
  "system.read", "system.settings", "calendar.read", "contacts.read",
  "location.read", "bluetooth.manage", "device.pair"
]);

export const SENSITIVE_CAPABILITIES = new Set([
  "files.write", "notifications.manage", "system.settings", "contacts.read",
  "location.read", "camera.capture", "bluetooth.manage"
]);

const ANDROID_ACTIONS = Object.freeze({
  "files.list": { capability: "files.read", params: [] },
  "files.search": { capability: "files.read", params: ["query"] },
  "files.read": { capability: "files.read", params: ["documentId"] },
  "apps.list": { capability: "apps.list", params: [] },
  "apps.launch": { capability: "apps.launch", params: ["packageName"] },
  "system.status": { capability: "system.read", params: [] },
  "notifications.list": { capability: "notifications.read", params: [] }
});

function validateParams(action, raw) {
  if (raw == null) raw = {};
  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new Error("Command params must be a JSON object");
  }

  const schema = ANDROID_ACTIONS[action];
  const unknown = Object.keys(raw).filter((key) => !schema.params.includes(key));
  if (unknown.length) {
    throw new Error("Unsupported parameter for " + action + ": " + unknown[0]);
  }

  const params = {};
  if (action === "files.search") {
    const query = typeof raw.query === "string" ? raw.query.trim() : "";
    if (!query || query.length > 200) {
      throw new Error("files.search requires query (1-200 characters)");
    }
    params.query = query;
  } else if (action === "files.read") {
    const documentId = typeof raw.documentId === "string" ? raw.documentId.trim() : "";
    if (!documentId || documentId.length > 1024) {
      throw new Error("files.read requires a valid documentId");
    }
    params.documentId = documentId;
  } else if (action === "apps.launch") {
    const packageName = typeof raw.packageName === "string" ? raw.packageName.trim() : "";
    if (
      !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName) ||
      packageName.length > 200
    ) {
      throw new Error("apps.launch requires a valid Android packageName");
    }
    params.packageName = packageName;
  }
  return params;
}

export function normalizeDevice(input) {
  const id = String(input?.deviceId || "").trim();
  const platform = String(input?.platform || "").toLowerCase();
  const accountId = String(input?.accountId || "").trim();

  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(id)) {
    throw new Error("deviceId must be 8-80 safe characters");
  }
  if (!/^[a-zA-Z0-9_-]{12,80}$/.test(accountId)) {
    throw new Error("A valid accountId is required");
  }
  if (!SUPPORTED_PLATFORMS.has(platform)) {
    throw new Error("Unsupported device platform");
  }

  const capabilities = Array.isArray(input?.capabilities)
    ? [...new Set(input.capabilities.map(String).filter((item) => CAPABILITIES.has(item)))].slice(0, 40)
    : [];

  return {
    deviceId: id,
    accountId,
    platform,
    name: String(input?.name || "JARVIS device").slice(0, 80),
    osVersion: String(input?.osVersion || "").slice(0, 40),
    appVersion: String(input?.appVersion || "").slice(0, 40),
    capabilities,
    protocolVersion: DEVICE_PROTOCOL_VERSION,
    lastSeen: new Date().toISOString()
  };
}

export function validateCommand(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("Command must be a JSON object");
  }
  const action = typeof input.action === "string" ? input.action : "";
  const schema = ANDROID_ACTIONS[action];
  if (!schema) throw new Error("Unsupported Android device action");

  return {
    commandId: crypto.randomUUID(),
    action,
    capability: schema.capability,
    params: validateParams(action, input.params),
    createdAt: new Date().toISOString(),
    requiresConfirmation: false
  };
}

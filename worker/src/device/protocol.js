import { webCrypto } from "../utils.js";

export const DEVICE_PROTOCOL_VERSION = "1.0.0";

export const SUPPORTED_PLATFORMS = new Set([
  "android", "windows", "macos", "linux", "ios", "ipados", "wearos", "web", "iot"
]);

export const CAPABILITIES = new Set([
  "files.read", "files.write", "apps.list", "apps.launch", "screen.read", "screen.control",
  "camera.capture", "media.read", "notifications.read", "notifications.manage",
  "system.read", "system.settings", "calendar.read", "contacts.read",
  "location.read", "bluetooth.manage", "device.pair"
]);

export const SENSITIVE_CAPABILITIES = new Set([
  "files.write", "notifications.manage", "system.settings",
  "location.read", "camera.capture", "bluetooth.manage", "screen.control", "screen.read"
]);

const ANDROID_ACTIONS = Object.freeze({
  "files.list": { capability: "files.read", params: [] },
  "files.search": { capability: "files.read", params: ["query"] },
  "files.read": { capability: "files.read", params: ["documentId"] },
  "apps.list": { capability: "apps.list", params: [] },
  "apps.launch": { capability: "apps.launch", params: ["packageName"] },
  "apps.launch_label": { capability: "apps.launch", params: ["appName"] },
  "OPEN_APP": { capability: "apps.launch", params: ["packageName", "appName"] },
  "screen.read": { capability: "screen.read", params: [] },
  "screen.tap": { capability: "screen.control", params: ["x", "y"] },
  "TAP_COORDINATE": { capability: "screen.control", params: ["x", "y"] },
  "screen.type": { capability: "screen.control", params: ["text"] },
  "TYPE_TEXT": { capability: "screen.control", params: ["text"] },
  "screen.click_text": { capability: "screen.control", params: ["text"] },
  "CLICK_TEXT": { capability: "screen.control", params: ["text"] },
  "screen.scroll": { capability: "screen.control", params: ["direction"] },
  "SCROLL": { capability: "screen.control", params: ["direction"] },
  "screen.global_action": { capability: "screen.control", params: ["globalAction"] },
  "GLOBAL_ACTION": { capability: "screen.control", params: ["globalAction"] },
  "system.wait": { capability: "system.read", params: ["durationMs"] },
  "WAIT": { capability: "system.read", params: ["durationMs"] },
  "contacts.list": { capability: "contacts.read", params: [] },
  "calendar.list": { capability: "calendar.read", params: [] },
  "system.status": { capability: "system.read", params: [] },
  "notifications.list": { capability: "notifications.read", params: [] }
});

const DESKTOP_ACTIONS = Object.freeze({
  "files.list": { capability: "files.read", params: ["rootIndex", "relativePath"] },
  "files.search": { capability: "files.read", params: ["query", "rootIndex"] },
  "files.read": { capability: "files.read", params: ["rootIndex", "relativePath"] },
  "apps.list": { capability: "apps.list", params: [] },
  "apps.launch": { capability: "apps.launch", params: ["appId"] },
  "apps.launch_label": { capability: "apps.launch", params: ["appName"] },
  "system.status": { capability: "system.read", params: [] }
});

const VALID_SCROLL_DIRECTIONS = new Set(["up", "down", "left", "right"]);
const VALID_GLOBAL_ACTIONS = new Set(["BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS"]);

function validateParams(action, raw, schema, platform) {
  if (raw == null) raw = {};
  if (typeof raw !== "object" || Array.isArray(raw)) {
    throw new Error("Command params must be a JSON object");
  }

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
  }
  if (platform === "android") {
    if (action === "files.read") {
      const documentId = typeof raw.documentId === "string" ? raw.documentId.trim() : "";
      if (!documentId || documentId.length > 1024) {
        throw new Error("files.read requires a valid documentId");
      }
      params.documentId = documentId;
    } else if (action === "apps.launch_label") {
      const appName = typeof raw.appName === "string" ? raw.appName.trim() : "";
      if (!appName || appName.length > 100) throw new Error("apps.launch_label requires appName (1-100 characters)");
      params.appName = appName;
    } else if (action === "apps.launch") {
      const packageName = typeof raw.packageName === "string" ? raw.packageName.trim() : "";
      if (
        !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName) ||
        packageName.length > 200
      ) {
        throw new Error("apps.launch requires a valid Android packageName");
      }
      params.packageName = packageName;
    } else if (action === "OPEN_APP") {
      const packageName = typeof raw.packageName === "string" ? raw.packageName.trim() : "";
      const appName = typeof raw.appName === "string" ? raw.appName.trim() : "";
      if (!packageName && !appName) {
        throw new Error("OPEN_APP requires packageName or appName");
      }
      if (packageName) {
        if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName) || packageName.length > 200) {
          throw new Error("OPEN_APP requires a valid Android packageName");
        }
        params.packageName = packageName;
      }
      if (appName) {
        if (appName.length > 100) throw new Error("OPEN_APP appName must be 1-100 characters");
        params.appName = appName;
      }
    } else if (action === "files.search") {
      params.query = raw.query.trim();
    } else if (action === "screen.tap" || action === "TAP_COORDINATE") {
      if (typeof raw.x !== "number" || !Number.isFinite(raw.x) || raw.x < 0 || raw.x > 1 ||
          typeof raw.y !== "number" || !Number.isFinite(raw.y) || raw.y < 0 || raw.y > 1) {
        throw new Error(`${action} requires normalized x and y coordinates from 0 to 1`);
      }
      params.x = raw.x;
      params.y = raw.y;
    } else if (action === "screen.type" || action === "TYPE_TEXT") {
      const value = typeof raw.text === "string" ? raw.text : "";
      if (!value.trim() || value.length > 500) {
        throw new Error(`${action} requires text (1-500 characters)`);
      }
      params.text = value;
    } else if (action === "screen.click_text" || action === "CLICK_TEXT") {
      const value = typeof raw.text === "string" ? raw.text.trim() : "";
      if (!value || value.length > 200) {
        throw new Error(`${action} requires text (1-200 characters)`);
      }
      params.text = value;
    } else if (action === "screen.scroll" || action === "SCROLL") {
      const dir = typeof raw.direction === "string" ? raw.direction.trim().toLowerCase() : "";
      if (!VALID_SCROLL_DIRECTIONS.has(dir)) {
        throw new Error(`${action} requires direction (up, down, left, or right)`);
      }
      params.direction = dir;
    } else if (action === "screen.global_action" || action === "GLOBAL_ACTION") {
      const ga = typeof raw.globalAction === "string" ? raw.globalAction.trim().toUpperCase() : "";
      if (!VALID_GLOBAL_ACTIONS.has(ga)) {
        throw new Error(`${action} requires globalAction (BACK, HOME, RECENTS, NOTIFICATIONS, or QUICK_SETTINGS)`);
      }
      params.globalAction = ga;
    } else if (action === "system.wait" || action === "WAIT") {
      const durationMs = Number(raw.durationMs);
      if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 5000) {
        throw new Error(`${action} requires integer durationMs between 100 and 5000`);
      }
      params.durationMs = durationMs;
    }
    return params;
  }

  if (["windows", "macos", "linux"].includes(platform)) {
    if (action === "apps.launch_label") {
      const appName = typeof raw.appName === "string" ? raw.appName.trim() : "";
      if (!appName || appName.length > 100) {
        throw new Error("apps.launch_label requires appName (1-100 characters)");
      }
      params.appName = appName;
    }
    if (action === "apps.launch") {
      const appId = typeof raw.appId === "string" ? raw.appId.trim() : "";
      if (!/^[A-Za-z0-9_-]{1,512}$/.test(appId)) {
        throw new Error("apps.launch requires a valid appId returned by apps.list");
      }
      params.appId = appId;
    }
    if (action.startsWith("files.")) {
      if (raw.rootIndex !== undefined) {
        if (!Number.isInteger(raw.rootIndex) || raw.rootIndex < 0 || raw.rootIndex > 19) {
          throw new Error("rootIndex must be an integer from 0 to 19");
        }
        params.rootIndex = raw.rootIndex;
      }
      if (action !== "files.search") {
        const relativePath = raw.relativePath === undefined ? "" :
          (typeof raw.relativePath === "string" ? raw.relativePath : null);
        if (relativePath === null || relativePath.length > 1024) {
          throw new Error(action + " requires a valid relativePath");
        }
        if (action === "files.read" && (!relativePath || !Number.isInteger(raw.rootIndex))) {
          throw new Error("files.read requires rootIndex and relativePath");
        }
        if (relativePath) params.relativePath = relativePath;
      }
      if (action === "files.search" && raw.rootIndex !== undefined) {
        params.rootIndex = raw.rootIndex;
      }
    }
    if (action === "files.search" && raw.rootIndex === undefined) {
      // Search all explicitly configured roots.
    }
    return params;
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

export function validateCommand(input, platform = "android") {
  if (!SUPPORTED_PLATFORMS.has(platform)) {
    throw new Error("Unsupported device platform");
  }
  const actions = platform === "android" ? ANDROID_ACTIONS :
    ["windows", "macos", "linux"].includes(platform) ? DESKTOP_ACTIONS : null;
  if (!actions) {
    throw new Error("No command connector is registered for platform: " + platform);
  }
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("Command must be a JSON object");
  }
  const action = typeof input.action === "string" ? input.action : "";
  const schema = actions[action];
  if (!schema) throw new Error("Unsupported " + platform + " device action");

  return {
    commandId: webCrypto.randomUUID(),
    action,
    capability: schema.capability,
    params: validateParams(action, input.params, schema, platform),
    createdAt: new Date().toISOString(),
    requiresConfirmation: SENSITIVE_CAPABILITIES.has(schema.capability)
  };
}

export function createDeviceSkills({ requestDeviceCommand, authorize } = {}) {
  if (typeof requestDeviceCommand !== "function") {
    throw new Error("requestDeviceCommand adapter is required");
  }
  const run = async (deviceId, action, params = {}, context = {}) => {
    const access = await authorize?.({ deviceId, action, params }, context);
    if (access && !access.allowed) return { ok: false, status: "blocked", ...access };
    return requestDeviceCommand({
      deviceId,
      action,
      params,
      userConfirmed: false
    });
  };
  return {
    listFiles: (deviceId, params, ctx) => run(deviceId, "files.list", params, ctx),
    searchFiles: (deviceId, params, ctx) => run(deviceId, "files.search", params, ctx),
    readFile: (deviceId, params, ctx) => run(deviceId, "files.read", params, ctx),
    listApps: (deviceId, params, ctx) => run(deviceId, "apps.list", params, ctx),
    launchApp: (deviceId, params, ctx) => run(deviceId, "apps.launch", params, ctx),
    launchAppByLabel: (deviceId, params, ctx) => run(deviceId, "apps.launch_label", params, ctx),
    systemStatus: (deviceId, params, ctx) => run(deviceId, "system.status", params, ctx),
    listNotifications: (deviceId, params, ctx) => run(deviceId, "notifications.list", params, ctx),
    listContacts: (deviceId, params, ctx) => run(deviceId, "contacts.list", params, ctx),
    listCalendar: (deviceId, params, ctx) => run(deviceId, "calendar.list", params, ctx),
    readScreen: (deviceId, params, ctx) => run(deviceId, "screen.read", params, ctx),
    tapScreen: (deviceId, params, ctx) => run(deviceId, "screen.tap", params, ctx),
    typeScreen: (deviceId, params, ctx) => run(deviceId, "screen.type", params, ctx)
  };
}
export const DEVICE_PROTOCOL_VERSION = "1.0.0";
export const SUPPORTED_PLATFORMS = new Set(["android","windows","ios","ipados","wearos","web","iot"]);
export const CAPABILITIES = new Set(["files.read","files.write","apps.list","apps.launch","screen.read","camera.capture","media.read","notifications.read","notifications.manage","system.read","system.settings","calendar.read","contacts.read","location.read","bluetooth.manage","device.pair"]);
export const SENSITIVE_CAPABILITIES = new Set(["files.write","notifications.manage","system.settings","contacts.read","location.read","camera.capture","bluetooth.manage"]);
export function normalizeDevice(input) {
 const id=String(input?.deviceId||"").trim(), platform=String(input?.platform||"").toLowerCase();
 if(!/^[a-zA-Z0-9_-]{8,80}$/.test(id))throw new Error("deviceId must be 8-80 safe characters");
 if(!SUPPORTED_PLATFORMS.has(platform))throw new Error("Unsupported device platform");
 const capabilities=Array.isArray(input?.capabilities)?[...new Set(input.capabilities.map(String).filter(x=>CAPABILITIES.has(x)))].slice(0,40):[];
 return {deviceId:id,platform,name:String(input?.name||"JARVIS device").slice(0,80),osVersion:String(input?.osVersion||"").slice(0,40),appVersion:String(input?.appVersion||"").slice(0,40),capabilities,protocolVersion:DEVICE_PROTOCOL_VERSION,lastSeen:new Date().toISOString()};
}
export function validateCommand(input) {
 const action=String(input?.action||"");
 const allowed=new Set(["files.list","files.search","files.read","files.create","files.move","files.copy","apps.list","apps.launch","screen.capture","camera.capture","notifications.list","system.status","media.list","calendar.list"]);
 if(!allowed.has(action))throw new Error("Unsupported device action");
 const capability=action.startsWith("files.")?([ "files.create","files.move","files.copy"].includes(action)?"files.write":"files.read"):action.startsWith("apps.")?(action==="apps.launch"?"apps.launch":"apps.list"):action==="screen.capture"?"screen.read":action==="camera.capture"?"camera.capture":action.startsWith("notifications.")?"notifications.read":action==="system.status"?"system.read":action==="media.list"?"media.read":"calendar.read";
 const params=input?.params&&typeof input.params==="object"&&!Array.isArray(input.params)?input.params:{};
 if(JSON.stringify(params).length>8000)throw new Error("Command parameters too large");
 return {commandId:crypto.randomUUID(),action,capability,params,createdAt:new Date().toISOString(),requiresConfirmation:["files.create","files.move","files.copy","camera.capture"].includes(action)};
}

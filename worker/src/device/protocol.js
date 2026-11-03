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

const ACTION_SCHEMAS = {
 "apps.list": { capability:"apps.list", keys:[] },
 "apps.launch": { capability:"apps.launch", keys:["packageName"] },
 "system.status": { capability:"system.read", keys:[] },
 "notifications.list": { capability:"notifications.read", keys:[] },
 "camera.capture": { capability:"camera.capture", keys:[] },
 "files.list": { capability:"files.read", keys:[] },
 "files.search": { capability:"files.read", keys:["query"] },
 "files.read": { capability:"files.read", keys:["documentId"] }
};

function validateParams(action, raw) {
 if(raw == null) return {};
 if(typeof raw !== "object" || Array.isArray(raw)) throw new Error("Command params must be an object");
 const schema=ACTION_SCHEMAS[action];
 const keys=Object.keys(raw);
 if(keys.some(key=>!schema.keys.includes(key))) throw new Error("Unsupported parameter for "+action);
 if(keys.length>schema.keys.length) throw new Error("Too many command parameters");
 const params={};
 if(action==="apps.launch") {
  const value=raw.packageName;
  if(typeof value!=="string" || value.length>255 || !/^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$/.test(value)) throw new Error("A valid Android packageName is required");
  params.packageName=value;
 }
 if(action==="files.search") {
  const value=raw.query;
  if(typeof value!=="string" || value.trim().length===0 || value.length>120) throw new Error("File search query must be 1-120 characters");
  params.query=value.trim();
 }
 if(action==="files.read") {
  const value=raw.documentId;
  if(typeof value!=="string" || value.trim().length===0 || value.length>1024) throw new Error("A valid SAF documentId is required");
  params.documentId=value;
 }
 if(JSON.stringify(params).length>2048) throw new Error("Command parameters too large");
 return params;
}

export function validateCommand(input) {
 const action=String(input?.action||"");
 const schema=ACTION_SCHEMAS[action];
 if(!schema)throw new Error("Unsupported device action");
 const params=validateParams(action,input?.params);
 return {commandId:crypto.randomUUID(),action,capability:schema.capability,params,createdAt:new Date().toISOString(),requiresConfirmation:action==="camera.capture"};
}

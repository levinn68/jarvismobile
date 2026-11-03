export const PLATFORM_CONNECTORS=Object.freeze({
 android:{transport:"https",permissions:"Android runtime permissions, SAF, supported system services"},
 windows:{transport:"https|websocket",permissions:"Windows user consent and process/file permissions"},
 ios:{transport:"https",permissions:"iOS sandbox and entitlement-controlled APIs"},
 ipados:{transport:"https",permissions:"iPadOS sandbox and entitlement-controlled APIs"},
 wearos:{transport:"https",permissions:"Wear OS runtime permissions and companion pairing"},
 web:{transport:"https",permissions:"Browser permission prompts and origin sandbox"},
 iot:{transport:"https|mqtt-bridge",permissions:"Device-specific pairing and ACL"}
});
export function connectorFor(platform){return PLATFORM_CONNECTORS[platform]||null;}

import { normalizeDevice } from "./protocol.js";
const key=id=>"device:"+id;
export async function saveDevice(kv,input){if(!kv)throw new Error("DEVICE_REGISTRY KV binding is required");const d=normalizeDevice(input);await kv.put(key(d.deviceId),JSON.stringify(d),{expirationTtl:7776000});return d;}
export async function getDevice(kv,id){if(!kv)return null;const raw=await kv.get(key(id));if(!raw)return null;try{return JSON.parse(raw)}catch(_){return null}}
export async function listDevices(kv){if(!kv)return [];const page=await kv.list({prefix:"device:",limit:100});const values=await Promise.all(page.keys.map(async k=>{const raw=await kv.get(k.name);try{return raw?JSON.parse(raw):null}catch(_){return null}}));return values.filter(Boolean);}
export async function touchDevice(kv,id){const d=await getDevice(kv,id);if(!d)return null;d.lastSeen=new Date().toISOString();await kv.put(key(id),JSON.stringify(d),{expirationTtl:7776000});return d;}

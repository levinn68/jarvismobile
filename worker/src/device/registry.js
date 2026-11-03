import { normalizeDevice } from "./protocol.js";
const key=id=>"device:"+id;
const accountKey=id=>"account:"+id;
const accountDevicePrefix=id=>"account-device:"+id+":";
export async function createAccount(kv,accountId) {
 if(!kv)throw new Error("DEVICE_REGISTRY KV binding is required");
 const record={accountId,createdAt:new Date().toISOString(),status:"active"};
 await kv.put(accountKey(accountId),JSON.stringify(record));
 return record;
}
export async function getAccount(kv,id) {
 if(!kv)return null;const raw=await kv.get(accountKey(id));if(!raw)return null;
 try{return JSON.parse(raw)}catch(_){return null}
}
export async function saveDevice(kv,input) {
 if(!kv)throw new Error("DEVICE_REGISTRY KV binding is required");
 const d=normalizeDevice(input);
 if(!d.accountId)throw new Error("accountId is required for device enrollment");
 const account=await getAccount(kv,d.accountId);
 if(!account||account.status!=="active")throw new Error("JARVIS account not found or inactive");
 await kv.put(key(d.deviceId),JSON.stringify(d),{expirationTtl:7776000});
 await kv.put(accountDevicePrefix(d.accountId)+d.deviceId,"1",{expirationTtl:7776000});
 return d;
}
export async function getDevice(kv,id) {
 if(!kv)return null;const raw=await kv.get(key(id));if(!raw)return null;
 try{return JSON.parse(raw)}catch(_){return null}
}
export async function listDevicesForAccount(kv,accountId) {
 if(!kv)return [];
 const page=await kv.list({prefix:accountDevicePrefix(accountId),limit:100});
 const values=await Promise.all(page.keys.map(async item=>getDevice(kv,item.name.slice(accountDevicePrefix(accountId).length))));
 return values.filter(Boolean);
}
export async function listDevices(kv) {
 if(!kv)return [];const page=await kv.list({prefix:"device:",limit:100});
 const values=await Promise.all(page.keys.map(async item=>getDevice(kv,item.name.slice(7))));
 return values.filter(Boolean);
}
export async function touchDevice(kv,id) {
 const d=await getDevice(kv,id);if(!d)return null;d.lastSeen=new Date().toISOString();
 await kv.put(key(id),JSON.stringify(d),{expirationTtl:7776000});return d;
}

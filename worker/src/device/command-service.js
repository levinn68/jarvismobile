import { validateCommand } from "./protocol.js";
import { authorizeCapability } from "./policy.js";
export async function enqueueDeviceCommand({kv,device,input,context={}}) {
 if(!kv)throw new Error("DEVICE_COMMANDS KV binding is required");
 const command=validateCommand(input,device.platform);
 const decision=authorizeCapability(device,command.capability,{userConfirmed:context.userConfirmed});
 if(!decision.allowed)return {ok:false,status:"blocked",...decision};
 const record={...command,deviceId:device.deviceId,status:"queued"};
 await kv.put("command:"+device.deviceId+":"+command.commandId,JSON.stringify(record),{expirationTtl:300});
 return {ok:true,commandId:command.commandId,status:"queued",deviceId:device.deviceId};
}
export async function listDeviceCommands({kv,deviceId,limit=20}) {
 if(!kv)throw new Error("DEVICE_COMMANDS KV binding is required");
 const page=await kv.list({prefix:"command:"+deviceId+":",limit:Math.min(50,Math.max(1,limit))});
 const records=await Promise.all(page.keys.map(async item=>{const raw=await kv.get(item.name);try{return raw?JSON.parse(raw):null}catch(_){return null}}));
 return records.filter(x=>x&&x.status==="queued");
}
export async function acknowledgeDeviceCommand({kv,deviceId,commandId,result}) {
 if(!kv)throw new Error("DEVICE_COMMANDS KV binding is required");
 const key="command:"+deviceId+":"+commandId,raw=await kv.get(key);if(!raw)return {ok:false,error:"Command not found or expired"};
 let record;try{record=JSON.parse(raw)}catch(_){return {ok:false,error:"Command record is invalid"}}
 if(record.status==="completed")return {ok:true,commandId,status:"completed",alreadyAcknowledged:true};
 record.status="completed";record.completedAt=new Date().toISOString();record.result=result??null;
 await kv.put(key,JSON.stringify(record),{expirationTtl:86400});
 return {ok:true,commandId,status:record.status};
}

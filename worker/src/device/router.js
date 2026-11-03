import { issueDeviceToken, verifyDeviceToken } from "./auth.js";
import { getDevice, listDevices, saveDevice, touchDevice } from "./registry.js";
import { authorizeCapability, publicDevice } from "./policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand } from "./command-service.js";
const json=(d,s=200)=>new Response(JSON.stringify(d),{status:s,headers:{"Content-Type":"application/json; charset=utf-8","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"Content-Type, Authorization","Access-Control-Allow-Methods":"GET, POST, PUT, OPTIONS"}});
async function readBody(r){try{return await r.json()}catch(_){throw new Error("Request body must be valid JSON")}}
export async function handleDeviceRequest(request,env,url){
 const path=url.pathname;
 if(path==="/v1/devices/enroll"&&request.method==="POST"){
  if(!env.DEVICE_ENROLLMENT_SECRET||!env.DEVICE_TOKEN_SECRET)return json({ok:false,error:"Device enrollment is not configured."},503);
  const supplied=(request.headers.get("Authorization")||"").replace(/^Bearer\s+/i,"");if(supplied!==env.DEVICE_ENROLLMENT_SECRET)return json({ok:false,error:"Unauthorized"},401);
  try{const d=await saveDevice(env.DEVICE_REGISTRY,await readBody(request));const token=await issueDeviceToken(d.deviceId,env.DEVICE_TOKEN_SECRET);return json({ok:true,device:publicDevice(d),token,tokenType:"Bearer"});}catch(e){return json({ok:false,error:e.message},400)}
 }
 const match=path.match(/^\/v1\/devices\/([a-zA-Z0-9_-]{8,80})(?:\/(heartbeat|capabilities))?$/);
 if(match){const [,id,action]=match;const claims=await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id);if(!claims)return json({ok:false,error:"Unauthorized device token"},401);const d=await getDevice(env.DEVICE_REGISTRY,id);if(!d)return json({ok:false,error:"Device not registered"},404);
  if(action==="heartbeat"&&request.method==="POST"){const updated=await touchDevice(env.DEVICE_REGISTRY,id);return json({ok:true,device:publicDevice(updated)});}
  if(action==="capabilities"&&request.method==="PUT"){try{const b=await readBody(request);const updated=await saveDevice(env.DEVICE_REGISTRY,{...d,capabilities:b.capabilities});return json({ok:true,device:publicDevice(updated)});}catch(e){return json({ok:false,error:e.message},400)}}
  return json({ok:true,device:publicDevice(d)});
 }
 if(path==="/v1/devices"&&request.method==="GET"){const c=await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET);if(!c)return json({ok:false,error:"Unauthorized"},401);const own=await getDevice(env.DEVICE_REGISTRY,c.sub);return json({ok:true,devices:own?[publicDevice(own)]:[]});}
 if(path==="/v1/device/command"&&request.method==="POST"){try{const b=await readBody(request),id=String(b.deviceId||"");if(!await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id))return json({ok:false,error:"Unauthorized"},401);const d=await getDevice(env.DEVICE_REGISTRY,id);if(!d)return json({ok:false,error:"Device not registered"},404);const result=await enqueueDeviceCommand({kv:env.DEVICE_COMMANDS,device:d,input:b,context:{userConfirmed:false}});return json(result,result.ok?200:403);}catch(e){return json({ok:false,error:e.message},400)}}
 if(path==="/v1/device/commands"&&request.method==="GET"){const id=url.searchParams.get("deviceId")||"";if(!await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id))return json({ok:false,error:"Unauthorized"},401);try{return json({ok:true,commands:await listDeviceCommands({kv:env.DEVICE_COMMANDS,deviceId:id})});}catch(e){return json({ok:false,error:e.message},503)}}
 if(path==="/v1/device/commands/ack"&&request.method==="POST"){try{const b=await readBody(request),id=String(b.deviceId||"");if(!await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id))return json({ok:false,error:"Unauthorized"},401);const result=await acknowledgeDeviceCommand({kv:env.DEVICE_COMMANDS,deviceId:id,commandId:String(b.commandId||""),result:b.result});return json(result,result.ok?200:404);}catch(e){return json({ok:false,error:e.message},400)}}
 return null;
}

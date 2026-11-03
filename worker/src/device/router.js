import { issueAccountToken, issueDeviceToken, verifyAccountToken, verifyDeviceToken } from "./auth.js";
import { createAccount, getAccount, getDevice, listDevicesForAccount, saveDevice, touchDevice } from "./registry.js";
import { publicDevice } from "./policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand, getDeviceCommand } from "./command-service.js";
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{"Content-Type":"application/json; charset=utf-8","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"Content-Type, Authorization","Access-Control-Allow-Methods":"GET, POST, PUT, OPTIONS"}});
async function readBody(request){try{return await request.json()}catch(_){throw new Error("Request body must be valid JSON")}}
const bearer=request=>(request.headers.get("Authorization")||"").replace(/^Bearer\s+/i,"");
export async function handleDeviceRequest(request,env,url) {
 const path=url.pathname;
 if(path==="/v1/accounts/google"&&request.method==="POST") {
  if(!env.SUPABASE_URL||!env.SUPABASE_ANON_KEY||!env.DEVICE_TOKEN_SECRET||!env.DEVICE_REGISTRY)
   return json({ok:false,error:"Google-linked device enrollment is not configured on the Worker."},503);
  const accessToken=bearer(request);
  if(!accessToken)return json({ok:false,error:"A Supabase Google session is required"},401);
  try {
   const response=await fetch(env.SUPABASE_URL.replace(/\/$/,"")+"/auth/v1/user",{
    headers:{apikey:env.SUPABASE_ANON_KEY,Authorization:"Bearer "+accessToken,Accept:"application/json"}
   });
   if(!response.ok)return json({ok:false,error:"Supabase session is invalid or expired"},401);
   const user=await response.json();
   const userId=String(user.id||"");
   if(!/^[a-f0-9-]{36}$/i.test(userId))return json({ok:false,error:"Supabase user identity is invalid"},401);
   const accountId="acct-"+userId;
   let account=await getAccount(env.DEVICE_REGISTRY,accountId);
   if(!account)account=await createAccount(env.DEVICE_REGISTRY,accountId);
   if(account.status!=="active")return json({ok:false,error:"JARVIS account is inactive"},403);
   const accountToken=await issueAccountToken(accountId,env.DEVICE_TOKEN_SECRET,3600);
   return json({ok:true,account:{accountId,createdAt:account.createdAt},accountToken,tokenType:"Bearer",expiresIn:3600,provider:"supabase-google"},200);
  } catch(e) { return json({ok:false,error:String(e.message||e).slice(0,200)},502); }
 }
 if(path==="/v1/accounts/create"&&request.method==="POST") {
  if(!env.DEVICE_ENROLLMENT_SECRET||!env.DEVICE_TOKEN_SECRET||!env.DEVICE_REGISTRY)return json({ok:false,error:"Account enrollment is not configured."},503);
  if(bearer(request)!==env.DEVICE_ENROLLMENT_SECRET)return json({ok:false,error:"Unauthorized bootstrap secret"},401);
  let enrollmentStage="initializing";
  try {
   enrollmentStage="generate_account_id";
   const accountId="acct-"+crypto.randomUUID();
   enrollmentStage="write_account_to_kv";
   const account=await createAccount(env.DEVICE_REGISTRY,accountId);
   enrollmentStage="issue_account_token";
   const accountToken=await issueAccountToken(accountId,env.DEVICE_TOKEN_SECRET);
   return json({ok:true,account:{accountId,createdAt:account.createdAt},accountToken,tokenType:"Bearer",expiresIn:31536000},201);
  } catch(e){
   const errorName=String(e?.name||"Error").slice(0,80);
   const errorMessage=String(e?.message||e).slice(0,240);
   console.error("[JARVIS_ACCOUNT_ENROLLMENT_FAILED]",JSON.stringify({
    stage:enrollmentStage,
    name:errorName,
    message:errorMessage,
    stack:String(e?.stack||"").slice(0,1200)
   }));
   return json({ok:false,error:errorMessage,stage:enrollmentStage},400);
  }
 }
 if(path==="/v1/devices/enroll"&&request.method==="POST") {
  if(!env.DEVICE_TOKEN_SECRET||!env.DEVICE_REGISTRY)return json({ok:false,error:"Device enrollment is not configured."},503);
  const accountClaims=await verifyAccountToken(request,env.DEVICE_TOKEN_SECRET);
  if(!accountClaims)return json({ok:false,error:"A valid JARVIS account token is required"},401);
  try {
   const input=await readBody(request),device=await saveDevice(env.DEVICE_REGISTRY,{...input,accountId:accountClaims.sub});
   const token=await issueDeviceToken(device.deviceId,env.DEVICE_TOKEN_SECRET);
   return json({ok:true,device:publicDevice(device),token,tokenType:"Bearer"},201);
  } catch(e){return json({ok:false,error:e.message},400)}
 }
 const match=path.match(/^\/v1\/devices\/([a-zA-Z0-9_-]{8,80})(?:\/(heartbeat|capabilities))?$/);
 if(match) {
  const [,id,action]=match,claims=await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id);
  if(!claims)return json({ok:false,error:"Unauthorized device token"},401);
  const device=await getDevice(env.DEVICE_REGISTRY,id);
  if(!device)return json({ok:false,error:"Device not registered"},404);
  if(action==="heartbeat"&&request.method==="POST") {
   const updated=await touchDevice(env.DEVICE_REGISTRY,id);return json({ok:true,device:publicDevice(updated)});
  }
  if(action==="capabilities"&&request.method==="PUT") {
   try {const body=await readBody(request),updated=await saveDevice(env.DEVICE_REGISTRY,{...device,capabilities:body.capabilities});return json({ok:true,device:publicDevice(updated)});}
   catch(e){return json({ok:false,error:e.message},400)}
  }
  return json({ok:true,device:publicDevice(device)});
 }
 if(path==="/v1/devices"&&request.method==="GET") {
  const claims=await verifyAccountToken(request,env.DEVICE_TOKEN_SECRET);
  if(!claims)return json({ok:false,error:"Account token required"},401);
  const account=await getAccount(env.DEVICE_REGISTRY,claims.sub);
  if(!account||account.status!=="active")return json({ok:false,error:"Account is inactive"},403);
  const devices=await listDevicesForAccount(env.DEVICE_REGISTRY,claims.sub);
  return json({ok:true,account:{accountId:claims.sub},devices:devices.map(publicDevice)});
 }
 if(path==="/v1/device/command"&&request.method==="POST") {
  try {
   const body=await readBody(request),id=String(body.deviceId||"");
   const accountClaims=await verifyAccountToken(request,env.DEVICE_TOKEN_SECRET);
   const deviceClaims=accountClaims?null:await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id);
   if(!accountClaims&&!deviceClaims)return json({ok:false,error:"Account or matching device token required"},401);
   const device=await getDevice(env.DEVICE_REGISTRY,id);
   if(!device)return json({ok:false,error:"Device not registered"},404);
   if(accountClaims&&device.accountId!==accountClaims.sub)return json({ok:false,error:"Device belongs to another account"},403);
   const result=await enqueueDeviceCommand({kv:env.DEVICE_COMMANDS,device,input:body,context:{userConfirmed:false}});
   return json(result,result.ok?200:403);
  } catch(e){return json({ok:false,error:e.message},400)}
 }
 if(path==="/v1/device/command/status"&&request.method==="GET") {
  const id=url.searchParams.get("deviceId")||"",commandId=url.searchParams.get("commandId")||"";
  const accountClaims=await verifyAccountToken(request,env.DEVICE_TOKEN_SECRET);
  const deviceClaims=accountClaims?null:await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id);
  if(!accountClaims&&!deviceClaims)return json({ok:false,error:"Account or matching device token required"},401);
  const device=await getDevice(env.DEVICE_REGISTRY,id);
  if(!device)return json({ok:false,error:"Device not registered"},404);
  if(accountClaims&&device.accountId!==accountClaims.sub)return json({ok:false,error:"Device belongs to another account"},403);
  try {
   const command=await getDeviceCommand({kv:env.DEVICE_COMMANDS,deviceId:id,commandId});
   return command?json({ok:true,command}):json({ok:false,error:"Command not found or expired"},404);
  } catch(e){return json({ok:false,error:e.message},503)}
 }
 if(path==="/v1/device/commands"&&request.method==="GET") {
  const id=url.searchParams.get("deviceId")||"";
  if(!await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id))return json({ok:false,error:"Unauthorized"},401);
  try{return json({ok:true,commands:await listDeviceCommands({kv:env.DEVICE_COMMANDS,deviceId:id})});}
  catch(e){return json({ok:false,error:e.message},503)}
 }
 if(path==="/v1/device/commands/ack"&&request.method==="POST") {
  try {
   const body=await readBody(request),id=String(body.deviceId||"");
   if(!await verifyDeviceToken(request,env.DEVICE_TOKEN_SECRET,id))return json({ok:false,error:"Unauthorized"},401);
   const result=await acknowledgeDeviceCommand({kv:env.DEVICE_COMMANDS,deviceId:id,commandId:String(body.commandId||""),result:body.result});
   return json(result,result.ok?200:404);
  } catch(e){return json({ok:false,error:e.message},400)}
 }
 return null;
}

export function createDeviceSkills({requestDeviceCommand,authorize}={}) {
 if(typeof requestDeviceCommand!=="function")throw new Error("requestDeviceCommand adapter is required");
 const run=async(deviceId,action,params={},context={})=>{const access=await authorize?.({deviceId,action,params},context);if(access&&!access.allowed)return {ok:false,status:"blocked",...access};return requestDeviceCommand({deviceId,action,params,userConfirmed:Boolean(context.userConfirmed)});};
 return {
  listFiles:(deviceId,params,ctx)=>run(deviceId,"files.list",params,ctx),
  searchFiles:(deviceId,params,ctx)=>run(deviceId,"files.search",params,ctx),
  readFile:(deviceId,params,ctx)=>run(deviceId,"files.read",params,ctx),
  listApps:(deviceId,params,ctx)=>run(deviceId,"apps.list",params,ctx),
  launchApp:(deviceId,params,ctx)=>run(deviceId,"apps.launch",params,ctx),
  systemStatus:(deviceId,params,ctx)=>run(deviceId,"system.status",params,ctx)
 };
}

export function createProviderManager({providers={},rotator}={}) {
 return {
  names(){return Object.keys(providers);},
  configured(env,provider){return (providers[provider]?.keys||[]).filter(key=>typeof env[key]==="string"&&env[key].trim());},
  async invoke(provider,env,input) {
   const config=providers[provider];if(!config||typeof config.invoke!=="function")throw new Error("Provider adapter unavailable: "+provider);
   const keys=rotator?rotator.order(provider,this.configured(env,provider)):this.configured(env,provider);
   if(!keys.length)throw new Error("No configured key for "+provider);
   let lastError;
   for(const key of keys){try{const result=await config.invoke({env,key,input});rotator?.success(provider,key);return {provider,keyName:key,result};}catch(error){lastError=error;rotator?.failure(provider,key,{status:Number(String(error?.message||"").match(/HTTP (\d+)/)?.[1]||0)});}}
   throw lastError||new Error("All keys failed for "+provider);
  }
 };
}

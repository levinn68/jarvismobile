export async function runWithFallback({providers=[],invoke,isRetryable=()=>true,onAttempt=()=>{}}) {
 const errors=[];
 for(const provider of providers){onAttempt({provider,status:"started"});try{const result=await invoke(provider);onAttempt({provider,status:"success"});return {ok:true,provider,result,errors};}catch(error){const item={provider,error:String(error?.message||error).slice(0,240)};errors.push(item);onAttempt({provider,status:"failed",error:item.error});if(!isRetryable(error))break;}}
 return {ok:false,errors};
}

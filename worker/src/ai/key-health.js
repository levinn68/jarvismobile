export function classifyProviderError(error) {
 const message=String(error?.message||error||"").toLowerCase();
 const status=Number(String(message).match(/http\s+(\d{3})/)?.[1]||0);
 if(status===401||status===403)return {kind:"auth",retryable:false,status};
 if(status===429||/quota|rate.?limit|resource_exhausted/.test(message))return {kind:"quota",retryable:true,status:status||429};
 if(status>=500||/timeout|network|fetch failed/.test(message))return {kind:"transient",retryable:true,status};
 return {kind:"unknown",retryable:false,status};
}

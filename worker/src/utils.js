export function boundedString(value,max=500){return String(value??"").trim().slice(0,max);}
export function jsonResponse(data,status=200,headers={}){return new Response(JSON.stringify(data),{status,headers:{"Content-Type":"application/json; charset=utf-8",...headers}});}
export function safeError(error,max=300){return String(error?.message||error||"Unknown error").slice(0,max);}
export function isPlainObject(value){return Boolean(value)&&typeof value==="object"&&!Array.isArray(value);}

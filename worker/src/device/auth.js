const encoder = new TextEncoder();
function b64(bytes) { let value=""; for (const byte of bytes) value+=String.fromCharCode(byte); return btoa(value).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_"); }
function unb64(value) { const binary=atob(value.replace(/-/g,"+").replace(/_/g,"/")); return Uint8Array.from(binary,char=>char.charCodeAt(0)); }
async function sign(value,secret) {
 const key=await crypto.subtle.importKey("raw",encoder.encode(secret),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
 return b64(new Uint8Array(await crypto.subtle.sign("HMAC",key,encoder.encode(value))));
}
async function issueToken(claims,secret,ttlSeconds) {
 if(!secret) throw new Error("DEVICE_TOKEN_SECRET is not configured");
 const now=Math.floor(Date.now()/1000);
 const payload=b64(encoder.encode(JSON.stringify({...claims,iat:now,exp:now+ttlSeconds})));
 return payload+"."+await sign(payload,secret);
}
async function verifyToken(request,secret,scope,expectedSubject) {
 if(!secret)return null;
 const token=(request.headers.get("Authorization")||"").replace(/^Bearer\s+/i,"");
 const [payload,signature,...extra]=token.split(".");
 if(!payload||!signature||extra.length)return null;
 const expected=await sign(payload,secret);
 if(signature.length!==expected.length)return null;
 let difference=0;for(let i=0;i<signature.length;i++)difference|=signature.charCodeAt(i)^expected.charCodeAt(i);
 if(difference!==0)return null;
 try {
  const claims=JSON.parse(new TextDecoder().decode(unb64(payload)));
  if(claims.exp<Date.now()/1000||claims.scope!==scope||(expectedSubject&&claims.sub!==expectedSubject))return null;
  return claims;
 } catch(_){return null}
}
export const issueDeviceToken=(deviceId,secret,ttlSeconds=2592000)=>issueToken({sub:deviceId,scope:"device"},secret,ttlSeconds);
export const verifyDeviceToken=(request,secret,expectedDeviceId)=>verifyToken(request,secret,"device",expectedDeviceId);
export const issueAccountToken=(accountId,secret,ttlSeconds=31536000)=>issueToken({sub:accountId,scope:"account"},secret,ttlSeconds);
export const verifyAccountToken=(request,secret)=>verifyToken(request,secret,"account");

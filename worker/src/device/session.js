export function createDeviceSession({now=()=>Date.now(),ttlMs=90000}={}) {
 const sessions=new Map();
 return {
  touch(deviceId,metadata={}){const session={deviceId,lastSeen:now(),metadata};sessions.set(deviceId,session);return session;},
  get(deviceId){const s=sessions.get(deviceId);if(!s)return null;if(now()-s.lastSeen>ttlMs){sessions.delete(deviceId);return null;}return s;},
  online(deviceId){return Boolean(this.get(deviceId));},
  remove(deviceId){return sessions.delete(deviceId);},
  list(){for(const [id,s] of sessions)if(now()-s.lastSeen>ttlMs)sessions.delete(id);return [...sessions.values()];}
 };
}
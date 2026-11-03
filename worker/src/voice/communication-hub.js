export function createCommunicationHub() {
 const peers=new Map();
 return {
  register(id,send){if(typeof send!=="function")throw new TypeError("send must be a function");peers.set(id,send);return ()=>peers.delete(id);},
  async send(id,message){const fn=peers.get(id);if(!fn)return {ok:false,error:"Peer is not connected"};await fn(message);return {ok:true};},
  async broadcast(message){const results=await Promise.allSettled([...peers.values()].map(send=>send(message)));return {sent:results.filter(x=>x.status==="fulfilled").length,failed:results.filter(x=>x.status==="rejected").length};},
  count(){return peers.size;}
 };
}

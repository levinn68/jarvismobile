export function createKeyRotator({cooldownMs=30000,maxFailures=2}={}) {
 const state=new Map();
 return {
  order(provider,keyNames,now=Date.now()) {
   return [...keyNames].sort((a,b)=>(state.get(provider+":"+a)?.nextAt||0)-(state.get(provider+":"+b)?.nextAt||0));
  },
  success(provider,key){state.delete(provider+":"+key);},
  failure(provider,key,{status=0,now=Date.now()}={}) {
   const id=provider+":"+key,old=state.get(id)||{failures:0,nextAt:0};
   old.failures++;old.nextAt=now+(status===429?Math.max(cooldownMs,60000):cooldownMs*Math.min(old.failures,maxFailures));
   state.set(id,old);
  },
  snapshot(){return [...state.entries()].map(([id,value])=>({id,...value}));}
 };
}

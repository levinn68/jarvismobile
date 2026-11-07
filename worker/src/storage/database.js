export function createKvStore(binding,{prefix=""}={}) {
 if(!binding)throw new Error("A Cloudflare KV binding is required");
 return {
  get:async key=>binding.get(prefix+key,"json"),
  put:async(key,value,options={})=>binding.put(prefix+key,JSON.stringify(value),options),
  delete:async key=>binding.delete(prefix+key),
  list:async(options={})=>binding.list({...options,prefix:prefix+(options.prefix||"")})
 };
}

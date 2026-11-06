export function createEventBus() {
 const listeners=new Map();
 return {
  on(type,listener){if(typeof listener!=="function")throw new TypeError("listener must be a function");const set=listeners.get(type)||new Set();set.add(listener);listeners.set(type,set);return ()=>set.delete(listener);},
  emit(type,payload){for(const listener of listeners.get(type)||[])try{listener(payload)}catch(_){}},
  clear(){listeners.clear();}
 };
}
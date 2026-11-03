export const VOICE_STATES=Object.freeze(["idle","listening","thinking","speaking","executing","error"]);
export function normalizeVoiceState(value){return VOICE_STATES.includes(value)?value:"idle";}
export function createVoiceVisualState(){let state="idle";const listeners=new Set();return {get:()=>state,set(next){state=normalizeVoiceState(next);for(const fn of listeners)try{fn(state)}catch(_){}return state;},subscribe(fn){listeners.add(fn);return()=>listeners.delete(fn);}};}

import { createEventBus } from "./event-bus.js";
import { createTaskOrchestrator } from "./task-orchestrator.js";
export function createJarvisCore(options={}) {
 const events=createEventBus();
 const tasks=createTaskOrchestrator({...options,emit:(name,data)=>events.emit(name,data)});
 return {events,tasks,version:"1.0.0",async health(){return {ok:true,service:"jarvis-core",version:"1.0.0"};}};
}
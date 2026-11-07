import { webCrypto } from "../utils.js";

export function createTaskOrchestrator({ handlers = {}, authorize = async () => ({ allowed: false, reason: "Task authorization is not configured." }), emit = () => {} } = {}) {
  return {
    async run(task, context = {}) {
      const type = String(task?.type || "");
      const handler = handlers[type];
      if (!handler) throw new Error("No handler registered for task: " + type);
      const decision = await authorize(task, context);
      if (!decision?.allowed) {
        return {
          ok: false,
          status: "blocked",
          reason: decision?.reason || "Not authorized",
          requiresConfirmation: Boolean(decision?.requiresConfirmation)
        };
      }
      const id = webCrypto.randomUUID();
      emit("task.started", { id, type });
      try {
        const result = await handler(task.payload || {}, context);
        emit("task.completed", { id, type });
        return { ok: true, id, status: "completed", result };
      } catch (error) {
        emit("task.failed", { id, type, error: String(error?.message || error) });
        return { ok: false, id, status: "failed", error: String(error?.message || error).slice(0, 300) };
      }
    }
  };
}

import { webCrypto } from "../utils.js";

export function createTaskOrchestrator({
  handlers = {},
  authorize = async () => ({ allowed: false, reason: "Task authorization is not configured." }),
  execute = async () => ({ ok: false, error: "Task executor is not configured." }),
  onProgress = null,
} = {}) {
  const tasks = new Map();

  async function submit(taskType, payload = {}, meta = {}) {
    const id = crypto.randomUUID?.() || webCrypto.randomUUID();
    const task = {
      id,
      type: taskType,
      status: "pending",
      payload,
      meta,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    tasks.set(id, task);

    const auth = await authorize({ type: taskType, payload, meta });
    if (!auth?.allowed) {
      task.status = "rejected";
      task.error = auth?.reason || "Unauthorized";
      task.updatedAt = Date.now();
      return { ...task };
    }

    task.status = "running";
    task.updatedAt = Date.now();
    if (typeof onProgress === "function") {
      try { onProgress(task); } catch (_) {}
    }

    try {
      const handler = handlers[taskType] || execute;
      const result = await handler({ type: taskType, payload, meta, taskId: id });
      task.status = result?.ok === false ? "failed" : "completed";
      task.result = result;
      if (result?.error) task.error = result.error;
    } catch (err) {
      task.status = "failed";
      task.error = err?.message || String(err);
    }
    task.updatedAt = Date.now();
    if (typeof onProgress === "function") {
      try { onProgress(task); } catch (_) {}
    }
    return { ...task };
  }

  function get(id) {
    const t = tasks.get(id);
    return t ? { ...t } : null;
  }

  function list() {
    return Array.from(tasks.values()).map((t) => ({ ...t }));
  }

  return { submit, get, list };
}

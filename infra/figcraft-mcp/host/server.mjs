import http from "node:http";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.PORT || 8080);
const WORKSPACE = process.env.FIGCRAFT_WORKSPACE || "/workspace";
const MAX_BODY = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 120_000;
const pending = new Map();
let child;
let stdoutBuffer = "";

function startEngine() {
  if (child && child.exitCode === null) return;
  child = spawn("figcraft-cli", [
    "mcp",
    "--automation-read-root", WORKSPACE,
    "--automation-write-root", WORKSPACE
  ], { stdio: ["pipe", "pipe", "inherit"], env: process.env });

  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdoutBuffer += chunk;
    let newline;
    while ((newline = stdoutBuffer.indexOf("\n")) >= 0) {
      const line = stdoutBuffer.slice(0, newline).trim();
      stdoutBuffer = stdoutBuffer.slice(newline + 1);
      if (!line) continue;
      try {
        const message = JSON.parse(line);
        const key = String(message.id);
        const waiter = pending.get(key);
        if (waiter) {
          pending.delete(key);
          clearTimeout(waiter.timer);
          waiter.resolve(message);
        }
      } catch (error) {
        console.error("Ignoring non-JSON FigCraft stdout line:", error.message);
      }
    }
  });

  child.on("exit", (code, signal) => {
    console.error("FigCraft engine exited", { code, signal });
    for (const [key, waiter] of pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("FigCraft engine exited"));
      pending.delete(key);
    }
    child = undefined;
    stdoutBuffer = "";
  });
  child.on("error", (error) => console.error("FigCraft engine spawn error:", error));
}

function sendToEngine(message) {
  return new Promise((resolve, reject) => {
    startEngine();
    if (!child || child.stdin.destroyed) return reject(new Error("FigCraft engine is unavailable"));
    if (message.id === undefined || message.id === null) {
      child.stdin.write(JSON.stringify(message) + "\n");
      return resolve(null);
    }
    const key = String(message.id);
    const timer = setTimeout(() => {
      pending.delete(key);
      reject(new Error("FigCraft request timed out"));
    }, REQUEST_TIMEOUT_MS);
    pending.set(key, { resolve, reject, timer });
    child.stdin.write(JSON.stringify(message) + "\n", (error) => {
      if (error) {
        clearTimeout(timer);
        pending.delete(key);
        reject(error);
      }
    });
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
    res.end(JSON.stringify({ ok: true, service: "figcraft-mcp-host", engine: child && child.exitCode === null ? "running" : "starting" }));
    return;
  }

  if (req.url !== "/mcp" || req.method !== "POST") {
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "not_found" }));
    return;
  }

  let body = "";
  try {
    for await (const chunk of req) {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY) {
        res.writeHead(413);
        res.end();
        return;
      }
    }
    const message = JSON.parse(body);
    if (!message || typeof message !== "object" || Array.isArray(message) || message.jsonrpc !== "2.0") {
      res.writeHead(400, { "content-type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid JSON-RPC request" } }));
      return;
    }
    const response = await sendToEngine(message);
    if (response === null) {
      res.writeHead(202, { "cache-control": "no-store" });
      res.end();
      return;
    }
    res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
    res.end(JSON.stringify(response));
  } catch (error) {
    console.error("MCP request failed:", error);
    if (!res.headersSent) {
      res.writeHead(502, { "content-type": "application/json", "cache-control": "no-store" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32603, message: "FigCraft engine unavailable" } }));
    }
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`FigCraft MCP host listening on :${PORT}`);
  startEngine();
});

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    server.close(() => {
      if (child && child.exitCode === null) child.kill("SIGTERM");
      process.exit(0);
    });
  });
}

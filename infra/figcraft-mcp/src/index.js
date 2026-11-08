import { Container } from "@cloudflare/containers";

export class FigCraftContainer extends Container {
  defaultPort = 8080;
  sleepAfter = "10m";
}

function unauthorized() {
  return new Response(JSON.stringify({ error: "unauthorized" }), {
    status: 401,
    headers: { "content-type": "application/json", "www-authenticate": "Bearer" }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      const id = env.FIGCRAFT_CONTAINER.idFromName("figcraft-engine");
      return env.FIGCRAFT_CONTAINER.get(id).fetch(new Request("http://container/health"));
    }

    if (url.pathname !== "/mcp") {
      return new Response("Not Found", { status: 404 });
    }

    if (request.method !== "POST") {
      return new Response(JSON.stringify({
        error: "method_not_supported",
        message: "This bridge currently supports stateless MCP JSON-RPC POST requests."
      }), { status: 405, headers: { "content-type": "application/json", allow: "POST" } });
    }

    const expected = env.MCP_AUTH_TOKEN;
    const supplied = request.headers.get("authorization") || "";
    if (!expected || supplied !== `Bearer ${expected}`) return unauthorized();

    const contentType = request.headers.get("content-type") || "";
    if (!contentType.toLowerCase().includes("application/json")) {
      return new Response(JSON.stringify({ error: "content_type_must_be_application_json" }), {
        status: 415, headers: { "content-type": "application/json" }
      });
    }

    const id = env.FIGCRAFT_CONTAINER.idFromName("figcraft-engine");
    const upstream = env.FIGCRAFT_CONTAINER.get(id);
    const forwarded = new Request("http://container/mcp", request);
    return upstream.fetch(forwarded);
  }
};

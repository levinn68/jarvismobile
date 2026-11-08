# FigCraft Remote MCP Host

This directory contains the Cloudflare Worker + Container deployment for a remote FigCraft MCP endpoint.

## Architecture

- Cloudflare Worker authenticates clients and exposes `/mcp`.
- Cloudflare Container runs the upstream Rust `figcraft-cli mcp` process.
- The container bridge forwards JSON-RPC messages between HTTP and the upstream newline-delimited STDIO transport.
- The upstream project is built from https://github.com/storytold/figcraft during the container image build.

## Important status

This is infrastructure source, not a deployed service. A successful deployment still requires Cloudflare Workers Builds or a local Wrangler deployment, a configured `MCP_AUTH_TOKEN` secret, and protocol smoke tests against a live container.

## Deploy

Use the Cloudflare Workers Builds connection for this repository with the project root set to `infra/figcraft-mcp` and deploy command `npx wrangler deploy`. Alternatively, deploy from this directory with Wrangler after installing dependencies and authenticating Wrangler to the intended Cloudflare account.

Set the Worker secret `MCP_AUTH_TOKEN` before exposing the endpoint. Never commit the token.

## Endpoints

- `GET /health` returns service readiness metadata.
- `POST /mcp` accepts MCP JSON-RPC requests.
- `GET /mcp` and `DELETE /mcp` are not implemented by this stateless bridge; clients should use request/response Streamable HTTP mode.

## Security

The Worker requires bearer-token authentication on `/mcp`. The FigCraft CLI is launched with a confined workspace root. The container filesystem is ephemeral; do not treat it as durable user storage.

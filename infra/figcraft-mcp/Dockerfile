FROM rust:bookworm AS figcraft-builder
RUN apt-get update && apt-get install -y --no-install-recommends     git pkg-config libssl-dev cmake clang build-essential ca-certificates     && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN git clone --depth 1 https://github.com/storytold/figcraft.git .
RUN cargo build --release -p figcraft-cli

FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends     ca-certificates libssl3     && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=figcraft-builder /build/target/release/figcraft-cli /usr/local/bin/figcraft-cli
COPY host/server.mjs ./server.mjs
RUN mkdir -p /workspace && chown -R node:node /app /workspace
USER node
ENV PORT=8080
ENV FIGCRAFT_WORKSPACE=/workspace
EXPOSE 8080
CMD ["node", "server.mjs"]

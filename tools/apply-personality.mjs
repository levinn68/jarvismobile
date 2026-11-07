import fs from "node:fs";
import path from "node:path";

const target = path.resolve(process.cwd(), "worker/src/index.js");
if (!fs.existsSync(target)) {
  console.error("Tidak menemukan worker/src/index.js. Jalankan script dari root repository JARVIS.");
  process.exit(1);
}

let source = fs.readFileSync(target, "utf8");
if (source.includes('from "./personality.js"') && source.includes("buildPersonalityPrompt")) {
  console.log("Personality Engine tampaknya sudah terpasang. Tidak ada perubahan.");  process.exit(0);
}

if (!source.includes("const systemBase =")) {
  console.error("Titik integrasi systemBase tidak ditemukan. File tidak diubah.");
  process.exit(1);
}

source = source.replace(
  /^/m,
  'import { buildPersonalityPrompt } from "./personality.js";\n'
);

const systemBaseLine = source.split("\n").findIndex((line) => line.includes("const systemBase ="));
if (systemBaseLine < 0) {
  console.error("Gagal menemukan deklarasi systemBase. File tidak diubah.");
  process.exit(1);
}

// systemBase in the current Worker is a single-line string declaration.
const lines = source.split("\n");
const insertAt = systemBaseLine + 1;
lines.splice(
  insertAt,
  0,
  '  const personalitySystem = buildPersonalityPrompt(prompt, body.history || body.messages || []);'
);
source = lines.join("\n");

// Apply the personality rules to all agent, reviewer, fallback, and final synthesis prompts.
source = source.replaceAll("${systemBase}", "${systemBase}\\n${personalitySystem}");

const backup = `${target}.before-personality.bak`;
if (!fs.existsSync(backup)) fs.copyFileSync(target, backup);
fs.writeFileSync(target, source, "utf8");
console.log("JARVIS Personality Engine berhasil diintegrasikan.");
console.log(`Backup backend lama: ${backup}`);
console.log("Tidak ada API key atau Cloudflare Secret yang dibaca maupun diubah.");

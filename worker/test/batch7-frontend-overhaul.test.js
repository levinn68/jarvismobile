import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");

const mainActivityPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/MainActivity.kt");
const designSystemPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ui/JarvisDesignSystem.kt");
const orbPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ui/HolographicOrb.kt");
const chatComponentsPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ui/JarvisChatComponents.kt");
const deviceSetupPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/DeviceSetupActivity.kt");
const pairingQrPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/PairingQrActivity.kt");
const approvalPath = path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/ScreenActionApprovalActivity.kt");
const stylesXmlPath = path.join(REPO_ROOT, "app/src/main/res/values/styles.xml");
const stringsXmlPath = path.join(REPO_ROOT, "app/src/main/res/values/strings.xml");

const mainKt = fs.readFileSync(mainActivityPath, "utf8");
const designKt = fs.readFileSync(designSystemPath, "utf8");
const orbKt = fs.readFileSync(orbPath, "utf8");
const chatKt = fs.readFileSync(chatComponentsPath, "utf8");
const setupKt = fs.readFileSync(deviceSetupPath, "utf8");
const qrKt = fs.readFileSync(pairingQrPath, "utf8");
const approvalKt = fs.readFileSync(approvalPath, "utf8");
const stylesXml = fs.readFileSync(stylesXmlPath, "utf8");
const stringsXml = fs.readFileSync(stringsXmlPath, "utf8");

test("Batch 7 Frontend Overhaul — 1. Chat Mode displays complete assistant responses without truncation", () => {
  // Verify Chat Mode preserves full payload.answer.trim() instead of stripping everything before KESIMPULAN JARVIS
  assert.ok(
    mainKt.includes('val baseAnswer = payload.answer.trim().ifBlank { "JARVIS tidak menerima jawaban akhir." }'),
    "Chat Mode must preserve the full assistant response without truncating before KESIMPULAN JARVIS"
  );
  // Verify RichMessageText renders headings, paragraphs, code blocks, tables, bullets, and images
  for (const blockType of [
    "is MarkdownBlock.Heading",
    "is MarkdownBlock.Paragraph",
    "is MarkdownBlock.ImageBlock",
    "is MarkdownBlock.Bullet",
    "is MarkdownBlock.Code",
    "is MarkdownBlock.Table"
  ]) {
    assert.ok(mainKt.includes(blockType), `RichMessageText must render ${blockType}`);
  }
});

test("Batch 7 Frontend Overhaul — 2. Chat Mode & Voice Mode contextual visual images and clean load-error fallback", () => {
  // Contextual visual fallback covers multiple domains and extracts inline markdown images
  assert.ok(mainKt.includes("private fun extractInlineMarkdownImages(markdown: String)"), "Must extract inline markdown images");
  assert.ok(mainKt.includes("defaultVisualFallbackFor("), "Must provide topic-aware visual fallback");
  for (const domainSnippet of [
    "Kontrol Perangkat & Sistem Desktop",
    "Telemetri Perangkat Android",
    "Arsitektur Kode & Engineering",
    "Analisis Finansial & Anggaran",
    "Eksplorasi Destinasi & Rute",
    "Performa Gaming & Hiburan Digital",
    "Eksplorasi Sains & Pengetahuan"
  ]) {
    assert.ok(mainKt.includes(domainSnippet), `Topic-aware visual fallback must include domain: ${domainSnippet}`);
  }
  // Verify BrokenImage icon is removed and replaced with a dimension-preserving HUD fallback card
  assert.equal(mainKt.includes("Icons.Default.BrokenImage"), false, "Must not display raw BrokenImage icon on load error");
  assert.ok(mainKt.includes("VISUAL HUD FALLBACK · PRATINJAU SIAP"), "Must render clean HUD fallback when image fails to load");
  assert.ok(mainKt.includes("var imgLoadError by remember(item.image)"), "ProductCarouselCards must handle image load errors cleanly");
});

test("Batch 7 Frontend Overhaul — 3. Voice Mode supports all 7 required Orb states, concise summary card, and up to 5 visual cards", () => {
  // Verify all 7 required states exist in VoiceOrbState enum and HolographicOrb
  const requiredOrbStates = ["Idle", "Listening", "Processing", "Responding", "Speaking", "Muted", "Error"];
  for (const state of requiredOrbStates) {
    assert.match(designKt, new RegExp(`\\b${state}\\b`), `JarvisDesignSystem.kt VoiceOrbState must include ${state}`);
    assert.ok(orbKt.includes(`VoiceOrbState.${state}`), `HolographicOrb.kt must handle VoiceOrbState.${state}`);
  }
  // Verify concise voice response summary extractor and card in Voice Mode
  assert.ok(mainKt.includes("private fun extractConciseVoiceSummary(fullAnswer: String): String"), "Must extract concise voice summary");
  assert.ok(mainKt.includes("var voiceConciseSummary by remember"), "Voice Mode must track concise summary state");
  assert.ok(mainKt.includes("MAX_VISUAL_CARDS = 5"), "Visual cards must be capped at 5");
  assert.ok(mainKt.includes("ProductCarouselCards(voiceProducts.take(MAX_VISUAL_CARDS))"), "Voice Mode must render up to 5 product cards");
  assert.ok(mainKt.includes("VoiceImageCarouselCards(voiceImages.take(MAX_VISUAL_CARDS))"), "Voice Mode must render up to 5 image cards");
});
test("Batch 7 Frontend Overhaul — 4. Voice Mode interruption, speech deduplication, AudioAttributes, and recognizer error handling", () => {
  // Interruption & deduplication
  assert.ok(mainKt.includes("private fun stopSpeaking("), "MainActivity must implement stopSpeaking() for voice interruption");
  assert.ok(mainKt.includes("private val speechGeneration = java.util.concurrent.atomic.AtomicInteger(0)"), "Must track speechGeneration to prevent duplicate playback");
  assert.ok(mainKt.includes("private val activeTtsConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)"), "Must track activeTtsConnection to cancel in-flight TTS requests");
  assert.ok(mainKt.includes("HENTIKAN SUARA"), "Voice Mode UI must expose an interrupt button during speech");
  // Deprecated setAudioStreamType replaced with AudioAttributes
  assert.equal(mainKt.includes("setAudioStreamType("), false, "Deprecated setAudioStreamType must not be used");
  assert.ok(mainKt.includes("setAudioAttributes("), "MediaPlayer must use modern AudioAttributes");
  // TTS init failure & SpeechRecognizer error handling
  assert.ok(mainKt.includes("ANDROID TTS ENGINE UNAVAILABLE"), "Must handle TextToSpeech initialization failure");
  assert.ok(mainKt.includes("SpeechRecognizer.ERROR_RECOGNIZER_BUSY"), "Must handle ERROR_RECOGNIZER_BUSY");
  assert.ok(mainKt.includes("SpeechRecognizer.ERROR_NETWORK"), "Must handle ERROR_NETWORK");
  assert.ok(mainKt.includes("SpeechRecognizer.ERROR_AUDIO"), "Must handle ERROR_AUDIO");
});

test("Batch 7 Frontend Overhaul — 5. Navigation strictly provides Chat Mode and Voice Mode with BackHandler and no destructive HOME button", () => {
  assert.equal(mainKt.includes("CORE 01 / HOME"), false, "Separate HOME mode label must be removed");
  assert.ok(mainKt.includes('val coreModeLabel = if (page == "VOICE") "CORE 01 / VOICE" else "CORE 01 / CHAT"'), "Only CHAT and VOICE modes should exist");
  assert.ok(mainKt.includes('BackHandler(enabled = page == "VOICE")'), "Back navigation in Voice Mode must return to Chat Mode");
});

test("Batch 7 Frontend Overhaul — 6. Performance optimizations, error retry card, logout confirmation, and secondary activity HUD styling", () => {
  // Stable LazyColumn keys & isolated clock composable
  assert.ok(mainKt.includes("items(items = lines, key = { it.id })"), "Chat LazyColumn must use stable Line.id keys");
  assert.ok(mainKt.includes("private fun VoiceHudClock()"), "1-second clock must be isolated in a leaf composable");
  // Error retry card in Chat Mode
  assert.ok(mainKt.includes("JarvisChatErrorCard("), "Chat Mode must display JarvisChatErrorCard on error responses");
  assert.ok(chatKt.includes("COBA LAGI"), "JarvisChatErrorCard must expose a retry action");
  // Two-step logout confirmation in Account dialog
  assert.ok(mainKt.includes("var confirmSignOut by remember"), "Account dialog must require logout confirmation");
  assert.ok(mainKt.includes("YA, KONFIRMASI KELUAR"), "Account dialog must display confirmation prompt before signing out");
  // Secondary activities HUD styling and dp scaling
  assert.ok(setupKt.includes("private fun dp(value: Int): Int"), "DeviceSetupActivity must use density-scaled dp units");
  assert.ok(setupKt.includes("minHeight = dp(48)"), "DeviceSetupActivity buttons must enforce 48dp minimum touch targets");
  assert.ok(qrKt.includes("private fun dp(value: Int): Int"), "PairingQrActivity must use density-scaled dp units");
  assert.ok(qrKt.includes("minHeight = dp(48)"), "PairingQrActivity buttons must enforce 48dp minimum touch targets");
  assert.ok(approvalKt.includes("KONFIRMASI KEAMANAN J.A.R.V.I.S."), "ScreenActionApprovalActivity must use HUD security styling");
  assert.ok(stylesXml.includes("#040911") && stringsXml.includes("app_name"), "XML resources must align with the #040911 HUD theme");
});

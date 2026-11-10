# InkFlow Studio — Privacy & Data Flow Policy

## 1. Privacy-First Principles
InkFlow Studio is designed to respect user privacy:
1. **Local Storage First:** All notebooks, handwritten strokes, imported PDFs, audio recordings, and flashcards are stored locally inside the app's sandboxed storage directory (`context.filesDir`).
2. **Zero Default Telemetry:** The app does not bundle third-party tracking SDKs or transmit user analytics.
3. **Explicit Permissions:**
   - **Microphone:** Requested strictly when the user activates the in-note audio recording feature.
   - **Storage / File Picker:** Uses the modern Android Storage Access Framework (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`), requesting no broad file-system storage permissions.

---

## 2. AI & Cloud Data Flow
- **On-Device AI Engine (Default):** The built-in summarizer and flashcard generator run entirely within the Android app process using local deterministic algorithms and rule-based NLP. No user data leaves the device.
- **External AI Providers (Optional):** If the user chooses to connect an external API (e.g. Gemini), the user must explicitly provide their own API key and give explicit per-request consent before any note text is dispatched over HTTPS.


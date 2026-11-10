# InkFlow Studio — Architecture Specification

## 1. System Overview
InkFlow Studio is a native Android application built on modern Android architecture patterns (MVVM + Clean Architecture), Jetpack Compose for UI, Room for local relational metadata, and custom Canvas/Drawing pipelines for low-latency digital ink rendering.

```
+-----------------------------------------------------------------------+
|                           Presentation Layer                          |
|  - Jetpack Compose UI (Material 3)                                    |
|  - Adaptive Tablet / Foldable / Phone Layouts                        |
|  - ViewModels & StateFlow UI State                                    |
+-----------------------------------------------------------------------+
                                  |
                                  v
+-----------------------------------------------------------------------+
|                              Domain Layer                             |
|  - Use Cases (Stroke Operations, PDF Rendering, AI Summaries)         |
|  - Domain Models (Document, Page, Stroke, CanvasObject, StudyCard)    |
|  - Interfaces (AiProvider, InkEngine, AudioEngine, PdfEngine)         |
+-----------------------------------------------------------------------+
                                  |
                                  v
+-----------------------------------------------------------------------+
|                               Data Layer                              |
|  - Room Local Database (SQLite: Documents, Pages, Tags, StudySets)     |
|  - File Storage (Sandboxed JSON stroke vectors, PDFs, audio clips)    |
|  - Repositories (DocumentRepository, StudyRepository, SettingsRepo)   |
|  - Local AI Adapter & Audio MediaRecorder Service                    |
+-----------------------------------------------------------------------+
```

---

## 2. Digital Ink Engine Architecture
The ink engine avoids treating notes as static bitmap snapshots. Every stroke is recorded as a mathematical vector path with pressure and temporal data.

### 2.1 Coordinate Space & Transformations
- All strokes are stored in **Document Space** (normalized millimeters or virtual points, e.g. standard A4 at 595 x 842 points).
- Screen touch events (`MotionEvent`) in **Screen Space** $(x_s, y_s)$ are transformed to **Document Space** $(x_d, y_d)$ via a matrix transform:
  $$x_d = \frac{x_s - \text{pan}_x}{\text{zoom}}, \quad y_d = \frac{y_s - \text{pan}_y}{\text{zoom}}$$
- Zoom and pan remain fluid without causing stroke degradation or pixelation.

### 2.2 Tool System & Rendering Pipelines
1. **Fountain Pen:** Variable stroke width computed from pointer pressure:
   $$w(p) = w_{base} \cdot (0.4 + 1.2 \cdot p)$$
   Quadratic Bézier curve interpolation connects discrete touch samples to prevent jagged lines.
2. **Ballpoint:** Consistent stroke width with subtle velocity dampening.
3. **Pencil:** Graphite grain simulation with opacity modulation and tilt shading.
4. **Highlighter:** Translucent stroke rendered with `BlendMode.SrcOver` or alpha layering ($30\text{--}40\%$ alpha) so underlying text and paper grids remain crisp.
5. **Eraser:**
   - **Stroke Eraser:** Bounding box hit-testing + line-segment distance calculation removes intersecting strokes in a single tap.
   - **Pixel/Segment Eraser:** Splits or trims intersecting stroke segments.

### 2.3 Undo / Redo Mechanism
- Managed through an in-memory command stack per page:
  - `AddStrokeCommand`: Pushes new stroke to the page model.
  - `DeleteStrokeCommand`: Removes stroke(s) with recovery snapshot.
  - Stack size is bounded (default 50 actions) to safeguard memory on tablet devices.

---

## 3. PDF Annotation Subsystem
1. **Source Preservation:** The original PDF is copied into app-private storage (`files/documents/<doc_id>/source.pdf`). The original file is never destructively overwritten.
2. **Rendering:** Android's native `android.graphics.pdf.PdfRenderer` generates hardware-accelerated bitmaps at the current viewport resolution.
3. **Annotation Layer:** An independent vector ink layer is overlaid on top of each PDF page. Annotations are saved as vector coordinates aligned with the PDF page aspect ratio.
4. **Export Engine:** `android.graphics.pdf.PdfDocument` renders each page of the original PDF, draws the corresponding vector annotation paths on top, and exports a clean, standalone PDF.

---

## 4. Audio & Synchronized Note Replay
- Audio is captured in AAC/M4A format using Android's `MediaRecorder`.
- While recording, each new ink stroke records an `audioTimestampMs` corresponding to the elapsed recording duration.
- During playback, as the media position advances, strokes created within that time window are visually highlighted, enabling lecture and meeting review.

---

## 5. Study Workspace & Spaced Repetition (SM-2)
- Study sets consist of flashcards with Front (Prompt), Back (Answer), and source document references.
- Spaced repetition algorithm calculates the next review date and ease factor ($EF$):
  $$EF' = EF + (0.1 - (5 - q) \cdot (0.08 + (5 - q) \cdot 0.02))$$
  where $q \in \{1, 2, 3, 5\}$ maps to `Again`, `Hard`, `Good`, and `Easy`.
- Cards are scheduled locally and can be practiced completely offline.

---

## 6. AI Assistant ("Ask InkFlow")
- Interface `AiProvider` decouples the user interface from specific AI backends.
- **Default Local Engine:** Fast keyword extraction, outline generator, and flashcard synthesizer running 100% on-device with zero network requests.
- **Cloud Provider (Consent-gated):** Pluggable HTTP adapter for Gemini / OpenAI APIs requiring explicit user consent and local API key storage in Android EncryptedSharedPreferences / secure storage.


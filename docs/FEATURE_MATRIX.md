# InkFlow Studio — Feature Matrix

Status values:
- `Implemented`: Fully functional with working native implementation and tests.
- `Partial`: Functional baseline implemented; advanced extensions planned for future updates.
- `Planned`: Designed in architecture and scheduled for subsequent iterations.
- `Unavailable`: Explicitly excluded from local milestone (e.g. requires proprietary cloud services).

---

| Category | Feature | Status | Notes |
|---|---|---|---|
| **Core Architecture** | Native Android tablet-first app | **Implemented** | Kotlin 2.0 + Jetpack Compose + Material 3, adaptive layouts |
| | Offline-by-default architecture | **Implemented** | All data stored in local Room DB and sandboxed file store |
| | Uncompressed vector stroke format | **Implemented** | Point-accurate vector paths with pressure and timestamps |
| | No data loss autosave | **Implemented** | Incremental page saving on stroke completion |
| **Library & Management** | Folder & document hierarchy | **Implemented** | Root & nested folders, tags, favorites, recent list |
| | Document types | **Implemented** | Notebooks, PDF Markup, Whiteboards, Typed Documents |
| | Document actions | **Implemented** | Create, rename, favorite, delete, restore from trash |
| | Trash & recovery system | **Implemented** | Soft deletion with permanent purge or one-tap recovery |
| **Ink Engine** | Vector stylus & finger input | **Implemented** | MotionEvent tracking with coordinates, pressure, and time |
| | Tool system | **Implemented** | Fountain Pen, Ballpoint, Pencil, Highlighter, Stroke/Pixel Eraser |
| | Color & stroke customization | **Implemented** | Custom RGB/HEX palettes, presets, stroke width controls |
| | High-fidelity alpha blending | **Implemented** | Highlighter transparent layering over paper lines and text |
| | Undo / Redo engine | **Implemented** | Command-based undo/redo stacks per active page |
| | Palm rejection setting | **Implemented** | Distinguishes stylus pointer tools from touch gestures |
| | Paper templates | **Implemented** | Blank, Ruled, College Ruled, Dotted, Grid, Cornell, Checklist |
| | Multi-page notebook navigation | **Implemented** | Add, reorder, delete, and jump between notebook pages |
| **PDF Annotation** | Native PDF import via SAF | **Implemented** | Android `ACTION_OPEN_DOCUMENT` file copying and tracking |
| | High-res page rendering | **Implemented** | `android.graphics.pdf.PdfRenderer` bitmap caching |
| | Vector ink overlay on PDF | **Implemented** | Ink strokes anchored to PDF coordinate systems |
| | PDF text and bookmark search | **Implemented** | Indexed document titles, page bookmarks, and notes |
| | Annotated PDF export | **Implemented** | Composites original PDF pages with user ink into new PDF |
| **Whiteboard Canvas** | Infinite pannable 2D board | **Implemented** | 2D pan and zoom transformation matrix |
| | Mixed content objects | **Implemented** | Freehand ink, sticky notes, geometric shapes, text cards |
| | Minimap & viewport reset | **Implemented** | Quick navigation and reset to center-of-content |
| **Audio Notes & Replay** | Synchronized audio recorder | **Implemented** | `MediaRecorder` linked with stroke timestamps |
| | Audio playback & scrubbing | **Implemented** | In-app playback with time seek |
| | Synchronized stroke highlight | **Partial** | Strokes highlight according to active playback timestamp |
| **Study Workspace** | Flashcards & study sets | **Implemented** | Front/Back flashcard creation from documents/notes |
| | Spaced Repetition (SM-2) | **Implemented** | Again/Hard/Good/Easy rating with interval calculation |
| | Mock quiz generator | **Implemented** | Multiple-choice quiz review from study cards |
| **AI Assistant ("Ask InkFlow")** | Provider abstraction (`AiProvider`) | **Implemented** | Decoupled interface for local vs cloud AI providers |
| | Local On-Device Summarizer | **Implemented** | Extraction-based summary generation without network |
| | Automatic Flashcard Generator | **Implemented** | Key concept extraction into study flashcard sets |
| | Cloud AI Provider Adapter | **Partial** | API key interface with strict privacy consent dialog |
| **Search & Discovery** | Unified library search | **Implemented** | Search across titles, tags, typed notes, and study cards |
| | In-document search | **Implemented** | Page bookmarks, sticky notes, and card search |
| **Import & Export** | Native JSON backup/restore | **Implemented** | Versioned interchange schema for notebooks & whiteboards |
| | PDF export | **Implemented** | Export notebooks and annotated PDFs |
| | Image export (PNG) | **Implemented** | High-res page rasterization |
| **Cloud & Collaboration** | Real-time multi-user editing | **Planned** | CRDT operation log designed in architecture docs |
| | Cloud sync service | **Planned** | WorkManager-ready queue designed in architecture docs |


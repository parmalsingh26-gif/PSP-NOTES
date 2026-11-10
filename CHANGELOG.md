# Changelog

All notable changes to **InkFlow Studio** will be documented in this file.

## [1.0.0] - 2026-11-10

### Initial Milestone Release
- **Core Architecture:**
  - Modern Kotlin 2.0 + Jetpack Compose + Material 3 application shell.
  - Adaptive two-tier layout with tablet Navigation Rail and responsive document grid.
  - Room 2.6 SQLite database with entities for Documents, Folders, Pages, and StudyCards.
  - Offline-first local file store with atomic file swapping for vector strokes.
- **Digital Ink Engine:**
  - Low-latency vector stylus and touch drawing engine with coordinate matrix transforms $(x_s, y_s) \to (x_d, y_d)$.
  - Tools: Fountain Pen (pressure-sensitive), Ballpoint, Pencil, Highlighter (alpha blending), Stroke Eraser, and Pan/Zoom.
  - Color palette swatches, stroke width presets, and reactive Undo/Redo command stacks.
  - Paper templates: Blank, Ruled, College Ruled, Dotted, Grid, Cornell Notes, Checklist.
- **PDF Annotation Subsystem:**
  - System file picker import via Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
  - Hardware-accelerated multi-page rendering via `PdfRenderer` with 10-page LRU cache.
  - Vector ink overlay on PDF pages and composite PDF export via `PdfDocument`.
- **Infinite Whiteboard Workspace:**
  - 2D non-paginated spatial canvas with pinch zoom and pan navigation.
  - Sticky notes, geometric shapes (rectangles, circles), text cards, and freehand ink.
- **Audio Notes & Synchronized Replay:**
  - High-fidelity AAC/M4A audio recording via `MediaRecorder`.
  - Millisecond stroke timestamp linkage and playback stroke highlighting.
- **Study Workspace & Spaced Repetition:**
  - Flashcard deck manager with card flip animation.
  - SuperMemo SM-2 algorithm implementation with `Again`, `Hard`, `Good`, and `Easy` ratings.
- **"Ask InkFlow" On-Device AI Assistant:**
  - 100% offline extractive summarization, concept extraction, and question-answering.
  - One-tap generation and export of flashcards into the Study Workspace.


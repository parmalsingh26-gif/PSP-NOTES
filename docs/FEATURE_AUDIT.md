# InkFlow Studio — Feature Audit & Root Cause Analysis

**Date:** 2026-11-10  
**Target:** Elimination of all dummy controls, fixing half-screen canvas sizing bug, enabling real multi-page document workflows, and delivering complete PDF import/annotation/export.

---

## 1. Feature Status Breakdown

| Feature | Audit Status | Evidence File Path | Root Cause & Action Required |
|---|---|---|---|
| **Canvas Viewport Sizing** | **BROKEN** | `feature/editor/NotebookEditorScreen.kt` | **Root Cause:** Page was rendered with fixed 595x842 points at $(0,0)$ in canvas coordinates without measuring viewport size or applying adaptive `fitScale`. On modern high-DPI screens and tablets, this occupied only a fraction of the screen, appearing as a "half-screen" or corner box.<br>**Action:** Wrap canvas in `BoxWithConstraints`, compute automatic `fitScale` (Fit Width / Fit Page) and center the page with proper margins and document coordinate transforms. |
| **Multi-Page PDF Import** | **BROKEN** | `feature/library/LibraryScreen.kt` & `DocumentRepository.kt` | **Root Cause:** When PDF was selected via SAF picker, `LibraryScreen.kt` called `repository.createPdfDocument(..., pageCount = 1)`. The actual page count was never read from the PDF! Only 1 page was generated in Room regardless of how many pages the PDF had.<br>**Action:** In `DocumentRepository.createPdfDocument`, open the copied PDF with `ParcelFileDescriptor` + `PdfRenderer`, read the real `renderer.pageCount`, read each page's actual width & height, and insert all pages into the database. |
| **Multi-Page Management UI** | **PARTIAL** | `feature/editor/NotebookEditorScreen.kt` | **Root Cause:** Users could only navigate with `<` and `>`, with no visual thumbnail strip, page reordering, page duplication, or bookmark manager.<br>**Action:** Implement a bottom/drawer **Page Manager Sheet** with thumbnail previews of all pages, Add Page Before/After, Duplicate Page, Delete Page, Bookmark toggle, and direct jump. |
| **Professional Tool Palette** | **PARTIAL** | `feature/editor/NotebookEditorScreen.kt` & `InkEngine.kt` | **Root Cause:** Toolbar only showed basic icons without tool property sheets (exact numeric width slider, opacity, custom HEX/RGB color picker, lasso transform, text box insertion, image insertion).<br>**Action:** Add full tool properties sheet, custom color picker dialog, numeric width controls, Palm Rejection toggle (stylus-only vs touch), Lasso selection & move/delete, Text Box insertion, and Image insertion. |
| **Lasso Selection & Transform** | **MISSING** | `feature/ink/InkEngine.kt` | **Root Cause:** Lasso was only an enum value; no lasso selection path computation, bounding box selection, moving selected strokes, or deleting selection.<br>**Action:** Implement polygon lasso hit-testing, selection handles, drag-to-move selected strokes, and delete/recolor selection. |
| **Text Box Tool** | **MISSING** | `domain/model/Page.kt` & `feature/editor/NotebookEditorScreen.kt` | **Root Cause:** In Notebook editor, only ink strokes were rendered. Text annotations were not supported on notebook pages.<br>**Action:** Add `PageTextObject` to page model and render editable, draggable text overlays. |
| **Shape Recognition on Hold** | **WORKING** | `feature/ink/ShapeRecognizer.kt` | Geometric circle, line, and rectangle snapping engine implemented. |
| **Full Backup & Restore** | **WORKING** | `data/backup/BackupManager.kt` | JSON archive export with SHA-256 verification and restore. |

---

## 2. Root Cause Details

### 2.1 The Half-Screen / Fixed 595x842 Sizing Bug
In `NotebookEditorScreen.kt`:
```kotlin
Canvas(
    modifier = Modifier.fillMaxSize().graphicsLayer {
        translationX = panOffset.x
        translationY = panOffset.y
        scaleX = zoomScale
        scaleY = zoomScale
    }
) {
    drawRect(color = Color.White, topLeft = Offset.Zero, size = Size(pageW, pageH))
    ...
}
```
Because `pageW = 595f` and `pageH = 842f` are in density-independent or fixed points, on a tablet with `1600 x 2560` pixels, 595x842 takes up less than a third of the screen!
**Solution:**
We measure the available container width `availableWidth` and height `availableHeight` using `BoxWithConstraints`.
We compute:
$$\text{baseScale} = \min\left(\frac{\text{availableWidth} - 48}{\text{pageWidth}}, \frac{\text{availableHeight} - 48}{\text{pageHeight}}\right)$$
We center the page at:
$$\text{pageOriginX} = \frac{\text{availableWidth} - \text{pageWidth} \cdot \text{baseScale}}{2}$$
$$\text{pageOriginY} = \frac{\text{availableHeight} - \text{pageHeight} \cdot \text{baseScale}}{2}$$
And adjust the input mapping accordingly so stylus and touch input always align 1:1 with visual ink.

### 2.2 PDF Multi-Page Import PageCount Bug
In `LibraryScreen.kt`:
```kotlin
val doc = repository.createPdfDocument(
    title = "Imported Document",
    uri = it,
    pageCount = 1 // <--- HARDCODED TO 1!
)
```
**Solution:**
In `DocumentRepository.createPdfDocument`:
1. Copy the PDF file to `PdfFileStore`.
2. Inspect the file with `PdfRenderer(ParcelFileDescriptor.open(file, MODE_READ_ONLY))`.
3. Retrieve actual `renderer.pageCount`.
4. Read actual page dimensions for each page:
   ```kotlin
   val page = renderer.openPage(i)
   val w = page.width.toFloat()
   val h = page.height.toFloat()
   page.close()
   ```
5. Insert entities for all $N$ pages!
6. Close renderer.


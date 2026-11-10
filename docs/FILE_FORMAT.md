# InkFlow Studio — File Format Specification

Version: `1.0.0`  
Standard Interchange Extension: `.inkflow` (ZIP archive containing `manifest.json`, `pages/`, `attachments/`) or standalone `inkflow_backup.json`.

---

## 1. Document Structure Schema (JSON)
```json
{
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "documentId": "uuid-v4",
  "title": "Physics 101 Lecture Notes",
  "type": "NOTEBOOK",
  "createdAt": 1794321000000,
  "updatedAt": 1794321500000,
  "pages": [
    {
      "pageId": "page-uuid-1",
      "index": 0,
      "width": 595.0,
      "height": 842.0,
      "template": "RULED",
      "strokes": [
        {
          "id": "stroke-uuid-1",
          "tool": "PEN",
          "color": "#1E293B",
          "width": 2.5,
          "opacity": 1.0,
          "audioTimestampMs": 1420,
          "points": [
            { "x": 102.5, "y": 80.2, "pressure": 0.65, "t": 1794321010000 },
            { "x": 104.1, "y": 82.0, "pressure": 0.72, "t": 1794321010016 }
          ]
        }
      ]
    }
  ]
}
```

---

## 2. Whiteboard Structure Schema
In `WHITEBOARD` mode, the document has a single boundless canvas holding both vector strokes and mixed-content canvas objects (sticky notes, shapes, text cards) positioned in 2D coordinate space $(x, y, w, h)$.


package app.inkflow.studio

import app.inkflow.studio.domain.model.Page
import app.inkflow.studio.domain.model.PageTemplate
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageInsertionTest {

    @Test
    fun testMidDocumentInsertionIndexCalculation() {
        // Simulates inserting a page into a 3-page document at index 1 (between page 0 and page 1)
        val initialPages = listOf(
            Page(id = "p0", documentId = "doc1", index = 0, template = PageTemplate.RULED),
            Page(id = "p1", documentId = "doc1", index = 1, template = PageTemplate.GRID),
            Page(id = "p2", documentId = "doc1", index = 2, template = PageTemplate.BLANK)
        )

        val insertAfterIndex = 0 // Insert after Page 0 (at position 1)
        val targetInsertIndex = insertAfterIndex + 1

        // Shift pages at or after targetInsertIndex
        val updatedPages = initialPages.map {
            if (it.index >= targetInsertIndex) it.copy(index = it.index + 1) else it
        }.toMutableList()

        // Insert new Cornell page at targetInsertIndex
        val newPage = Page(
            id = "new-p",
            documentId = "doc1",
            index = targetInsertIndex,
            template = PageTemplate.CORNELL
        )
        updatedPages.add(newPage)

        val sorted = updatedPages.sortedBy { it.index }
        assertEquals(4, sorted.size)
        assertEquals("p0", sorted[0].id)
        assertEquals(0, sorted[0].index)
        assertEquals(PageTemplate.RULED, sorted[0].template)

        assertEquals("new-p", sorted[1].id)
        assertEquals(1, sorted[1].index)
        assertEquals(PageTemplate.CORNELL, sorted[1].template)

        assertEquals("p1", sorted[2].id)
        assertEquals(2, sorted[2].index)

        assertEquals("p2", sorted[3].id)
        assertEquals(3, sorted[3].index)
    }

    @Test
    fun testInsertionAtBeginning() {
        val initialPages = listOf(
            Page(id = "p0", documentId = "doc1", index = 0, template = PageTemplate.RULED),
            Page(id = "p1", documentId = "doc1", index = 1, template = PageTemplate.GRID)
        )

        // Insert before page 0 (target index = 0)
        val targetInsertIndex = 0
        val shifted = initialPages.map { it.copy(index = it.index + 1) }.toMutableList()
        val newPage = Page(
            id = "first-p",
            documentId = "doc1",
            index = targetInsertIndex,
            template = PageTemplate.BLANK
        )
        shifted.add(newPage)

        val sorted = shifted.sortedBy { it.index }
        assertEquals("first-p", sorted[0].id)
        assertEquals(0, sorted[0].index)
        assertEquals("p0", sorted[1].id)
        assertEquals(1, sorted[1].index)
        assertEquals("p1", sorted[2].id)
        assertEquals(2, sorted[2].index)
    }

    @Test
    fun testAllPageTemplatesAvailable() {
        val templates = PageTemplate.entries
        assertTrue(templates.contains(PageTemplate.BLANK))
        assertTrue(templates.contains(PageTemplate.RULED))
        assertTrue(templates.contains(PageTemplate.COLLEGE_RULED))
        assertTrue(templates.contains(PageTemplate.DOTTED))
        assertTrue(templates.contains(PageTemplate.GRID))
        assertTrue(templates.contains(PageTemplate.CORNELL))
        assertTrue(templates.contains(PageTemplate.CHECKLIST))
        assertEquals(7, templates.size)
    }
}


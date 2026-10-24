package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.helpcenter.*

class HelpCenterTest {
    private val guides = HelpContent.guides

    @Test fun hasTheFourteenRequiredGuidesInOrder() {
        assertEquals(14, guides.size)
        assertEquals((1..14).toList(), guides.map { it.order })
        assertEquals(guides.size, guides.map { it.id }.toSet().size)
        assertEquals(guides.size, guides.map { it.title }.toSet().size)
    }

    @Test fun everyGuideHasAllRequiredSections() {
        for (g in guides) {
            assertTrue(g.id, g.purpose.isNotBlank()); assertTrue(g.id, g.prerequisites.isNotEmpty()); assertTrue(g.id, g.steps.size >= 3)
            assertTrue(g.id, g.expected.isNotBlank()); assertTrue(g.id, g.commonErrors.isNotEmpty()); assertTrue(g.id, g.fixes.isNotEmpty())
            assertTrue(g.id, (g.prerequisites + g.steps + g.commonErrors + g.fixes + g.expected + g.purpose + g.title).none { it.isBlank() })
        }
    }

    @Test fun everyGuideHasANavigationAction() = guides.forEach { assertNotNull(it.id, it.action) }

    @Test fun safetyFlagIsOffOnlyForPureReadingGuides() {
        assertEquals(setOf(HelpContent.Id.GETTING_STARTED, HelpContent.Id.PRIVACY), guides.filter { !it.requiresStationary }.map { it.id }.toSet())
        assertTrue(HelpSafety.STATIONARY_NOTICE.contains("dừng"))
    }

    @Test fun noGuideTellsTheUserToOperateThePhoneWhileRiding() {
        val bad = listOf("khi đang lái", "trong lúc lái", "vừa lái")
        for (g in guides) for (line in g.steps) if (bad.any { line.lowercase().contains(it) })
            assertTrue("Bài ${g.id}: '$line' phải ở dạng cấm", line.lowercase().contains("không"))
    }

    @Test fun guidesNeverClaimUnverifiedCapabilities() {
        val all = guides.joinToString("\n") { (it.steps + it.expected + it.purpose).joinToString(" ") }.lowercase()
        assertFalse(all.contains("hiển thị trên tft thành công")); assertFalse(all.contains("đã đọc được rpm"))
        val tft = HelpContent.find(HelpContent.Id.TFT_NAV)!!
        assertTrue(tft.steps.joinToString(" ").contains("KHÔNG HỖ TRỢ")); assertFalse(tft.manualComplete)
    }

    @Test fun searchIgnoresDiacriticsAndCase() {
        assertEquals(HelpSearch.search("ket noi").map { it.id }, HelpSearch.search("KẾT NỐI").map { it.id })
        assertTrue(HelpSearch.search("ket noi").isNotEmpty())
        assertEquals("duong", HelpSearch.normalize("Đường")); assertEquals("quyen rieng tu", HelpSearch.normalize("Quyền riêng tư"))
    }

    @Test fun searchRanksTitleFirstAndEmptyReturnsAllOrdered() {
        assertEquals(HelpContent.Id.TFT_NAV, HelpSearch.search("tft").first().id)
        assertEquals(HelpContent.Id.PRIVACY, HelpSearch.search("xoa du lieu").first().id)
        assertEquals((1..14).toList(), HelpSearch.search("   ").map { it.order })
    }

    @Test fun searchRequiresAllWordsAndUnknownGivesEmpty() {
        assertTrue(HelpSearch.search("zzzxyz").isEmpty())
        assertTrue(HelpSearch.search("tft zzzxyz").isEmpty())
        assertTrue(HelpSearch.search("quyen bluetooth").isNotEmpty())
    }

    @Test fun findReturnsNullForUnknownId() { assertNull(HelpContent.find("khong-co")); assertNotNull(HelpContent.find(HelpContent.Id.PERMISSIONS)) }
}

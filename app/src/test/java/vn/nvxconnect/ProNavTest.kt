package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.*

class ProNavTest {
    @Test fun startsAtHomeAndCannotGoBack() {
        val s = ProNavState()
        assertEquals(ProTab.OVERVIEW, s.tab); assertNull(s.page); assertFalse(s.canGoBack); assertEquals(s, s.back())
    }
    @Test fun backFromOtherTabGoesHome() {
        val s = ProNavState().selectTab(ProTab.DATA)
        assertTrue(s.canGoBack); assertEquals(ProTab.OVERVIEW, s.back().tab)
    }
    @Test fun openPageSwitchesToOwningTabAndBackPopsOnePage() {
        val s = ProNavState().open(ProPage.LAB)
        assertEquals(ProTab.CONNECT, s.tab); assertEquals(ProPage.LAB, s.page)
        val s2 = s.open(ProPage.AUTO)
        assertEquals(listOf(ProPage.LAB, ProPage.AUTO), s2.stack)
        assertEquals(ProPage.LAB, s2.back().page); assertNull(s2.back().back().page); assertEquals(ProTab.CONNECT, s2.back().back().tab)
        assertEquals(ProTab.OVERVIEW, s2.back().back().back().tab)
    }
    @Test fun reselectingTabResetsToRoot() {
        val s = ProNavState().open(ProPage.LAB).selectTab(ProTab.CONNECT)
        assertNull(s.page); assertEquals(ProTab.CONNECT, s.tab)
    }
    @Test fun eachTabKeepsItsOwnPageWhenSwitching() {
        val s = ProNavState().open(ProPage.LAB).selectTab(ProTab.LOG).selectTab(ProTab.CONNECT)
        assertEquals(ProPage.LAB, s.page)
    }
    @Test fun openingSamePageTwiceDoesNotStack() {
        assertEquals(1, ProNavState().open(ProPage.SETTINGS).open(ProPage.SETTINGS).stack.size)
    }
    @Test fun fullscreenCockpitIsClosedFirstByBack() {
        val s = ProNavState().enterCockpit()
        assertTrue(s.fullscreenCockpit); assertTrue(s.canGoBack)
        val b = s.back(); assertFalse(b.fullscreenCockpit); assertEquals(ProTab.DATA, b.tab); assertEquals(ProPage.COCKPIT, b.page)
        assertEquals(ProTab.DATA, b.back().tab); assertNull(b.back().page)
        assertEquals(ProTab.OVERVIEW, b.back().back().tab)
    }
    @Test fun anyTabSelectionLeavesFullscreen() {
        assertFalse(ProNavState().enterCockpit().selectTab(ProTab.OVERVIEW).fullscreenCockpit)
        assertFalse(ProNavState().enterCockpit().open(ProPage.LAB).fullscreenCockpit)
    }
    @Test fun helpTopicIsCarried() {
        val s = ProNavState().openHelp("x"); assertEquals(ProPage.HELP, s.page); assertEquals("x", s.helpTopic)
    }
    @Test fun everyPageBelongsToExactlyOneOfTheFourTabs() {
        assertTrue(ProPage.values().all { it.tab in ProTab.values() })
        assertEquals(ProTab.OVERVIEW, ProPage.SETTINGS.tab); assertEquals(ProTab.LOG, ProPage.DIAGNOSTICS.tab); assertEquals(ProTab.CONNECT, ProPage.LAB.tab)
        assertEquals(ProTab.CONNECT, ProPage.AUTO.tab); assertEquals(ProTab.DATA, ProPage.COCKPIT.tab); assertEquals(ProTab.DATA, ProPage.TELEMETRY.tab)
    }
    @Test fun exactlyFourBottomTabsInRequiredOrder() {
        assertEquals(listOf("Tổng quan", "Kết nối xe", "Dữ liệu xe", "Nhật ký kỹ thuật"), ProTab.values().map { it.title })
    }
    @Test fun everyOldScreenStillHasAHome() {
        // Không màn hình nào bị mất khi gộp 5 tab → 4 tab: tên trang cũ còn nguyên (trừ 2 trang đã thành gốc của tab).
        val names = ProPage.values().map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("LAB", "DIAGNOSTICS", "SETTINGS", "AUTO", "CAPABILITIES", "TFT", "HELP", "MAP", "VEHICLE", "COCKPIT", "TELEMETRY", "TRIPS")))
    }
    @Test fun connectionScreensLiveOnlyUnderTheConnectTab() {
        assertEquals(setOf(ProPage.AUTO, ProPage.LAB), ProPage.values().filter { it.tab == ProTab.CONNECT }.toSet())
    }
    @Test fun showRootJumpsToTabRootWithoutTouchingOtherTabs() {
        val s = ProNavState().open(ProPage.LAB).open(ProPage.AUTO).open(ProPage.DIAGNOSTICS)       // CONNECT:[LAB,AUTO]  LOG:[DIAGNOSTICS]
        val r = s.showRoot(ProTab.CONNECT)
        assertEquals(ProTab.CONNECT, r.tab); assertNull(r.page); assertFalse(r.fullscreenCockpit)
        assertEquals(ProPage.DIAGNOSTICS, r.selectTab(ProTab.LOG).page)                             // tab khác giữ nguyên
        assertEquals(ProTab.OVERVIEW, r.back().tab)
    }
    @Test fun switchingTabsKeepsEveryTabsOwnStackAndNeverResetsOthers() {
        val s = ProNavState().open(ProPage.LAB).open(ProPage.COCKPIT).open(ProPage.DIAGNOSTICS)
        assertEquals(ProTab.LOG, s.tab)
        val t = s.selectTab(ProTab.CONNECT)
        assertEquals(ProPage.LAB, t.page); assertEquals(ProPage.COCKPIT, t.selectTab(ProTab.DATA).page); assertEquals(ProPage.DIAGNOSTICS, t.selectTab(ProTab.LOG).page)
    }
}

class ProNavSaveTest {
    @Test fun encodeDecodeRoundTrip() {
        val s = ProNavState().open(ProPage.LAB).open(ProPage.AUTO).openHelp("topic").selectTab(ProTab.LOG).open(ProPage.DIAGNOSTICS)
        assertEquals(s, ProNavState.decode(s.encode()))
        val c = ProNavState().enterCockpit(); assertEquals(c, ProNavState.decode(c.encode()))
    }
    @Test fun corruptedInputFallsBackToDefault() {
        assertEquals(ProNavState(), ProNavState.decode(null)); assertEquals(ProNavState(), ProNavState.decode("garbage"))
        assertEquals(ProNavState(), ProNavState.decode("NOPE|0|;;;|"))
        assertEquals(ProNavState(), ProNavState.decode("HOME|0|;;;;|"))   // chuỗi đã lưu từ bản 5 tab: không được làm app lỗi
    }
    @Test fun pageInWrongTabIsDropped() {
        val d = ProNavState.decode("OVERVIEW|0|LAB;;;|")   // LAB thuộc CONNECT, không được nằm trong OVERVIEW
        assertNull(d.page); assertTrue(d.pages.values.all { it.isEmpty() })
    }
    @Test fun fullscreenOnlyKeptForCockpitTab() { assertFalse(ProNavState.decode("OVERVIEW|1|;;;|").fullscreenCockpit) }
    @Test fun fullscreenRestoredOnlyWhenCockpitPageIsOpen() {
        val c = ProNavState().enterCockpit(); assertTrue(ProNavState.decode(c.encode()).fullscreenCockpit)
        assertFalse(ProNavState.decode("DATA|1|;;TELEMETRY;|").fullscreenCockpit)
    }
}

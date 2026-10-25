package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.*

class ProNavTest {
    @Test fun startsAtHomeAndCannotGoBack() {
        val s = ProNavState()
        assertEquals(ProTab.HOME, s.tab); assertNull(s.page); assertFalse(s.canGoBack); assertEquals(s, s.back())
    }
    @Test fun backFromOtherTabGoesHome() {
        val s = ProNavState().selectTab(ProTab.TELEMETRY)
        assertTrue(s.canGoBack); assertEquals(ProTab.HOME, s.back().tab)
    }
    @Test fun openPageSwitchesToOwningTabAndBackPopsOnePage() {
        val s = ProNavState().open(ProPage.LAB)
        assertEquals(ProTab.TOOLS, s.tab); assertEquals(ProPage.LAB, s.page)
        val s2 = s.open(ProPage.HELP)
        assertEquals(listOf(ProPage.LAB, ProPage.HELP), s2.stack)
        assertEquals(ProPage.LAB, s2.back().page); assertNull(s2.back().back().page); assertEquals(ProTab.TOOLS, s2.back().back().tab)
        assertEquals(ProTab.HOME, s2.back().back().back().tab)
    }
    @Test fun reselectingTabResetsToRoot() {
        val s = ProNavState().open(ProPage.LAB).selectTab(ProTab.TOOLS)
        assertNull(s.page); assertEquals(ProTab.TOOLS, s.tab)
    }
    @Test fun eachTabKeepsItsOwnPageWhenSwitching() {
        val s = ProNavState().open(ProPage.LAB).selectTab(ProTab.RIDES).selectTab(ProTab.TOOLS)
        assertEquals(ProPage.LAB, s.page)
    }
    @Test fun openingSamePageTwiceDoesNotStack() {
        assertEquals(1, ProNavState().open(ProPage.SETTINGS).open(ProPage.SETTINGS).stack.size)
    }
    @Test fun fullscreenCockpitIsClosedFirstByBack() {
        val s = ProNavState().enterCockpit()
        assertTrue(s.fullscreenCockpit); assertTrue(s.canGoBack)
        val b = s.back(); assertFalse(b.fullscreenCockpit); assertEquals(ProTab.COCKPIT, b.tab)
        assertEquals(ProTab.HOME, b.back().tab)
    }
    @Test fun anyTabSelectionLeavesFullscreen() {
        assertFalse(ProNavState().enterCockpit().selectTab(ProTab.HOME).fullscreenCockpit)
        assertFalse(ProNavState().enterCockpit().open(ProPage.LAB).fullscreenCockpit)
    }
    @Test fun helpTopicIsCarried() {
        val s = ProNavState().openHelp("x"); assertEquals(ProPage.HELP, s.page); assertEquals("x", s.helpTopic)
    }
    @Test fun everyPageBelongsToAToolsOrRidesTab() {
        assertTrue(ProPage.values().all { it.tab == ProTab.TOOLS || it.tab == ProTab.RIDES })
        assertEquals(ProTab.TOOLS, ProPage.SETTINGS.tab); assertEquals(ProTab.TOOLS, ProPage.DIAGNOSTICS.tab); assertEquals(ProTab.TOOLS, ProPage.LAB.tab)
    }
    @Test fun exactlyFiveBottomTabsInRequiredOrder() {
        assertEquals(listOf("Trang chủ", "Cockpit", "Telemetry", "Hành trình", "Công cụ"), ProTab.values().map { it.title })
    }
}

class ProNavSaveTest {
    @Test fun encodeDecodeRoundTrip() {
        val s = ProNavState().open(ProPage.LAB).open(ProPage.HELP).openHelp("topic").selectTab(ProTab.RIDES).open(ProPage.MAP)
        assertEquals(s, ProNavState.decode(s.encode()))
        val c = ProNavState().enterCockpit(); assertEquals(c, ProNavState.decode(c.encode()))
    }
    @Test fun corruptedInputFallsBackToDefault() {
        assertEquals(ProNavState(), ProNavState.decode(null)); assertEquals(ProNavState(), ProNavState.decode("garbage"))
        assertEquals(ProNavState(), ProNavState.decode("NOPE|0|;;;;|"))
    }
    @Test fun pageInWrongTabIsDropped() {
        val d = ProNavState.decode("HOME|0|LAB;;;;|")   // LAB thuộc TOOLS, không được nằm trong HOME
        assertNull(d.page); assertTrue(d.pages.values.all { it.isEmpty() })
    }
    @Test fun fullscreenOnlyKeptForCockpitTab() { assertFalse(ProNavState.decode("HOME|1|;;;;|").fullscreenCockpit) }
}

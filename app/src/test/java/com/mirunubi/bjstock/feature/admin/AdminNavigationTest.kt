package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.SettingsMenu
import com.mirunubi.bjstock.ui.navigation.TabHubs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdminNavigationTest {
    @Test
    fun adminIsOneSettingsEntry_named운영감사() {
        val entries = SettingsMenu.sections.flatMap { it.entries }.filter { it.route == BJStockRoutes.ADMIN }
        assertEquals(1, entries.size)
        assertEquals("운영 · 감사", entries.single().title)
    }

    @Test
    fun adminDoesNotChangeThePrimaryTabs() {
        assertEquals(listOf("홈", "종목", "전략", "모의투자", "성과"), PrimaryTab.entries.map { it.label })
        assertFalse(PrimaryTab.entries.any { it.route == BJStockRoutes.ADMIN })
        assertFalse(PrimaryTab.entries.flatMap { TabHubs.forTab(it) }.any { it.route == BJStockRoutes.ADMIN })
    }

    @Test
    fun adminRoute_isNewAndNotALegacyRoute() {
        assertFalse(BJStockRoutes.ADMIN in BJStockRoutes.LEGACY)
        val others = PrimaryTab.entries.map { it.route } + BJStockRoutes.SETTINGS + BJStockRoutes.LEGACY
        assertFalse(BJStockRoutes.ADMIN in others)
    }
}

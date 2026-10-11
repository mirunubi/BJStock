package com.mirunubi.bjstock.ui.navigation

import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shell's back wiring on the real navigationevent dispatcher: the drawer handler lives on the activity
 * (root) dispatcher, NavHost and screen handlers on a child dispatcher enabled by [BackPriority.contentBackEnabled].
 * The drawer wins because the child is disabled, whatever the registration order.
 */
class BackPriorityDispatchTest {
    private object Info : NavigationEventInfo()

    private class Recorder(private val name: String, enabled: Boolean, private val log: MutableList<String>) :
        NavigationEventHandler<NavigationEventInfo>(Info, enabled) {
        override fun onBackCompleted() {
            log += name
        }
    }

    private class BackInput : NavigationEventInput() {
        fun back() = dispatchOnBackCompleted()
    }

    private class Wiring(drawerOpen: Boolean, layered: Boolean, drawerRegisteredFirst: Boolean) {
        val log = mutableListOf<String>()
        private val root = NavigationEventDispatcher()
        private val content = NavigationEventDispatcher(root)
        private val input = BackInput()

        init {
            root.addInput(input)
            content.isEnabled = BackPriority.contentBackEnabled(drawerOpen)
            val drawer = Recorder("drawer", BackPriority.drawerHandlerEnabled(drawerOpen), log)
            if (drawerRegisteredFirst) root.addHandler(drawer)
            content.addHandler(Recorder("navHost", true, log))
            content.addHandler(Recorder("layer", BackPriority.layerHandlerEnabled(layered, drawerOpen), log))
            if (!drawerRegisteredFirst) root.addHandler(drawer)
        }

        fun back(): List<String> {
            input.back()
            return log
        }
    }

    @Test
    fun drawerOpen_drawerWins_evenWhenRegisteredBeforeNavHostAndScreen() {
        assertEquals(listOf("drawer"), Wiring(drawerOpen = true, layered = true, drawerRegisteredFirst = true).back())
        assertEquals(listOf("drawer"), Wiring(drawerOpen = true, layered = false, drawerRegisteredFirst = true).back())
    }

    @Test
    fun drawerOpen_drawerWins_whenRegisteredAfterNavHostAndScreen() {
        assertEquals(listOf("drawer"), Wiring(drawerOpen = true, layered = true, drawerRegisteredFirst = false).back())
    }

    @Test
    fun drawerClosed_layerHandlesBack_beforeNavHost() {
        assertEquals(listOf("layer"), Wiring(drawerOpen = false, layered = true, drawerRegisteredFirst = false).back())
        assertEquals(listOf("layer"), Wiring(drawerOpen = false, layered = true, drawerRegisteredFirst = true).back())
    }

    @Test
    fun drawerClosed_noLayer_navHostHandlesBack() {
        assertEquals(listOf("navHost"), Wiring(drawerOpen = false, layered = false, drawerRegisteredFirst = false).back())
        assertEquals(listOf("navHost"), Wiring(drawerOpen = false, layered = false, drawerRegisteredFirst = true).back())
    }
}

package com.jasonz3157.weightjournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {
    @Test fun automaticSuccessPreservesTheHomeStamp() {
        for (previous in listOf(null, SyncResult.Success, SyncResult.Failure)) {
            val running = JournalUiState(syncActivity = SyncActivity.Automatic, syncResult = previous)
            val finished = running.finishSync("最近同步", SyncResult.Success)

            assertFalse(running.manuallySyncing)
            assertEquals(previous, finished.syncResult)
            assertEquals("最近同步", finished.syncStatus)
            assertNull(finished.syncActivity)
        }
    }

    @Test fun automaticFailureReplacesAnEarlierSuccess() {
        val running = JournalUiState(syncActivity = SyncActivity.Automatic, syncResult = SyncResult.Success)
        val finished = running.finishSync("连接超时", SyncResult.Failure)

        assertEquals(SyncResult.Failure, finished.syncResult)
        assertTrue(finished.syncError)
    }

    @Test fun manualSuccessReplacesAnEarlierFailure() {
        val running = JournalUiState(syncActivity = SyncActivity.Manual, syncResult = SyncResult.Failure)
        val finished = running.finishSync("最近同步", SyncResult.Success)

        assertTrue(running.manuallySyncing)
        assertEquals(SyncResult.Success, finished.syncResult)
        assertFalse(finished.manuallySyncing)
        assertFalse(finished.syncError)
    }

    @Test fun openingSyncShowsProgressThenPublishesItsResult() {
        val opening = JournalUiState(syncActivity = SyncActivity.Opening)

        assertTrue(opening.visibleSyncing)
        assertFalse(opening.manuallySyncing)
        assertEquals(SyncResult.Success, opening.finishSync("最近同步", SyncResult.Success).syncResult)
        val failed = opening.finishSync("连接超时", SyncResult.Failure)
        assertFalse(failed.visibleSyncing)
        assertEquals(SyncResult.Failure, failed.syncResult)
    }
}

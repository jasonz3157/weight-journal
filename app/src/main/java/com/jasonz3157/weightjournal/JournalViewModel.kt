package com.jasonz3157.weightjournal

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal enum class SyncResult { Success, Failure }
internal enum class SyncActivity { Automatic, Opening, Manual, SavingSettings }

internal data class JournalUiState(
    val journal: Journal = Journal(),
    val today: LocalDate = LocalDate.now(),
    val dav: DavSettings? = null,
    val syncActivity: SyncActivity? = null,
    val syncStatus: String = "仅本机",
    val syncError: Boolean = false,
    val syncResult: SyncResult? = null,
    val loading: Boolean = true,
    val storageError: String? = null,
) {
    val syncBusy: Boolean get() = syncActivity != null
    val manuallySyncing: Boolean get() = syncActivity == SyncActivity.Manual
    val visibleSyncing: Boolean get() = manuallySyncing || syncActivity == SyncActivity.Opening
    val canSync: Boolean get() = dav != null && storageError == null && syncActivity != SyncActivity.SavingSettings

    fun finishSync(status: String, result: SyncResult?): JournalUiState = copy(
        syncActivity = null,
        syncStatus = status,
        syncError = result == SyncResult.Failure,
        syncResult = if (visibleSyncing || result == SyncResult.Failure) result else syncResult,
    )
}

internal class JournalViewModel(application: Application) : AndroidViewModel(application) {
    private val store = JournalStore(application)
    private val client = WebDavClient()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(JournalUiState())
    val ui = state.asStateFlow()

    init {
        viewModelScope.launch {
            val (journal, dav) = withContext(Dispatchers.IO) {
                runCatching { store.readJournal() } to runCatching { store.readDav() }
            }
            state.update {
                it.copy(
                    journal = journal.getOrDefault(Journal()),
                    dav = dav.getOrNull(),
                    syncError = dav.isFailure,
                    syncStatus = when {
                        dav.isFailure -> "无法读取 WebDAV 设置，请重新填写。"
                        dav.getOrNull() != null -> "等待同步"
                        else -> "仅本机"
                    },
                    storageError = if (journal.isFailure) "本机日记读取失败。" else null,
                    loading = false,
                )
            }
            for (request in requests) {
                val snapshot = state.value
                val settings = snapshot.dav ?: continue
                if (!snapshot.canSync) continue
                state.update {
                    it.copy(syncActivity = it.syncActivity ?: SyncActivity.Automatic, syncStatus = "正在同步…", syncError = false)
                }
                try {
                    val received = withContext(Dispatchers.IO) {
                        client.exchange(settings) { state.value.journal }
                    }
                    updateJournal(state.value.journal.merge(received))
                    val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                    state.update { current ->
                        val result = SyncResult.Success.takeIf { received == current.journal }
                        current.finishSync("最近同步 $time", result)
                    }
                } catch (error: CancellationException) {
                    state.update { it.copy(syncActivity = null) }
                    throw error
                } catch (error: Exception) {
                    val message = when (error) {
                        is SocketTimeoutException, is java.io.InterruptedIOException -> "WebDAV 连接超时。"
                        is DavException -> error.message ?: "WebDAV 同步失败。"
                        else -> "无法连接 WebDAV，请检查地址和网络。"
                    }
                    state.update { it.finishSync(message, SyncResult.Failure) }
                }
            }
        }
    }

    fun refreshToday() = state.update { it.copy(today = LocalDate.now()) }

    fun setValue(key: String, value: Double?): String? {
        val current = state.value
        if (current.loading) return "正在加载日记，请稍候。"
        current.storageError?.let { return it }
        return try {
            updateJournal(current.journal.set(key, value))
            syncInBackground()
            null
        } catch (error: IllegalStateException) {
            error.message ?: "无法保存记录。"
        }
    }

    private fun updateJournal(journal: Journal) {
        if (journal === state.value.journal) return
        store.saveJournal(journal)
        state.update { it.copy(journal = journal) }
    }

    fun syncInBackground() {
        if (state.value.dav != null) requests.trySend(Unit)
    }

    @MainThread
    fun syncOnOpen() = syncWithStatus(SyncActivity.Opening)

    @MainThread
    fun syncManually() = syncWithStatus(SyncActivity.Manual)

    private fun syncWithStatus(activity: SyncActivity) {
        val current = state.value
        if (!current.canSync || current.manuallySyncing || current.syncActivity == activity) return
        state.update { it.copy(syncActivity = activity) }
        // 已有同步时显示这次同步的进度, 避免发送重复请求.
        if (!current.syncBusy) requests.trySend(Unit)
    }

    fun configure(settings: DavSettings?) {
        if (state.value.syncBusy || state.value.loading) return
        state.update { it.copy(syncActivity = SyncActivity.SavingSettings, syncError = false, syncStatus = "正在保存设置…") }
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { store.saveDav(settings) }
                state.update {
                    it.copy(
                        dav = settings,
                        syncStatus = if (settings == null) "仅本机" else "等待同步",
                        syncResult = null,
                    )
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                state.update { it.copy(syncError = true, syncStatus = "无法保存 WebDAV 设置，请重试。") }
                false
            } finally {
                state.update { it.copy(syncActivity = null) }
            }
            if (saved) syncManually()
        }
    }
}

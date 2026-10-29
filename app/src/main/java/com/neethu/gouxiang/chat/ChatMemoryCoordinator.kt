package com.neethu.gouxiang.chat

import android.util.Log
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryTurn
import com.neethu.gouxiang.data.PendingMemoryWindowEntity
import com.neethu.gouxiang.data.deserializeWindow
import com.neethu.gouxiang.data.serializeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 待提炼窗口的持久化缝(对齐 ChatOrchestrator.DaoAccess 抽象惯例:
 * Repo 实现走 pending_memory_window 单行表,单测注入内存 fake)。
 */
interface MemoryWindowStore {
    suspend fun loadPayload(): String?
    suspend fun save(payload: String)
}

/**
 * 记忆协调器(F3 记忆系统的写路径中枢,移植自 AIAvatar-SDK memory/
 * MemoryCoordinator.kt 的窗口/阈值/防抖骨架,收敛为单通道+画像):
 *
 * - **窗口**:每个完整回合收尾把 (user, assistant 合并文本) 追加进待提炼窗口,
 *   持久化到 [MemoryWindowStore](force-stop 不丢、跨进程连续计数);
 *   被打断/失败的回合走不到 [onTurnCompleted]。
 * - **双触发**:窗口轮次 ≥ [everyTurns] 或累计字符 ≥ [minChars] 才发请求;
 *   触发时窗口累计 <12 字 → 静默清空不烧请求(连续寒暄凑满轮次的退化情形)。
 * - **fire-and-forget**:请求在 [scope] 后台跑,失败只 Log、窗口保留下次重试
 *   (硬顶 20 轮丢最旧);永不影响对话。
 * - **画像与总结**:达标时先画像后总结(03 §3.2c 顺序约定:总结=经历层,
 *   画像=身份层);任一成功即清本次快照覆盖的前缀(失败通道靠下次的现有
 *   事件/画像清单在 LLM 层去重重试)。
 * - **防抖**:任一通道在飞则本轮回调只标记不重入(mutex 内判定)。
 * - **手动总结**:记忆页「立即总结」按钮 → [summarizeNow](不经过窗口阈值)。
 */
class ChatMemoryCoordinator(
    private val source: MemorySource,
    private val book: MemoryBook,
    private val extractor: MemoryExtractor,
    private val scope: CoroutineScope,
    private val windowStore: MemoryWindowStore,
    everyTurns: Int = DEFAULT_EVERY_TURNS,
    minChars: Int = DEFAULT_MIN_CHARS,
) {

    /** 总结触发阈值:窗口轮次(≥1)。 */
    @Volatile var everyTurns: Int = everyTurns.coerceAtLeast(1)
        private set

    /** 总结触发阈值:窗口累计字符(≥0)。 */
    @Volatile var minChars: Int = minChars.coerceAtLeast(0)
        private set

    /** 自动总结开关(页面静默后台总结;手动「立即总结」不受它约束)。 */
    @Volatile var autoSummarize: Boolean = true

    /** 总结进行中(UI「记忆生成中」态)。 */
    private val _generating = kotlinx.coroutines.flow.MutableStateFlow(false)
    val generating = _generating.asStateFlow()

    private val mutex = Mutex()
    private var window: MutableList<MemoryTurn> = mutableListOf()
    private var windowLoaded = false
    private var working = false

    private companion object {
        const val TAG = "ChatMemoryCoord"
        const val DEFAULT_EVERY_TURNS = 5
        const val DEFAULT_MIN_CHARS = 600

        /** 静默门:触发条件满足但窗口累计 <12 字 → 清空不请求。 */
        const val SILENCE_GATE_CHARS = 12

        /** 窗口硬顶(失败保留语义下防无限膨胀):超顶丢最旧。 */
        const val WINDOW_MAX_TURNS = 20
    }

    // ── 注入(读路径)────────────────────────────────────────────────

    /** 本轮请求的 [记忆] 块;空库 → null(零注入)。 */
    suspend fun lineFor(characterId: Long, query: String): String? {
        val all = book.all(characterId)
        if (all.isEmpty()) return null
        return MemoryInjection.block(characterName(characterId), all, query)
    }

    private suspend fun characterName(characterId: Long): String =
        book.characterName(characterId)

    // ── 写路径:回合收尾 ─────────────────────────────────────────────

    /**
     * 回合收尾钩子(ChatOrchestrator 在回复全部落库后调用;取消/异常路径
     * 天然跳过):窗口追加(持久化)→ 阈值判定 → 达标才后台总结。
     */
    suspend fun onTurnCompleted(character: CharacterEntity, userText: String, assistantText: String) {
        if (userText.isBlank() && assistantText.isBlank()) return
        ensureWindowLoaded()
        var shouldRun = false
        mutex.withLock {
            window.add(MemoryTurn(userText, assistantText))
            while (window.size > WINDOW_MAX_TURNS) window.removeAt(0)
            persistLocked()
            shouldRun = shouldTriggerLocked()
            if (shouldRun && totalCharsLocked() < SILENCE_GATE_CHARS) {
                Log.i(TAG, "silence gate: window < $SILENCE_GATE_CHARS chars, dropping without a request")
                window.clear()
                persistLocked()
                shouldRun = false
            }
        }
        if (shouldRun && autoSummarize) startRun(character)
    }

    private fun shouldTriggerLocked(): Boolean =
        window.size >= everyTurns.coerceAtLeast(1) || totalCharsLocked() >= minChars.coerceAtLeast(0)

    private fun totalCharsLocked(): Int =
        window.sumOf { it.user.length + it.assistant.length }

    // ── 总结执行(自动与手动共用)───────────────────────────────────

    /**
     * 后台跑一次「画像判定 + 事件总结」。任一通道成功即清本次快照覆盖的
     * 前缀轮次(两通道共用窗口快照,失败通道靠清单去重在下次重试)。
     */
    private fun startRun(character: CharacterEntity) {
        synchronized(this) {
            if (working) return
            working = true
        }
        _generating.value = true
        scope.launch {
            try {
                val (snapshot, snapshotSize) = mutex.withLock { window.toList() to window.size }
                if (snapshot.isEmpty()) return@launch
                var profileOk = false
                var summaryOk = false
                try {
                    profileOk = runCatching { extractor.extractProfile(snapshot, character) }
                        .onFailure { Log.w(TAG, "profile judge failed: ${it.message}") }
                        .getOrDefault(false)
                    summaryOk = runCatching { extractor.summarize(snapshot, character) }
                        .onFailure { Log.w(TAG, "event summarize failed: ${it.message}") }
                        .getOrDefault(false)
                } finally {
                    if (profileOk || summaryOk) {
                        mutex.withLock {
                            repeat(snapshotSize) { if (window.isNotEmpty()) window.removeAt(0) }
                            persistLocked()
                        }
                    }
                }
                Log.i(TAG, "memory run done: profile=$profileOk summary=$summaryOk cleared=${profileOk || summaryOk}")
            } finally {
                synchronized(this@ChatMemoryCoordinator) { working = false }
                _generating.value = false
            }
        }
    }

    /**
     * 手动「立即总结」(记忆页按钮):不经过窗口阈值,把当前窗口(或空窗口时
     * 调用方给的最近对话回合)直接交给总结通道。无内容可总结 → 返回 false。
     */
    suspend fun summarizeNow(character: CharacterEntity, recentTurns: List<MemoryTurn>): Boolean {
        ensureWindowLoaded()
        val snapshot = mutex.withLock { window.toList() }.ifEmpty { recentTurns }
        if (snapshot.isEmpty()) return false
        var ok = false
        try {
            _generating.value = true
            ok = runCatching { extractor.summarize(snapshot, character) }
                .onFailure { Log.w(TAG, "manual summarize failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) {
                // 手动成功清全窗口(该内容已固化进事件记忆)
                mutex.withLock {
                    window.clear()
                    persistLocked()
                }
            }
        } finally {
            _generating.value = false
        }
        return ok
    }

    /** 调试行:窗口状态行。 */
    suspend fun windowLine(): String {
        ensureWindowLoaded()
        return mutex.withLock {
            "window=${window.size} turns/${totalCharsLocked()}chars trigger=turns>=$everyTurns||chars>=$minChars"
        }
    }

    // ── 窗口持久化 ────────────────────────────────────────────────────

    private suspend fun ensureWindowLoaded() {
        if (windowLoaded) return
        mutex.withLock {
            if (windowLoaded) return
            val payload = windowStore.loadPayload()
            window = payload?.let { deserializeWindow(it).toMutableList() } ?: mutableListOf()
            windowLoaded = true
            if (window.isNotEmpty()) Log.i(TAG, "restored pending window: ${window.size} turn(s)")
        }
    }

    private suspend fun persistLocked() {
        windowStore.save(serializeWindow(window.toList()))
    }

    /** PendingMemoryWindowEntity 落库辅助(Repo 侧 MemoryWindowStore 实现共用)。 */
    object WindowCodec {
        fun entity(payload: String) = PendingMemoryWindowEntity(payload = payload)
        fun toTurns(payload: String) = deserializeWindow(payload)
    }
}

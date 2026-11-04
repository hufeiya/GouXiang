package com.neethu.gouxiang.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import com.neethu.gouxiang.chat.DialogMode
import com.neethu.gouxiang.chat.MemoryCategories
import com.neethu.gouxiang.chat.ReplyLength
import com.neethu.gouxiang.chat.StatusRule
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.protocol.CardPayload
import com.neethu.gouxiang.protocol.MultiBubbleParser
import com.neethu.gouxiang.protocol.ProactiveMessages
import com.neethu.gouxiang.protocol.toProtocolLine
import com.neethu.gouxiang.ui.MainViewModel
import com.neethu.gouxiang.ui.nav.BottomTab

/**
 * AI 原生调试执行器(docs/ai-debug-intents.md §4.3):`when(command.name)` 大
 * 分发,每条命令返回一行 String 结果;异常统一 catch 后打 FAIL。
 *
 * 日志契约(与参照实现逐字对齐):
 * - 成功 `Log.i(AIDebug, "OK <cmd>: <结果>")`(多行结果允许 OK 段内换行);
 * - 失败 `Log.e(AIDebug, "FAIL <cmd>: <原因>")`;
 * - 每条命令**恰好一行 OK 或一行 FAIL**,`help` 特殊:命令表打完回 `OK help: printed`。
 *
 * 红线(docs/ai-debug-intents.md §9):密钥绝不落日志(`ai_config` 只出掩码
 * 长度);任何命令不许 `Thread.sleep` 等待;未实现命令回 FAIL 不许静默。
 */
internal suspend fun executeAiCommand(
    context: Context,
    vm: MainViewModel,
    command: AiDebugCommand,
) {
    val result: String = try {
        when (command.name) {
            "help" -> {
                Log.i(AI_LOG_TAG, aiDebugHelp())
                "printed"
            }
            "state" -> cmdState(context, vm)
            "msgs" -> cmdMsgs(vm, command)
            "contexts" -> cmdContexts(vm)
            "open_chat" -> cmdOpenChat(context, vm, command)
            "open_tab" -> cmdOpenTab(vm, command)
            "ai_config" -> cmdAiConfig(context)
            "send_chat" -> cmdSendChat(vm, command)
            "send_card" -> cmdSendCard(vm, command)
            "parse" -> cmdParse(command)
            "retry" -> cmdRetry(vm)
            "rewind" -> cmdRewind(vm, command)
            "proactive" -> cmdProactive(vm)
            "proactive_fire" -> cmdProactiveFire(vm, command)
            "proactive_backdate" -> cmdProactiveBackdate(vm, command)
            "proactive_scan" -> vm.debugProactiveScan()
            "proactive_state" -> cmdProactiveState(vm)
            "memory_list" -> cmdMemoryList(context, vm, command)
            "memory_stats" -> cmdMemoryStats(context, vm)
            "memory_forget" -> cmdMemoryForget(context, vm, command)
            "memory_summarize" -> cmdMemorySummarize(vm)
            "status" -> cmdStatus(vm)
            "status_now" -> cmdStatusNow(vm)
            "status_list" -> cmdStatusList(vm, command)
            "status_inject" -> cmdStatusInject(vm, command)
            "status_refresh" -> vm.debugStatusRefresh()
            "set_style" -> cmdSetStyle(vm, command)
            "set_length" -> cmdSetLength(vm, command)
            "tasks" -> cmdTasks(context)
            "task_retry" -> cmdTaskRetry(context, command)
            "task_cancel" -> cmdTaskCancel(context, command)
            "open_tasks" -> {
                vm.openTaskHistory()
                "task history opened (verify rows on screen or via tasks)"
            }
            "close_tasks" -> {
                vm.closeTaskHistory()
                "task history closed"
            }
            "chars" -> cmdChars(context)
            "char_create" -> cmdCharCreate(context, vm, command, create = true)
            "char_edit" -> cmdCharCreate(context, vm, command, create = false)
            "avatar_retry" -> cmdAvatarRetry(context, vm, command)
            "sidestory_keys" -> vm.debugSidestoryKeys()
            "sidestory_list" -> cmdSidestoryList(vm, command)
            "sidestory_generate" -> cmdSidestoryGenerate(vm, command)
            "sidestory_delete" -> cmdSidestoryDelete(vm, command)
            "sidestory_retry" -> cmdSidestoryRetry(vm, command)
            "open_sidestory" -> cmdOpenSidestory(vm)
            "close_sidestory" -> {
                vm.closeSidestory()
                "sidestory screen closed"
            }
            "handbook_generate" -> vm.debugGenerateChatDerivative("handbook", command.arg)
            "novel_generate" -> vm.debugGenerateChatDerivative("novel", command.arg)
            "photo_generate" -> vm.debugGenerateChatDerivative("photo", command.arg)
            "derivative_list" -> cmdDerivativeList(vm, command)
            "derivative_retry" -> cmdDerivativeRetry(vm, command)
            "derivative_delete" -> cmdDerivativeDelete(vm, command)
            "open_handbook" -> cmdOpenDerivative(vm, "handbook")
            "close_handbook" -> {
                vm.closeHandbook()
                "handbook screen closed"
            }
            "open_novel" -> cmdOpenDerivative(vm, "novel")
            "close_novel" -> {
                vm.closeNovel()
                "novel screen closed"
            }
            "open_photo" -> cmdOpenDerivative(vm, "photo")
            "close_photo" -> {
                vm.closePhoto()
                "photo screen closed"
            }
            "innervoice_switch" -> cmdInnerVoiceSwitch(vm, command)
            "innervoice_delete" -> cmdInnerVoiceDelete(vm, command)
            "innervoice_list" -> cmdInnerVoiceList(context, vm)
            "content_state" -> cmdContentState(context, vm)
            "content_list" -> cmdContentList(context, vm)
            "content_auto" -> cmdContentAuto(vm, command)
            "content_open" -> cmdContentOpen(context, vm, command)
            "moments" -> cmdMoments(context, vm, command)
            "moments_list" -> cmdMomentsList(context)
            "moments_perm" -> cmdMomentsPerm(context, vm, command)
            else -> throw IllegalArgumentException(
                "unknown command, send ai_cmd=help (see docs/ai-debug-intents.md)",
            )
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // M0 收尾(热启动修复):取消必须上抛——吞掉会把消费循环留在「已取消却
        // 继续跑」的破损态,后续 receive 立即关闭,通道里排队的命令永远不被执行。
        throw cancelled
    } catch (e: Exception) {
        Log.e(AI_LOG_TAG, "FAIL ${command.name}: ${e.message}")
        return
    }
    Log.i(AI_LOG_TAG, "OK ${command.name}: $result")
}

// ── 参数解析辅助 ─────────────────────────────────────────────────────

/** 缺失即抛(FAIL 行带用法提示)。 */
private fun requireArg(command: AiDebugCommand): String =
    command.arg?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("missing ai_arg — usage in docs/ai-debug-intents.md §5")

private inline fun <reified T : Enum<T>> resolveEnum(name: String, options: String): T =
    try {
        enumValueOf<T>(name.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("No $options matches '$name'. Send ai_cmd=help to see options.")
    }

private fun Float?.requireLong(what: String): Long =
    this?.toLong() ?: throw IllegalArgumentException("missing ai_x ($what)")

/** 密钥掩码:只出 `已配[len=N]` 或 `未配`,绝不回显内容。 */
private fun keyMask(value: String): String =
    if (value.isBlank()) "未配" else "已配[len=${value.length}]"

// ── 5.1 通用 / 状态 ─────────────────────────────────────────────────

private suspend fun cmdState(context: Context, vm: MainViewModel): String {
    val chatId = vm.debugConversationId
    val chatUi = vm.chatUi.value
    val style = vm.chatStyle.value
    val unread = vm.conversations.value.sumOf { it.conversation.unread }
    val cfg = com.neethu.gouxiang.ai.config.AiConfigStore.load(context)
    val chatPart = if (chatId == null) {
        "chat=closed"
    } else {
        "chat(id=$chatId phone=${vm.debugPhoneMode} 角色=${chatUi.character?.name ?: "?"}" +
            " sending=${chatUi.sending} failedTurn=${chatUi.failedTurn != null})"
    }
    return "tab=${vm.selectedTab.value.name.lowercase()} $chatPart " +
        "style=${style.mode.name.lowercase()}+${style.length.name.lowercase()} " +
        "ai(provider=${cfg.provider.name.lowercase()} llmModel=${cfg.resolvedLlmModel()} " +
        "key=${keyMask(cfg.effectiveApiKey(cfg.provider))}) unread=$unread"
}

private suspend fun cmdMsgs(vm: MainViewModel, command: AiDebugCommand): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val limit = (command.x?.toInt() ?: 20).coerceIn(1, 100)
    val messages = vm.debugMessages(conversationId, limit).takeLast(limit)
    if (messages.isEmpty()) return "(no messages in conversation $conversationId)"
    return messages.joinToString("\n") { m ->
        "${m.id} ${m.turnId} ${m.role} ${m.kind} ${m.content.take(80)}"
    }
}

private suspend fun cmdContexts(vm: MainViewModel): String {
    val rows = vm.conversations.value
    if (rows.isEmpty()) return "(no conversations)"
    return rows.joinToString("\n") { row ->
        val c = row.conversation
        val name = row.character?.name ?: "?"
        "${c.id} ${c.mode} $name 未读${c.unread} 「${c.lastSummary.take(20)}」 " +
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(c.lastAt))
    }
}

private suspend fun cmdOpenChat(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val phoneMode = command.x?.toInt() == 1
    val conversationId = arg.toLongOrNull()?.let { id ->
        // id 直传:校验存在
        vm.conversations.value.firstOrNull { it.conversation.id == id }?.conversation?.id
            ?: throw IllegalArgumentException("No conversation matches id $id. Send ai_cmd=contexts to see options.")
    } ?: run {
        // 按角色名(经 CharacterDao.byName 同款查询)解析其首个会话
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
        val character = repo.characterByName(arg)
            ?: throw IllegalArgumentException(
                "No character matches '$arg'. Send ai_cmd=contexts to see options.",
            )
        vm.conversations.value.firstOrNull { it.conversation.characterId == character.id }?.conversation?.id
            ?: throw IllegalStateException("character '${character.name}' has no conversation yet")
    }
    vm.openChat(conversationId, phoneMode)
    return "opened conversation $conversationId (phone=$phoneMode)"
}

private suspend fun cmdOpenTab(vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val tab = when (arg.lowercase()) {
        "home" -> BottomTab.HOME
        "messages" -> BottomTab.MESSAGES
        "profile" -> BottomTab.PROFILE
        "create" -> {
            // C 工作包:创作 AI+ tab(角色列表 + 新建入口)
            vm.selectedTab.value = BottomTab.CREATE
            return "tab=create"
        }
        "discover" -> {
            // H 工作包:发现 tab(H4 本地角色浏览/搜索/历史 + 朋友圈入口)
            vm.selectedTab.value = BottomTab.DISCOVER
            return "tab=discover"
        }
        else -> throw IllegalArgumentException(
            "No tab matches '$arg'. Options: home | messages | profile | create | discover.",
        )
    }
    vm.selectedTab.value = tab
    return "tab=${tab.name.lowercase()}"
}

private suspend fun cmdAiConfig(context: Context): String {
    val cfg = com.neethu.gouxiang.ai.config.AiConfigStore.load(context)
    val provider = cfg.provider
    fun line(label: String, value: String) = "$label=$value"
    return listOf(
        "provider=${provider.name.lowercase()}",
        "llmModel=${cfg.resolvedLlmModel()}",
        "baseUrl=${cfg.effectiveBaseUrl()}",
        "ttsModel=${cfg.ttsModel.ifBlank { "(默认)" }} voice=${cfg.ttsVoice.ifBlank { "(默认)" }}",
        "asrModel=${cfg.asrModel.ifBlank { "(自动)" }}",
        "siliconflow=${keyMask(cfg.apiKeySiliconflow)}",
        "volcano=${keyMask(cfg.apiKeyVolcano)}",
        "volcanoTts=${keyMask(cfg.apiKeyVolcanoTts)}",
        "openrouter=${keyMask(cfg.apiKeyOpenrouter)}",
        "deepseek=${keyMask(cfg.apiKeyDeepseek)}",
        "bailian=${keyMask(cfg.apiKeyBailian)}",
        "grok=${keyMask(cfg.apiKeyGrok)}",
        "custom=${keyMask(cfg.apiKeyCustom)} baseUrl=${cfg.customBaseUrl.ifBlank { "-" }}",
        "configured=${cfg.isConfigured}",
    ).joinToString(" ")
}

// ── 5.2 对话链路 ─────────────────────────────────────────────────────

private suspend fun cmdSendChat(vm: MainViewModel, command: AiDebugCommand): String {
    val text = requireArg(command)
    if (vm.debugConversationId == null) {
        throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    }
    // VM.sendChatMessage 在角色未就绪时静默 no-op —— 这里显式 FAIL 提示重发,
    // 防「命令回 OK 但消息被丢」的假阳性
    if (vm.chatUi.value.character == null) {
        throw IllegalStateException("chat still loading (character not ready) — resend in a second")
    }
    // VM.sendChatMessage 在上一回合进行中会静默丢弃新消息(防重入)——这里显式
    // FAIL,让热启动重发的命令不会「OK 却没执行」(M0 收尾工作包 4)
    if (vm.chatUi.value.sending) {
        throw IllegalStateException(
            "chat busy — previous turn still streaming; poll msgs/state and resend later",
        )
    }
    // send_chat 语义 = 「入队即 OK」:内部协程跑 LLM 流不阻塞调用方;
    // 回合结果用 msgs/state 轮询确认(与 AIAvatar 相同)
    vm.sendChatMessage(text, phoneMode = vm.debugPhoneMode)
    return "queued \"$text\" (turn events will appear in msgs)"
}

private suspend fun cmdSendCard(vm: MainViewModel, command: AiDebugCommand): String {
    val desc = requireArg(command)
    if (vm.debugConversationId == null) {
        throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    }
    // sendCard 只在手机聊天形态生效(与 UI 入口同守卫):VM.sendCard 静默 no-op
    // 会让人误判成功,这里显式 FAIL 提示
    if (!vm.debugPhoneMode) {
        throw IllegalStateException(
            "send_card needs the phone chat form — reopen with ai_cmd=open_chat ai_x=1 (phone mode)",
        )
    }
    // 参数格式 "<类型> [标题|备注]":解码成 CardPayload 后走 sendCard 同一条
    // 编码入口(payload.toProtocolLine() → sendChatMessage),入库 kind 必须 =card
    val body = desc.substringBefore('|').trim()
    val note = desc.substringAfter('|', "").trim()
    val kindEntry = com.neethu.gouxiang.protocol.PhoneCardKind.entries.firstOrNull {
        body.startsWith(it.label)
    } ?: throw IllegalArgumentException(
        "No card type matches '${body.take(8)}'. Options: ${PhoneCardKindLabels.joinToString("|")}.",
    )
    val payload = CardPayload(kind = kindEntry, title = body.removePrefix(kindEntry.label).trim(), note = note)
    // F2 金额校验(与 UI/VM 同一 sendable 口径):转账/代付空金额被 VM 兜底
    // 静默拦截会误判成功,这里显式 FAIL
    if (!payload.sendable) {
        throw IllegalArgumentException(
            "${kindEntry.label} requires a non-blank amount (e.g. send_card 转账 520|备注)",
        )
    }
    val line = with(payload) { toProtocolLine() }
    vm.sendCard(payload)
    return "queued card $line (verify kind=card via msgs)"
}

private val PhoneCardKindLabels: List<String> =
    com.neethu.gouxiang.protocol.PhoneCardKind.entries.map { it.label }

/**
 * 纯解析探针(不落库不请求 LLM):`MultiBubbleParser.parse` 后逐段回显
 * type=xxx 内容前20字,验证段型识别/白名单/卡片行。
 */
private suspend fun cmdParse(command: AiDebugCommand): String {
    val raw = requireArg(command)
    val reply = MultiBubbleParser.parse(raw)
    val parts = reply.bubbles.map { b ->
        "type=${b.kind.name.lowercase()} content=${b.text.take(20)}"
    } + listOfNotNull(reply.proactivity?.let { "type=proactivity content=${it.text.take(20)}" })
    if (parts.isEmpty()) return "no segments parsed from ${raw.take(40)}"
    return parts.joinToString("\n")
}

private suspend fun cmdRetry(vm: MainViewModel): String {
    if (vm.debugConversationId == null) {
        throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    }
    if (vm.chatUi.value.failedTurn == null) {
        throw IllegalStateException("no failed turn")
    }
    vm.retryFailedTurn()
    return "retry queued for \"${vm.chatUi.value.failedTurn?.userText ?: ""}\" (poll msgs to confirm)"
}

private suspend fun cmdRewind(vm: MainViewModel, command: AiDebugCommand): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val messageId = if (command.arg?.lowercase() == "last") {
        // last = 该会话最新一条消息(含 user/assistant)
        vm.debugMessages(conversationId, 1).lastOrNull()?.id
            ?: throw IllegalStateException("conversation is empty, nothing to rewind")
    } else {
        command.x?.toLong()
            ?: throw IllegalArgumentException(
                "missing target — pass ai_x=<messageId> (see msgs) or ai_arg=last",
            )
    }
    // 回溯后 memory_list 里晚于回溯点的 event 记忆应消失(缺口 A15 验收口)
    vm.rewindToMessage(messageId)
    return "rewound to message $messageId (deleted it and everything after; compare memory_list)"
}

// ── 5.3 主动消息 ─────────────────────────────────────────────────────

private suspend fun cmdProactive(vm: MainViewModel): String = vm.debugProactiveDecision()

private suspend fun cmdProactiveFire(vm: MainViewModel, command: AiDebugCommand): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val kind = when (requireArg(command).lowercase()) {
        "hello" -> ProactiveMessages.Kind.FIRST_HELLO
        "check_in" -> ProactiveMessages.Kind.CHECK_IN
        "share" -> ProactiveMessages.Kind.DAILY_SHARE
        "offline" -> {
            // 走 simulateOfflineReturn 语义:按模拟离线时长(ai_x,默认 2h)触发 CHECK_IN
            val offlineMs = command.x?.toLong()
                ?: ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS
            if (vm.chatUi.value.character == null) {
                throw IllegalStateException("chat still loading (character not ready) — resend in a second")
            }
            vm.sendProactiveMessages(ProactiveMessages.Kind.CHECK_IN, offlineMs = offlineMs)
            return "fired check_in with simulated offline=${ProactiveMessages.offlineMsDescription(offlineMs)} (poll msgs)"
        }
        else -> throw IllegalArgumentException(
            "No kind matches '${command.arg}'. Options: hello | check_in | share | offline.",
        )
    }
    if (vm.chatUi.value.character == null) {
        throw IllegalStateException("chat still loading (character not ready) — resend in a second")
    }
    vm.sendProactiveMessages(kind, offlineMs = null)
    return "fired ${kind.name.lowercase()} (poll msgs)"
}

/**
 * D 工作包验收口:回拨当前会话的用户活跃时间(ai_x=毫秒,默认 2h),只动
 * 账本不碰消息数据;随后 `proactive_scan` 即可按「离线回归」判定命中。
 */
private suspend fun cmdProactiveBackdate(vm: MainViewModel, command: AiDebugCommand): String {
    val offlineMs = command.x?.toLong() ?: ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS
    return vm.debugProactiveBackdate(offlineMs)
}

/** D 工作包观测口:当前会话的账本状态行 + 批次清单(状态/未读/通知幂等门)。 */
private suspend fun cmdProactiveState(vm: MainViewModel): String = vm.debugProactiveState()

// ── 5.4 记忆 ─────────────────────────────────────────────────────────

private suspend fun currentCharacter(context: Context, vm: MainViewModel): CharacterEntity {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val convId = conversationId
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val conv = repo.db.conversationDao().byId(convId)
        ?: throw IllegalStateException("conversation $convId not found")
    return repo.characterById(conv.characterId)
        ?: throw IllegalStateException("character of conversation $convId not found")
}

private suspend fun cmdMemoryList(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val character = currentCharacter(context, vm)
    val filter = command.arg
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val all = repo.memoriesFor(character.id)
        .sortedByDescending { it.at }
        .filter { filter == null || it.content.contains(filter) }
    if (all.isEmpty()) {
        return "(no memories${if (filter != null) " matching '$filter'" else ""} for ${character.name})"
    }
    val lines = all.map { m ->
        val cat = m.category
        val sub = if (m.subtype.isNotBlank()) ":${m.subtype}" else ""
        val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(m.at))
        "${m.id} [$cat$sub] ${m.content.take(60)} ($time)"
    }
    return lines.joinToString("\n") + "\ntotal=${all.size}"
}

private suspend fun cmdMemoryStats(context: Context, vm: MainViewModel): String {
    val character = currentCharacter(context, vm)
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val all = repo.memoriesFor(character.id)
    val dist = all.groupingBy { it.category }.eachCount()
    val distLine = MemoryCategories.ALL.joinToString("·") { cat ->
        "$cat=${dist[cat] ?: 0}"
    }
    return "total=${all.size} ($distLine) character=${character.name}"
}

private suspend fun cmdMemoryForget(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val character = currentCharacter(context, vm)
    val prefix = requireArg(command)
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val candidates = repo.memoriesFor(character.id)
        .filter { it.id.toString().startsWith(prefix) }
    return when {
        candidates.isEmpty() -> throw IllegalArgumentException(
            "No memory id starts with '$prefix'. Send ai_cmd=memory_list to see options.",
        )
        candidates.size > 1 -> throw IllegalArgumentException(
            "ambiguous id prefix '$prefix': ${candidates.joinToString(", ") { it.id.toString() }}",
        )
        else -> {
            vm.forgetMemory(candidates.first().id)
            "forgot ${candidates.first().id}: ${candidates.first().content.take(40)}"
        }
    }
}

private suspend fun cmdMemorySummarize(vm: MainViewModel): String {
    // summarizeMemoryNow 以「记忆页打开的会话 id」为目标(与 UI 同源),静默
    // no-op 会让人误判成功 —— 先对拍打开页;不一致时显式打开记忆页再总结
    val chatId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    if (vm.memoryConversationId.value != chatId) {
        vm.openMemory(chatId)
    }
    if (vm.memoryUi.value.generating) {
        throw IllegalStateException("memory summarization already in progress")
    }
    // summarizeMemoryNow 内部 viewModelScope.launch 走 memoryCoordinator.summarizeNow
    // (异步请求 LLM):入队即返回,结果用 memory_list / memory_stats 轮询确认。
    vm.summarizeMemoryNow()
    return "summarize queued for conversation $chatId (poll memory_list / memory_stats to confirm)"
}

// ── 5.5 状态与风格 ───────────────────────────────────────────────────

/** `status_now`:当前状态条取值 + 是否来自状态记录(E2 记录优先/规则兜底口径)。 */
private suspend fun cmdStatusNow(vm: MainViewModel): String {
    val (line, fromRecord) = vm.debugStatusNow()
    val source = if (fromRecord) "status record" else "rule fallback (no active record)"
    return "${line.emoji} ${line.text} — source: $source"
}

/** `status_list`:当前角色近期状态记录(id emoji 心情/正在做/内心想法 来源 时间 状态)。 */
private suspend fun cmdStatusList(vm: MainViewModel, command: AiDebugCommand): String {
    val limit = (command.x?.toInt() ?: 10).coerceIn(1, 30)
    val records = vm.debugStatusList(limit)
    if (records.isEmpty()) return "(no status records; chat a few turns or status_inject)"
    return records.joinToString("\n") { r ->
        buildList {
            add("${r.id} ${r.emoji} 心情=${r.mood} 做=${r.doing} 想=${r.thought}")
            add("src=${r.sourceKind} turn=${r.sourceTurnId.take(12)} range=${r.sourceStartMessageId}..${r.sourceEndMessageId} v=${r.sourceVersion}")
            add("${r.state} @${com.neethu.gouxiang.ui.status.statusTimeLabel(r.generatedAt)}")
        }.joinToString(" | ")
    }
}

/** `status_inject`:手动注入一条状态(arg=心情|正在做的事|内心想法[|emoji])。 */
private suspend fun cmdStatusInject(vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val record = vm.debugStatusInject(arg)
    return "injected status id=${record.id} ${record.emoji} ${record.doing} (poll status_list)"
}

private suspend fun cmdStatus(vm: MainViewModel): String {
    val chatId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val chatUi = vm.chatUi.value
    val character = chatUi.character
        ?: throw IllegalStateException("chat not ready (still loading); resend ai_cmd=status")
    val messages = vm.debugMessages(chatId, 20)
    val line = StatusRule.generate(character, messages)
    // 判定依据回显:命中关键词 / 动作段 / 默认态
    val lastUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
    val basis = when {
        lastUser.isNotBlank() -> "keyword match on last user message '${lastUser.take(20)}'"
        lastAssistant != null -> "last assistant action segment"
        else -> "default state"
    }
    return "${line.emoji} ${line.text} — basis: $basis"
}

private suspend fun cmdSetStyle(vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val mode = when (arg.lowercase()) {
        "heartbeat" -> DialogMode.HEARTBEAT
        "delicate" -> DialogMode.DELICATE
        "long_dialog" -> DialogMode.LONG_DIALOG
        "deep_think" -> DialogMode.DEEP_THINK
        "basic" -> DialogMode.BASIC
        else -> throw IllegalArgumentException(
            "No style matches '$arg'. Options: heartbeat | delicate | long_dialog | deep_think | basic.",
        )
    }
    vm.selectDialogMode(mode)
    return "style=${mode.name.lowercase()} (persisted; verify via state)"
}

private suspend fun cmdSetLength(vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val length = when (arg.lowercase()) {
        "shorter" -> ReplyLength.SHORTER
        "medium" -> ReplyLength.MEDIUM
        "longer" -> ReplyLength.LONGER
        else -> throw IllegalArgumentException(
            "No length matches '$arg'. Options: shorter | medium | longer.",
        )
    }
    vm.selectReplyLength(length)
    return "length=${length.name.lowercase()} (persisted; verify via state)"
}

// ── 5.8 任务历史(M0 收尾工作包 1)────────────────────────────────────

/** 任务历史清单:`workId 前8位 来源 状态(分类) 类型·会话·结果ID 原因`。 */
private suspend fun cmdTasks(context: Context): String {
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val latest = repo.db.derivativeTaskDao().observeHistory(20).first()
    if (latest.isEmpty()) return "(no derivative tasks yet)"
    return latest.joinToString("\n") { row ->
        val stateLine = if (row.state == "failed") {
            val kind = com.neethu.gouxiang.chat.DerivativeFailureKind.fromName(row.failureKind)
            "${row.state}(${kind.label})"
        } else row.state
        val parts = buildList {
            add("${row.workId.take(8)} ${row.source.lowercase()} $stateLine " +
                "${row.kind.lowercase()}·conv${row.conversationId}")
            row.resultIds?.takeIf { it.isNotBlank() }?.let { add("result=$it") }
            row.failureReason?.let { add("「${it.take(30)}」") }
        }
        parts.joinToString(" ")
    }
}

/** workId 前 8 位 → 唯一匹配的完整 workId(歧义列出候选)。 */
private suspend fun resolveWorkId(context: Context, prefix: String): String {
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val candidates = repo.db.derivativeTaskDao().observeHistory(100).first()
        .filter { it.workId.startsWith(prefix.trim()) }
    return when {
        candidates.isEmpty() -> throw IllegalArgumentException(
            "No task matches workId prefix '$prefix'. Send ai_cmd=tasks to see options.",
        )
        candidates.size > 1 -> throw IllegalArgumentException(
            "ambiguous workId prefix '$prefix': ${candidates.joinToString(", ") { it.workId.take(8) }}",
        )
        else -> candidates.first().workId
    }
}

private suspend fun cmdTaskRetry(context: Context, command: AiDebugCommand): String {
    val prefix = requireArg(command)
    val workId = resolveWorkId(context, prefix)
    // 与任务历史页「重试任务」同一条数据路径(同 requestId 幂等);页面开着时
    // 由 Room 流自动刷新
    val ok = com.neethu.gouxiang.work.TaskHistoryController.retry(context, workId)
    if (!ok) throw IllegalStateException("task $workId cannot be retried (record missing or invalid)")
    return "retry queued for $workId (poll tasks to confirm)"
}

private suspend fun cmdTaskCancel(context: Context, command: AiDebugCommand): String {
    val prefix = requireArg(command)
    val workId = resolveWorkId(context, prefix)
    val cancelled = com.neethu.gouxiang.work.TaskHistoryController.cancel(context, workId)
    return if (cancelled) "cancelled $workId" else "task $workId already finished (nothing to cancel)"
}

// ── 5.9 角色创作与管理(C 工作包)────────────────────────────────────

/** 角色清单:`id 名字 来源 avatar=<文件|-> `,验收重启后角色/头像/会话对应用。 */
private suspend fun cmdChars(context: Context): String {
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val characters = repo.db.characterDao().observeAll().first()
    if (characters.isEmpty()) return "(no characters)"
    return characters.joinToString("\n") { c ->
        val avatar = c.avatarFile.ifBlank { "-" }
        val version = "v${c.avatarVersion}"
        "${c.id} ${c.name} source=${c.source} avatar=$avatar $version"
    }
}

/**
 * 创建/编辑角色(C1+C2 数据路径):ai_arg = JSON 载荷
 * `{"name":"..","profile":"..","intro":"..","lines":"..","opening":"(..)台词","appearance":"..","style":".."}`
 * (编辑需含 `"id":N`)。走 VM.saveCharacter 与 UI 按钮同一校验 + 原子落库;
 * ai_x=1 时保存成功直接进入该角色 IM 续聊(open_chat 同语义)。
 */
private suspend fun cmdCharCreate(
    context: Context,
    vm: MainViewModel,
    command: AiDebugCommand,
    create: Boolean,
): String {
    val payload = requireArg(command)
    val draft = parseCharacterPayload(payload, create)
    val openAfter = command.x?.toInt() == 1
    return when (val outcome = vm.saveCharacter(draft, openChatAfter = openAfter)) {
        is com.neethu.gouxiang.ui.character.CharacterSaveOutcome.Ok ->
            (if (outcome.created) "created" else "updated") +
                " character id=${outcome.characterId} imConv=${outcome.conversationId}" +
                (if (openAfter) " (chat opened)" else " (poll chars / msgs to confirm)")
        is com.neethu.gouxiang.ui.character.CharacterSaveOutcome.Invalid ->
            throw IllegalArgumentException(
                "validation failed: " + listOfNotNull(
                    outcome.errors.name?.let { "name: $it" },
                    outcome.errors.profile?.let { "profile: $it" },
                    outcome.errors.openingLine?.let { "opening: $it" },
                    outcome.errors.intro?.let { "intro: $it" },
                    outcome.errors.exampleLines?.let { "lines: $it" },
                    outcome.errors.appearance?.let { "appearance: $it" },
                    outcome.errors.appearanceStyle?.let { "style: $it" },
                ).joinToString("; "),
            )
        is com.neethu.gouxiang.ui.character.CharacterSaveOutcome.Conflict ->
            throw IllegalStateException(
                "name conflict: a character named '${draft.name}' already exists (edit it via char_edit instead)",
            )
        is com.neethu.gouxiang.ui.character.CharacterSaveOutcome.Failure ->
            throw IllegalStateException("save failed: ${outcome.reason}")
    }
}

/** 头像显式重试(C3:断网失败后的恢复路径);结果用 chars 轮询状态。 */
private suspend fun cmdAvatarRetry(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val characterId = command.x?.toLong()
        ?: throw IllegalArgumentException("missing ai_x (character id, see chars)")
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    if (repo.characterById(characterId) == null) {
        throw IllegalArgumentException("No character matches id $characterId. Send ai_cmd=chars to see options.")
    }
    vm.retryAvatar(characterId)
    return "avatar retry queued for character $characterId (poll chars for state)"
}

// ── 5.6a 番外剧场与心动事件(F 工作包)────────────────────────────

/** `sidestory_list`:当前会话记录(id kind key 状态 配图 标题)。 */
private suspend fun cmdSidestoryList(vm: MainViewModel, command: AiDebugCommand): String {
    val limit = (command.x?.toInt() ?: 20).coerceIn(1, 100)
    val records = vm.debugSidestoryList(limit)
    if (records.isEmpty()) return "(no sidestory/heartbeat records; sidestory_generate one)"
    return records.joinToString("\n") { r ->
        val title = r.title.ifBlank { "-" }
        "${r.id} ${r.kind} ${r.templateKey} ${r.state} img=${r.imageStatus} req=${r.requestId.take(8)} 「$title」"
    }
}

/** `sidestory_generate`:按 key 生成一条(入队即 OK;结果用 sidestory_list 轮询)。 */
private suspend fun cmdSidestoryGenerate(vm: MainViewModel, command: AiDebugCommand): String {
    val key = requireArg(command)
    return vm.debugSidestoryGenerate(key)
}

/** `sidestory_delete`:按记录 id 删除一条(与 UI 同一数据路径)。 */
private suspend fun cmdSidestoryDelete(vm: MainViewModel, command: AiDebugCommand): String {
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("missing record id — pass ai_x=<recordId> (see sidestory_list)")
    return vm.debugSidestoryDelete(id)
}

/** `sidestory_retry`:按记录 id 重试失败记录(断网失败后的恢复路径)。 */
private suspend fun cmdSidestoryRetry(vm: MainViewModel, command: AiDebugCommand): String {
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("missing record id — pass ai_x=<recordId> (see sidestory_list)")
    return vm.debugSidestoryRetry(id)
}

/** `open_sidestory`:打开番外页(当前会话;dump 验证页面结构)。 */
private suspend fun cmdOpenSidestory(vm: MainViewModel): String {
    val chatId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    vm.openSidestory(chatId)
    return "sidestory screen opened for conversation $chatId (verify via uiautomator dump)"
}

// ── 5.6b 手帐/小说/合照与心声 G 交互(G 工作包)────────────────────

/** `derivative_list`:当前会话的手帐/小说/合照记录(id kind 状态 配图 标题)。 */
private suspend fun cmdDerivativeList(vm: MainViewModel, command: AiDebugCommand): String {
    val limit = (command.x?.toInt() ?: 20).coerceIn(1, 100)
    val records = vm.debugChatDerivativeList(limit)
    if (records.isEmpty()) return "(no handbook/novel/photo records; handbook_generate | novel_generate | photo_generate one)"
    return records.joinToString("\n") { r ->
        val title = r.title.ifBlank { "-" }
        "${r.id} ${r.kind} ${r.state} img=${r.imageStatus}${if (r.isStale) " stale" else ""} req=${r.requestId.take(8)} 「$title」"
    }
}

/** `handbook_generate`/`novel_generate`/`photo_generate` 后的共同重试入口。 */
private suspend fun cmdDerivativeRetry(vm: MainViewModel, command: AiDebugCommand): String {
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("missing record id — pass ai_x=<recordId> (see derivative_list)")
    return vm.debugChatDerivativeRetry(id)
}

/** `derivative_delete`:按记录 id 删除一条(与 UI 同一数据路径)。 */
private suspend fun cmdDerivativeDelete(vm: MainViewModel, command: AiDebugCommand): String {
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("missing record id — pass ai_x=<recordId> (see derivative_list)")
    return vm.debugChatDerivativeDelete(id)
}

/** `open_handbook`/`open_novel`/`open_photo`:打开对应衍生页(dump 验证页面结构)。 */
private suspend fun cmdOpenDerivative(vm: MainViewModel, kind: String): String {
    val chatId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    when (kind) {
        "handbook" -> vm.openHandbook(chatId)
        "novel" -> vm.openNovel(chatId)
        else -> vm.openPhoto(chatId)
    }
    return "$kind screen opened for conversation $chatId (verify via uiautomator dump)"
}

/** `innervoice_switch`:当前角色的「自动生成心声」开关(arg = on | off)。 */
private fun cmdInnerVoiceSwitch(vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command).lowercase()
    val enabled = when (arg) {
        "on", "true", "1" -> true
        "off", "false", "0" -> false
        else -> throw IllegalArgumentException("arg must be on|off (got '$arg')")
    }
    val characterId = vm.debugInnerVoiceCharacterId()
        ?: throw IllegalStateException("no open inner-voice screen — send ai_cmd=open innervoice first")
    vm.toggleInnerVoiceAuto(characterId, enabled)
    return "inner-voice auto switch for character $characterId -> $enabled (poll state or dump the sheet)"
}

/** `innervoice_delete`:按心声 id 删除一张卡(与 UI 同一数据路径)。 */
private fun cmdInnerVoiceDelete(vm: MainViewModel, command: AiDebugCommand): String {
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("missing voice id — pass ai_x=<innerVoiceId>")
    vm.deleteInnerVoice(id)
    return "inner voice $id delete requested (poll innervoice screen or dump)"
}

// ── 5.6c 朋友圈扩展与发现(H 工作包)────────────────────────────

private suspend fun cmdInnerVoiceList(context: Context, vm: MainViewModel): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val conversation = repo.db.conversationDao().byId(conversationId)
        ?: throw IllegalStateException("conversation $conversationId no longer exists")
    val voices = repo.innerVoices(conversation.characterId)
    if (voices.isEmpty()) return "(no inner voices for character ${conversation.characterId})"
    return voices.take(30).joinToString("\n") {
        "${it.id} stale=${it.isStale} at=${it.at} 「${it.title}」 ${it.echo.take(80)}"
    }
}

private suspend fun cmdContentState(context: Context, vm: MainViewModel): String {
    val id = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val db = com.neethu.gouxiang.data.GouXiangRepo.get(context).db
    val conversation = db.conversationDao().byId(id) ?: error("conversation $id no longer exists")
    val character = db.characterDao().byId(conversation.characterId) ?: error("character missing")
    val dao = db.contentDiscoveryDao()
    val state = dao.state(id) ?: com.neethu.gouxiang.data.ContentDiscoveryState(id)
    val turns = dao.turnsAfter(id, state.consumedTurnId)
    val prefs = com.neethu.gouxiang.ai.GenerationPrefs
    return "conversation=$id turns=${turns.size} consumed=${state.consumedTurnId} " +
        "evaluated=${state.lastEvaluatedTurnId} evaluatedCount=${state.lastEvaluatedCount} " +
        "failures=${state.failures} retryAfter=${state.retryAfter} unseen=${dao.unseenCount(id)} " +
        "voiceAuto=${prefs.innerVoiceAuto(context)}/${character.innerVoiceAuto} " +
        "momentAuto=${prefs.momentsDaily(context)}/${character.momentsJoined}/${character.momentsDailyAuto}"
}

private suspend fun cmdContentList(context: Context, vm: MainViewModel): String {
    val id = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val candidates = com.neethu.gouxiang.data.GouXiangRepo.get(context).db.contentDiscoveryDao()
        .observeForConversation(id).first()
    if (candidates.isEmpty()) return "(no content candidates for conversation $id)"
    return candidates.take(30).joinToString("\n") {
        "${it.id} ${it.kind} body=${it.bodyStatus} image=${it.imageStatus} result=${it.resultId} " +
            "auto=${it.autoEligible} reminder=${it.reminderStatus} stale=${it.sourceStale} 「${it.title}」"
    }
}

private fun cmdContentAuto(vm: MainViewModel, command: AiDebugCommand): String {
    val parts = requireArg(command).trim().split(Regex("\\s+"))
    require(parts.size == 2) { "usage: content_auto <voice|moment> <on|off>" }
    val key = when (parts[0].lowercase()) {
        "voice" -> com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO
        "moment" -> com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY
        else -> throw IllegalArgumentException("kind must be voice|moment")
    }
    val enabled = when (parts[1].lowercase()) {
        "on" -> true
        "off" -> false
        else -> throw IllegalArgumentException("value must be on|off")
    }
    vm.toggleGenerationSetting(key, enabled)
    return "${parts[0].lowercase()} auto=$enabled"
}

private suspend fun cmdContentOpen(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val id = command.x?.toLong() ?: command.arg?.toLongOrNull()
        ?: throw IllegalArgumentException("usage: content_open <candidateId>")
    val candidate = com.neethu.gouxiang.data.GouXiangRepo.get(context).db.contentDiscoveryDao().byId(id)
        ?: throw IllegalArgumentException("candidate $id not found")
    require(candidate.conversationId == conversationId) { "candidate $id belongs to another conversation" }
    vm.openContentCandidate(id)
    return "candidate $id opened (poll content_list for body status)"
}

/**
 * 合并时间线清单(H1,真实计数,不伪造社交数据):
 * `id 作者(类型) 赞 评论数 「文本前40字」`。
 */
private suspend fun cmdMomentsList(context: Context): String {
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val characters = repo.db.characterDao().observeAll().first()
    val feed = com.neethu.gouxiang.data.MomentFeed.assemble(
        repo.db.momentDao().all(),
        characters.associateBy { it.id },
    )
    if (feed.isEmpty()) {
        return "(no moments — ai_cmd=moments post <文本> 发一条,或 open_chat 后 ai_cmd=moments gen)"
    }
    val lines = mutableListOf<String>()
    for (item in feed) {
        val like = if (item.post.likedByMe) "赞1" else "赞0"
        val comments = repo.countMomentComments(item.post.id)
        lines += "${item.post.id} ${item.authorName}(${item.post.authorKind}) $like " +
            "评$comments 「${item.post.text.take(40)}」"
    }
    return lines.joinToString("\n")
}

/**
 * `moments <动作>`(H 工作包验收触达口):
 * `gen`(对当前打开聊天生成一条角色动态)/ `post <文本>`(用户发帖 → 互评
 * 轮异步)/ `like <postId>` / `comment <postId> <文本>` / `del_post <postId>` /
 * `del_comment <commentId>`。
 */
private suspend fun cmdMoments(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command).trim()
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val head = arg.substringBefore(' ').lowercase()
    val rest = arg.substringAfter(' ', "").trim()
    return when (head) {
        "gen" -> {
            val conversationId = vm.debugConversationId
                ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
            if (vm.momentsUi.value.generating) {
                throw IllegalStateException("moment generation already in progress (poll moments_list)")
            }
            vm.generateMoment(conversationId)
            "moment generation queued for conversation $conversationId (poll moments_list)"
        }
        "post" -> {
            if (rest.isEmpty()) {
                throw IllegalArgumentException("usage: ai_cmd=moments --es ai_arg 'post <文本>'")
            }
            if (vm.momentsUi.value.publishing) {
                throw IllegalStateException("a post is still publishing — poll moments_list")
            }
            vm.publishUserMoment(rest)
            "user post queued (poll moments_list; interaction round runs async)"
        }
        "like" -> {
            val post = momentById(repo, rest)
            vm.toggleMomentLike(post)
            "like toggled on post ${post.id} (liked=${!post.likedByMe} after toggle; poll moments_list)"
        }
        "comment" -> {
            val postId = rest.substringBefore(' ').toLongOrNull()
                ?: throw IllegalArgumentException("usage: ai_cmd=moments --es ai_arg 'comment <postId> <文本>'")
            val text = rest.substringAfter(' ', "").trim()
            val post = momentById(repo, postId.toString())
            if (text.isEmpty()) {
                throw IllegalArgumentException("usage: ai_cmd=moments --es ai_arg 'comment <postId> <文本>'")
            }
            vm.sendMomentComment(post, text)
            "user comment queued on post $postId (author reply gated by permissions; poll moments_list)"
        }
        "del_post" -> {
            val post = momentById(repo, rest)
            vm.deleteUserMoment(post.id)
            "user post ${post.id} delete requested (avatar posts are ignored by design; poll moments_list)"
        }
        "del_comment" -> {
            val id = rest.toLongOrNull()
                ?: throw IllegalArgumentException("usage: ai_cmd=moments --es ai_arg 'del_comment <commentId>' (see moment comments via moments_list)")
            vm.deleteUserComment(id)
            "comment $id delete requested (only user comments are removable; poll moments_list)"
        }
        else -> throw IllegalArgumentException(
            "unknown moments action '$head' — gen | post | like | comment | del_post | del_comment",
        )
    }
}

private suspend fun momentById(repo: com.neethu.gouxiang.data.GouXiangRepo, raw: String): com.neethu.gouxiang.data.MomentEntity {
    val id = raw.toLongOrNull()
        ?: throw IllegalArgumentException("usage: pass a numeric postId (see moments_list)")
    return repo.db.momentDao().byId(id)
        ?: throw IllegalArgumentException("No moment matches id $id. Send ai_cmd=moments_list to see options.")
}

/**
 * `moments_perm <角色名|id> <joined|see|interact|daily> <on|off>`(H2):
 * 逐角色切换 参与/看用户动态/评论互动/自动日更;写回 characters 对应列,
 * 生成层(入队+执行)与查询层(合并时间线/互评候选)双执行。
 */
private suspend fun cmdMomentsPerm(context: Context, vm: MainViewModel, command: AiDebugCommand): String {
    val arg = requireArg(command)
    val parts = arg.trim().split(Regex("\\s+"))
    if (parts.size < 3) {
        throw IllegalArgumentException(
            "usage: ai_cmd=moments_perm --es ai_arg '<角色名|id> <joined|see|interact|daily> <on|off>' (see chars)",
        )
    }
    val repo = com.neethu.gouxiang.data.GouXiangRepo.get(context)
    val who = parts[0]
    val character = repo.characterByName(who)
        ?: who.toLongOrNull()?.let { repo.characterById(it) }
        ?: throw IllegalArgumentException(
            "No character matches '$who'. Send ai_cmd=chars to see options.",
        )
    val field = when (parts[1].lowercase()) {
        "joined" -> com.neethu.gouxiang.ui.moments.MomentPermissionField.JOINED
        "see" -> com.neethu.gouxiang.ui.moments.MomentPermissionField.SEE_USER_POSTS
        "interact" -> com.neethu.gouxiang.ui.moments.MomentPermissionField.INTERACT
        "daily" -> com.neethu.gouxiang.ui.moments.MomentPermissionField.DAILY_AUTO
        else -> throw IllegalArgumentException(
            "unknown permission '${parts[1]}' — joined | see | interact | daily",
        )
    }
    val enabled = when (parts[2].lowercase()) {
        "on", "true", "1" -> true
        "off", "false", "0" -> false
        else -> throw IllegalArgumentException("value must be on|off (got '${parts[2]}')")
    }
    vm.setMomentPermission(character.id, field, enabled)
    return "moments permission ${parts[1].lowercase()} of ${character.name} -> $enabled " +
        "(auto tasks re-checked at enqueue/execute; feed & interactions re-filter immediately)"
}

/** char 载荷容错 JSON 解析(与 MultiBubbleParser 同款 lenient 配置)。 */
private val charPayloadJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true; isLenient = true
}

/**
 * char 载荷解析(容错:缺省字段为空串;编辑必须带数字 id)。校验仍走
 * CharacterFormValidator,这里只做结构解析。
 */
private fun parseCharacterPayload(payload: String, create: Boolean): com.neethu.gouxiang.ui.character.CharacterDraft {
    val root = try {
        charPayloadJson.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject
    } catch (_: Exception) {
        null
    } ?: throw IllegalArgumentException(
        "payload must be a JSON object, e.g. {\"name\":\"..\",\"profile\":\"..\",\"opening\":\"..\"}",
    )

    fun str(key: String): String =
        (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    val id = str("id").toLongOrNull()
    if (!create && id == null) {
        throw IllegalArgumentException("char_edit payload requires a numeric \"id\" (see chars)")
    }
    if (create && id != null && id > 0) {
        throw IllegalArgumentException("char_create must not carry \"id\"; use char_edit for existing characters")
    }
    return com.neethu.gouxiang.ui.character.CharacterDraft(
        id = id ?: 0L,
        name = str("name"),
        profile = str("profile"),
        intro = str("intro"),
        exampleLines = str("lines"),
        openingLine = str("opening"),
        appearance = str("appearance"),
        appearanceStyle = str("style"),
    )
}

package com.neethu.gouxiang.debug

import android.content.Context
import android.util.Log
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
            "memory_list" -> cmdMemoryList(context, vm, command)
            "memory_stats" -> cmdMemoryStats(context, vm)
            "memory_forget" -> cmdMemoryForget(context, vm, command)
            "memory_summarize" -> cmdMemorySummarize(vm)
            "status" -> cmdStatus(vm)
            "set_style" -> cmdSetStyle(vm, command)
            "set_length" -> cmdSetLength(vm, command)
            else -> throw IllegalArgumentException(
                "unknown command, send ai_cmd=help (see docs/ai-debug-intents.md)",
            )
        }
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
        else -> throw IllegalArgumentException(
            "No tab matches '$arg'. Options: home | messages | profile.",
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

private suspend fun cmdProactive(vm: MainViewModel): String {
    val conversationId = vm.debugConversationId
        ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
    val conv = vm.conversations.value.firstOrNull { it.conversation.id == conversationId }?.conversation
        ?: throw IllegalStateException("conversation $conversationId not found")
    val trigger = vm.pickProactiveTrigger(conversationId)
    val age = System.currentTimeMillis() - conv.lastAt
    val verdict = trigger?.let { "${it.name.lowercase()} (would fire on phone-chat entry)" } ?: "null (no trigger)"
    return "last message age=${ProactiveMessages.offlineMsDescription(age)} trigger=$verdict"
}

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

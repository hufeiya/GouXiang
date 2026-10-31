package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.protocol.Bubble

/**
 * 语音通话 F6 的口语文本层:LLM 多泡回复 → TTS 可念的口语词。
 *
 * 屏幕上的气泡与念出来的话不是一回事:
 * - [Bubble.Kind.TEXT] 正文照念;内嵌 () 动作是给"眼睛"的(神态/场景,含
 *   存量表情降级出的括号文本),TTS 念出来就是废话,剥离(实机验收要求
 *   "完整念出角色回复文本"指用户能听懂的口语部分)。
 * - [Bubble.Kind.IMAGE]/[VOICE] 与手机卡片消息没有可念语义,只给
 *   [seenBubbles] 摘要(字幕页可见,验证"回复确实来了"),不进 TTS。
 */
object CallSpeech {

    /** TTS 文本为空时字幕页的兜底文案(引擎/网络故障也走这里,不空转)。 */
    const val FALLBACK_SPEECH = "(信号有点糟,刚刚没听清)再说一遍好不好?"

    /**
     * 回复泡列表 → 口语文本(纯函数,单测直接覆盖)。
     * [seenBubbles] 返回"进了哪些泡"的字幕摘要(贴纸/图片/语音/卡片有描述,
     * 文本泡给正文首行);两者都为空 = 回复全空(理论不发生,调用方兜底)。
     */
    fun composeSpeechText(bubbles: List<Bubble>): Pair<String, List<String>> {
        val spoken = StringBuilder()
        val seen = mutableListOf<String>()
        for (b in bubbles) {
            when (b.kind) {
                Bubble.Kind.TEXT -> {
                    val speech = stripActions(b.text).trim()
                    if (speech.isNotEmpty()) {
                        if (spoken.isNotEmpty()) spoken.append('。')
                        spoken.append(speech)
                        seen += speech
                    }
                }
                else -> {
                    if (b.text.isNotBlank()) seen += b.text
                }
            }
        }
        return spoken.toString().trim() to seen
    }

    /** 剥离括号内的动作描述，支持全角、半角和嵌套；未闭合的动作隐藏至末尾。 */
    fun stripActions(text: String): String {
        val spoken = StringBuilder(text.length)
        var depth = 0
        for (character in text) {
            when (character) {
                '(', '（' -> {
                    if (depth == 0) spoken.append(' ')
                    depth++
                }
                ')', '）' -> if (depth > 0) depth-- else spoken.append(character)
                else -> if (depth == 0) spoken.append(character)
            }
        }
        return spoken.toString().trim()
    }

    /** 字幕行的展示文本(空回复给兜底,保证 TTS/字幕总有内容)。 */
    fun speechOrFallback(speech: String): String = speech.ifBlank { FALLBACK_SPEECH }
}

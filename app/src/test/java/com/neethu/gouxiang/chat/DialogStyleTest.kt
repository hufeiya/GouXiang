package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话风格(F5)单测:弹层 09/10 的选择 → system prompt 行为段与连发参数
 * 的真实映射(纯 JVM,零 Android 依赖,惯例同 ChatOrchestratorTest)。
 */
class DialogStyleTest {

    // ── 模式行为段(范围 2:选择真实生效)────────────────────────────

    @Test
    fun `every mode has non-blank prompt block with distinct wording`() {
        val blocks = DialogMode.entries.map { it.promptBlock }
        blocks.forEach { b ->
            assertTrue("行为段应非空", b.isNotBlank())
            assertTrue("行为段应以 [对话模式] 标签开头: $b", b.startsWith("[对话模式]"))
        }
        assertEquals("五种模式行为段互不相同", blocks.size, blocks.toSet().size)
    }

    @Test
    fun `mode titles and descs match 09 dump lines`() {
        // 基准 09-dialog-mode.xml 逐行:标题与描述顺序一致
        assertEquals(
            listOf("心动模式", "细腻模式", "长对话模式", "深度思考模式", "基础模式"),
            DialogMode.entries.map { it.title },
        )
        assertEquals("增强恋爱拉扯和情感张力", DialogMode.HEARTBEAT.desc)
        assertEquals("增强细节描写,情感表现细腻", DialogMode.DELICATE.desc)
        assertEquals("超长文本输出,沉浸内容体验", DialogMode.LONG_DIALOG.desc)
        assertEquals("限定模式,回复等待时间长", DialogMode.DEEP_THINK.desc)
        assertEquals("原灵动模式,角色聪明灵动", DialogMode.BASIC.desc)
    }

    @Test
    fun `system prompt carries selected mode block`() {
        val p = ChatContextBuilder.systemPrompt(
            char, memories,
            mode = DialogMode.LONG_DIALOG,
        )
        assertTrue("应包含所选模式行为段", p.contains("[对话模式] 长对话模式"))
        assertTrue(p.contains("[对话模式] 长对话模式:超长文本输出"))
        assertFalse("未选模式不应出现", p.contains("心动模式:恋爱心动感拉满"))
    }

    @Test
    fun `default mode keeps heartbeat block so f2 prompt is compatible`() {
        // 默认参数(不传 mode)= 心动模式:prompt 与弹层默认勾选项一致
        val p = ChatContextBuilder.systemPrompt(char, memories)
        assertTrue(p.contains("[对话模式] 心动模式"))
        // 行为段在 [当前形态] 之后、[记忆] 块之前(组装序;记忆块头见 MemoryInjection)
        assertTrue(p.indexOf("[当前形态]") < p.indexOf("[对话模式]"))
        assertTrue(p.indexOf("[对话模式]") < p.indexOf("[记忆]"))
    }

    @Test
    fun `prompt never mentions billing`() {
        // 范围红线:本地版全部免费,五种模式行为段不得出现计费字样
        DialogMode.entries.forEach { mode ->
            val p = ChatContextBuilder.systemPrompt(char, memories, mode = mode)
            assertFalse("不应出现限免: $mode", p.contains("限免"))
            assertFalse("不应出现猫粮: $mode", p.contains("猫粮"))
            assertFalse("不应出现扣费: $mode", p.contains("扣费"))
        }
    }

    // ── 回复长度 → 连发参数(范围 3:真实影响连发参数)────────────────

    @Test
    fun `reply length maps to burst params im default matches f2`() {
        // IM 默认(适中)= F2 既定 3 条 ×30 字
        assertEquals(BurstParams(3, 3, 30), BurstParams.resolve(ReplyLength.MEDIUM, phone = false, mode = DialogMode.HEARTBEAT))
        // 更短:2 条 ×18 字;更长:4 条 ×60 字
        assertEquals(BurstParams(2, 2, 18), BurstParams.resolve(ReplyLength.SHORTER, phone = false, mode = DialogMode.HEARTBEAT))
        assertEquals(BurstParams(4, 4, 60), BurstParams.resolve(ReplyLength.LONGER, phone = false, mode = DialogMode.HEARTBEAT))
    }

    @Test
    fun `reply length maps to burst params phone default matches f4`() {
        // 手机默认(适中)= F4 既定 2~4 条 ×30 字
        assertEquals(BurstParams(2, 4, 30), BurstParams.resolve(ReplyLength.MEDIUM, phone = true, mode = DialogMode.HEARTBEAT))
        assertEquals(BurstParams(2, 3, 16), BurstParams.resolve(ReplyLength.SHORTER, phone = true, mode = DialogMode.HEARTBEAT))
        assertEquals(BurstParams(3, 4, 45), BurstParams.resolve(ReplyLength.LONGER, phone = true, mode = DialogMode.HEARTBEAT))
    }

    @Test
    fun `long dialog mode doubles chars and caps bubbles at protocol max`() {
        val p = BurstParams.resolve(ReplyLength.MEDIUM, phone = false, mode = DialogMode.LONG_DIALOG)
        assertEquals(60, p.maxBubbleChars) // 30 × 2
        assertEquals(4, p.maxBubbles) // 协议顶格
        val phoneP = BurstParams.resolve(ReplyLength.SHORTER, phone = true, mode = DialogMode.LONG_DIALOG)
        assertTrue("手机长对话仍保留区间下限", phoneP.minBubbles < phoneP.maxBubbles)
        assertEquals(32, phoneP.maxBubbleChars) // 16 × 2
    }

    @Test
    fun `burst params surface in system prompt rules`() {
        // 更短(IM):「连发 2 条」+ 每条 ≤18 字
        val short = ChatContextBuilder.systemPrompt(
            char, memories, maxBubbles = 2, maxBubbleChars = 18, minBubbles = 2,
        )
        assertTrue(short.contains("连发 2 条"))
        assertTrue(short.contains("不超过 18个字"))
        // 更长(IM):「连发 4 条」+ ≤60 字
        val longer = ChatContextBuilder.systemPrompt(
            char, memories, maxBubbles = 4, maxBubbleChars = 60, minBubbles = 4,
        )
        assertTrue(longer.contains("连发 4 条"))
        assertTrue(longer.contains("不超过 60个字"))
        // 区间(IM,弹层更长且未顶格的组合):「连发 2~3 条」
        // 注:此为参数化口径测试 —— 生产 [BurstParams] 映射只会产生固定/顶格组合,
        // 区间组合由未来风格参数组合预留(手机形态同款口径)。
        val ranged = ChatContextBuilder.systemPrompt(
            char, memories, maxBubbles = 3, maxBubbleChars = 30, minBubbles = 2,
        )
        assertTrue(ranged.contains("连发 2~3 条"))
        assertTrue(ranged.contains("连发 2~3 条".replace("连发 ", ""))) // 形态行同步
        // 手机形态:区间口径保持(min~max)
        val phone = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 3, maxBubbleChars = 16, minBubbles = 2, phoneStyle = true)
        assertTrue(phone.contains("连发 2~3 条"))
        assertTrue(phone.contains("不超过 16个字"))
    }

    @Test
    fun `default minBubbles keeps legacy im and phone prompts byte compatible`() {
        // 不传 minBubbles(=0):IM 固定条数(「连发 3 条」)、手机默认区间(「连发 2~4 条」)
        val im = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 3, maxBubbleChars = 30)
        assertTrue(im.contains("连发 3 条"))
        assertFalse(im.contains("连发 0~"))
        val phone = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 4, maxBubbleChars = 30, phoneStyle = true)
        assertTrue(phone.contains("连发 2~4 条"))
    }

    @Test
    fun `reply length drives turn-level burst params end to end`() {
        // F5 验收 4c 修复回归:更长(4,60) 必须原样进入 prompt,不再被
        // 构造期常量截成 (3,30)。模拟 MainViewModel.runRound 的下发路径。
        val longer = BurstParams.resolve(ReplyLength.LONGER, phone = false, mode = DialogMode.HEARTBEAT)
        val p = ChatContextBuilder.systemPrompt(
            char, memories,
            maxBubbles = longer.maxBubbles,
            maxBubbleChars = longer.maxBubbleChars,
            minBubbles = longer.minBubbles,
        )
        assertTrue(p.contains("连发 4 条"))
        assertTrue(p.contains("不超过 60个字"))
        // 更短:字数上限 18 与固定 2 条都真实下发(F5 实测此前只有条数生效)
        val shorter = BurstParams.resolve(ReplyLength.SHORTER, phone = false, mode = DialogMode.HEARTBEAT)
        val ps = ChatContextBuilder.systemPrompt(
            char, memories,
            maxBubbles = shorter.maxBubbles,
            maxBubbleChars = shorter.maxBubbleChars,
            minBubbles = shorter.minBubbles,
        )
        assertTrue(ps.contains("连发 2 条"))
        assertTrue(ps.contains("不超过 18个字"))
    }

    @Test
    fun `prompt forbids leaking internal planning meta talk`() {
        // F5 观察项修复回归:泄漏「用户一直在发hello4…还要分三条短消息」类
        // 内部规划文本 — 输出协议第 5 条硬规则必须存在且带负例。
        DialogMode.entries.forEach { mode ->
            val p = ChatContextBuilder.systemPrompt(char, memories, mode = mode)
            assertTrue("应含元话禁令: $mode", p.contains("只输出要发给用户的消息本身"))
            assertTrue("应含元话负例: $mode", p.contains("不要提「分几条」"))
        }
    }

    @Test
    fun `reply length enum matches 10 dump segments`() {
        assertEquals(listOf("更短", "适中", "更长"), ReplyLength.entries.map { it.title })
        assertEquals(ReplyLength.MEDIUM, ReplyLength.DEFAULT)
        assertEquals(DialogMode.HEARTBEAT, DialogMode.DEFAULT)
    }

    // ── 测试基建(惯例同 ChatOrchestratorTest)────────────────────────

    private val char = CharacterEntity(
        id = 1,
        name = "苏馨玥",
        profile = "22岁,美术系大四学生,和用户同居一年",
        intro = "简介",
        exampleLines = "(笑着)喏。",
        openingLine = "嗨",
        appearance = "desc",
        appearanceStyle = "写实摄影风",
        createdAt = 0,
    )
    private val memories = listOf(
        MemoryEntity(characterId = 1, category = "relationship", content = "我的称呼:老公", importance = 9, at = 1),
        MemoryEntity(characterId = 1, category = "profile", content = "我的身份:上班族", importance = 8, at = 1),
        MemoryEntity(characterId = 1, category = "event", content = "两人一起看了新房", importance = 6, at = 1),
    )
}

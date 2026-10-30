package com.neethu.gouxiang.ui.chat

import com.neethu.gouxiang.ui.phone.rewindSnippet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F1 im 页长按菜单(按钮审计报告 §4-1/§5.1):im 页与 phone 页共用
 * [rewindSnippetShared] 点名截取 —— JVM 锁两页同源口径(设备端 Compose
 * instrumented 在 MIUI 上 Espresso idle 挂起,见 2026-10-30 审计施工记录;
 * UI 行为由真机 adb 冒烟覆盖:长按弹「消息操作」三件套)。
 */
class MessageActionsDialogTest {

    @Test
    fun `shared snippet matches phone-page snippet semantics`() {
        // 同一输入两页产出一致(im 页长按菜单与 phone 页同源的直接证据)
        for (text in listOf(
            " 在吗 ",
            "(举着手里的奶泡)刚才买的抹茶拿铁奶泡超绵密",
            "第一段\n\n第二段",
            "",
        )) {
            assertEquals(rewindSnippet(text), rewindSnippetShared(text))
        }
    }

    @Test
    fun `shared snippet flattens whitespace and truncates at 12 chars`() {
        assertEquals("在吗", rewindSnippetShared(" 在吗 "))
        assertEquals(
            "(举着手里的奶泡)刚才买…",
            rewindSnippetShared("(举着手里的奶泡)刚才买的抹茶拿铁奶泡超绵密"),
        )
        assertEquals("第一段 第二段", rewindSnippetShared("第一段\n\n第二段"))
    }
}

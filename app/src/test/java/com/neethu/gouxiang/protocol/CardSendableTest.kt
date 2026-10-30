package com.neethu.gouxiang.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 转账/代付卡金额非空校验(按钮审计报告 §4-5):
 * 空金额卡发送会渲染「转账 ¥0」,数据脏且观感坏 —— 协议层 [CardPayload.sendable]
 * 是 UI 红提示 / VM 兜底 / ai_cmd 显式 FAIL 共用的同一口径。
 */
class CardSendableTest {

    @Test
    fun `transfer without amount is not sendable`() {
        // 空串/纯空白都拦(编辑器未填 + 模型漏参两种来源)
        assertFalse(CardPayload(kind = PhoneCardKind.TRANSFER, title = "").sendable)
        assertFalse(CardPayload(kind = PhoneCardKind.TRANSFER, title = "   ").sendable)
    }

    @Test
    fun `pay_for without amount is not sendable`() {
        assertFalse(CardPayload(kind = PhoneCardKind.PAY_FOR, title = "").sendable)
        assertFalse(CardPayload(kind = PhoneCardKind.PAY_FOR, title = " \t").sendable)
    }

    @Test
    fun `transfer and pay_for with amount are sendable`() {
        assertTrue(CardPayload(kind = PhoneCardKind.TRANSFER, title = "520").sendable)
        assertTrue(CardPayload(kind = PhoneCardKind.PAY_FOR, title = "88").sendable)
        // 金额是自由文本字段(非数字校验不做,对齐编辑器 hint「例如:520」)
        assertTrue(CardPayload(kind = PhoneCardKind.TRANSFER, title = "请你喝奶茶的钱").sendable)
    }

    @Test
    fun `optional-field cards stay sendable when blank`() {
        // 其余类型字段可选(标题/描述留空降级为类型名占位),不受金额校验约束
        for (kind in listOf(
            PhoneCardKind.IMAGE,
            PhoneCardKind.MUSIC,
            PhoneCardKind.POST,
            PhoneCardKind.LOCATION,
        )) {
            assertTrue(CardPayload(kind = kind, title = "").sendable)
        }
    }

    @Test
    fun `blank-amount summary degrades to kind label`() {
        // 校验兜底前的既有降级语义保持(摘要不渲染 ¥0 样式的假金额)
        assertEquals("[转账]", CardPayload(kind = PhoneCardKind.TRANSFER, title = "").summary())
        assertEquals("[转账] 520", CardPayload(kind = PhoneCardKind.TRANSFER, title = "520").summary())
    }
}

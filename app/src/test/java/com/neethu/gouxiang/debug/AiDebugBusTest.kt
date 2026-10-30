package com.neethu.gouxiang.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ai_cmd 进程级总线单测(M0 收尾工作包 4):命令跨「消费者重建」不丢——
 * 消费者被取消后,通道不关闭、后续命令仍可被新消费者取到(等价 Activity
 * 实例/组合重建后新循环继续消费)。
 */
class AiDebugBusTest {

    @Test
    fun `commands survive consumer restarts`() = runBlocking {
        AiDebugBus.enqueue(AiDebugCommand(name = "state"))
        val first = withTimeout(2_000) { AiDebugBus.commands.receive() }
        assertEquals("state", first.name)
        // 第一个消费者退出后,热启动新入队的命令仍会被下一个消费者取到
        AiDebugBus.enqueue(AiDebugCommand(name = "send_chat", arg = "三二一"))
        val second = withTimeout(2_000) { AiDebugBus.commands.receive() }
        assertEquals("send_chat", second.name)
        assertEquals("三二一", second.arg)
    }

    @Test
    fun `channel is never closed so burst enqueues all land in order`() = runBlocking {
        repeat(50) { AiDebugBus.enqueue(AiDebugCommand(name = "cmd$it")) }
        repeat(50) { index ->
            val cmd = withTimeout(2_000) { AiDebugBus.commands.receive() }
            assertEquals("cmd$index", cmd.name)
        }
    }

    @Test
    fun `cancelled consumer does not close the channel or lose later commands`() = runBlocking {
        val received = CompletableDeferred<AiDebugCommand>()
        val consumer = launch { received.complete(AiDebugBus.commands.receive()) }
        AiDebugBus.enqueue(AiDebugCommand(name = "before"))
        assertEquals("before", withTimeout(2_000) { received.await() }.name)
        // 消费者被取消(等价组合销毁):通道保持开放
        consumer.cancel()
        AiDebugBus.enqueue(AiDebugCommand(name = "after"))
        assertEquals("after", withTimeout(2_000) { AiDebugBus.commands.receive() }.name)
    }
}

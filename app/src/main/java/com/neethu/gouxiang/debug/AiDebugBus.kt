package com.neethu.gouxiang.debug

import kotlinx.coroutines.channels.Channel

/**
 * ai_cmd 调试命令的进程级总线(M0 收尾工作包 4:`send_chat` 热启动修复)。
 *
 * 之前命令队列挂在 Activity 实例上(`MainActivity.aiCommands`),消费循环挂在
 * 组合内 `LaunchedEffect`。两类时序会让热启动的命令静默丢失:
 * 1. Activity 实例被系统回收后重建(进程仍在):新实例的 Channel 是空的,
 *    发往旧实例的命令随旧实例一起消失;
 * 2. 消费循环协程被取消(`executeAiCommand` 曾把 CancellationException 当普通
 *    失败吞掉):Channel 里排队的命令再无人消费,且组合键稳定不会重建循环。
 *
 * 现在命令队列移到进程级:任何 Activity 实例入队,任何(重)组合出的消费循环
 * 都从同一条通道取——命令在进程存活期间不丢;配合执行器不再吞取消,消费循环
 * 死亡面关闭。
 */
object AiDebugBus {

    /** 无界:调试通道不许因背压丢命令;进程退出即释放。 */
    internal val commands = Channel<AiDebugCommand>(Channel.UNLIMITED)

    /** 入队(trySend 对 UNLIMITED 通道恒成功;通道不可能被关闭)。 */
    fun enqueue(command: AiDebugCommand) {
        commands.trySend(command)
    }
}

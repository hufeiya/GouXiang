package com.neethu.gouxiang.work

/**
 * 任务时间源(M0 收尾工作包 1:WorkManager 测试环境的可控时钟缝)。
 * 生产走系统时钟;JVM 单测注入固定值即可断言 createdAt/updatedAt/finishedAt,
 * 不用 sleep。Worker 与对账器([reconcileDerivativeTasks])统一取这里。
 */
internal object DerivativeClock {

    @Volatile
    var nowMillis: () -> Long = { System.currentTimeMillis() }
}

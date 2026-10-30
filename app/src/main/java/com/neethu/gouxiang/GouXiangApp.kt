package com.neethu.gouxiang

import android.app.Application
import com.neethu.gouxiang.work.DerivativeScheduler
import com.neethu.gouxiang.work.NotificationChannels
import kotlinx.coroutines.launch

/**
 * Application 类(docs/background-generation-plan-20261030.md §2.1):
 * - 启动幂等建通知通道(derivative/proactive);
 * - 冷启动兜底注册周期任务(逐项读 GenerationPrefs,只注册开关为开的;
 *   卸载重装/清数据后回到全关默认,符合 token 保守原则)。
 */
class GouXiangApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAll(this)
        DerivativeScheduler.bootstrap(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            com.neethu.gouxiang.work.reconcileDerivativeTasks(this@GouXiangApp)
            com.neethu.gouxiang.data.GouXiangRepo.get(this@GouXiangApp).db.derivativeResultDao().recoverInterruptedImages()
        }
    }
}

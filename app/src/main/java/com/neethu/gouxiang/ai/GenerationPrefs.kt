package com.neethu.gouxiang.ai

import android.content.Context
import android.content.SharedPreferences

/** 内容生成偏好。标题发现始终启用；四项正文开关只控制新候选的后台正文生成。
 * 用户点击候选不受正文自动开关限制，配图由各自的开关单独控制。 */
object GenerationPrefs {

    private const val FILE = "generation_prefs"

    /** 心声正文自动生成，沿用旧键以保留偏好。 */
    const val KEY_INNER_VOICE_AUTO = "gen_inner_voice_auto"

    /** 朋友圈正文自动生成，沿用旧键以保留偏好。 */
    const val KEY_MOMENTS_DAILY = "gen_moments_daily"

    const val KEY_STATUS_DETAIL_AUTO = "gen_status_detail_auto"
    fun statusDetailAuto(context: Context): Boolean = read(context, KEY_STATUS_DETAIL_AUTO, false)
    fun setStatusDetailAuto(context: Context, enabled: Boolean) = write(context, KEY_STATUS_DETAIL_AUTO, enabled)

    const val KEY_INNER_VOICE_IMAGES = "gen_inner_voice_images"
    const val KEY_MOMENTS_IMAGES = "gen_moments_images"

    /**
     * 番外配图(M0 收尾:键先行、分支随 F 工作包接入;生成管线未落地前设置页
     * 该行禁用并标注「功能开发中」,不制造可用假象)。默认关:番外批量生成
     * 量大,配图逐张烧 token,接入时按保守默认。
     */
    const val KEY_SIDESTORY_IMAGES = "gen_sidestory_images"

    /**
     * 手帐配图(G1;文本先出、图片可选,失败保留文字成品)。默认关:与番外
     * 配图同口径——手帐以文字为主,配图逐张烧 token,按保守默认。
     */
    const val KEY_HANDBOOK_IMAGES = "gen_handbook_images"

    /** 隔夜记忆总结兜底(A16:闲置 8h+ 补提炼)。 */
    const val KEY_MEMORY_OVERNIGHT = "gen_memory_overnight"

    /** 离线主动消息(M1 周期扫 pickTrigger 生成 outbox + 通知)。 */
    const val KEY_PROACTIVE_OUTBOX = "gen_proactive_outbox"

    /** 旧总开关，仅用于迁移手账和小说偏好。 */
    const val KEY_DERIVATIVE_AUTO = "gen_derivative_auto"

    /** 手账和小说正文自动生成；标题发现始终独立。 */
    const val KEY_HANDBOOK_AUTO = "gen_handbook_auto"
    const val KEY_NOVEL_AUTO = "gen_novel_auto"
    private const val KEY_CONTENT_PREFS_VERSION = "content_prefs_version"

    /** 完成通知(后台生成完成的系统通知,含点击触发的;不耗 token)。 */
    const val KEY_NOTIFY_ENABLED = "gen_notify_enabled"

    /** 朋友圈每日防重复补发(上次计划执行的 epoch day)。 */
    const val KEY_MOMENTS_LAST_DAY = "gen_moments_last_day"

    /** 主动消息 outbox 通知文案(「离线期间发来 N 条」)。 */
    const val KEY_OUTBOX_COUNT = "gen_outbox_count"

    // ── 主动消息频控参数(D 工作包 D2;本地可配置默认,非任何竞品参数)──
    // 读写走 long/int 泛化入口,默认值以 ProactivePolicy.Config 为准。

    const val KEY_PROACTIVE_MIN_INTERVAL_MS = "gen_proactive_min_interval_ms"
    const val KEY_PROACTIVE_DAILY_CAP = "gen_proactive_daily_cap"
    const val KEY_PROACTIVE_QUIET_START_HOUR = "gen_proactive_quiet_start_hour"
    const val KEY_PROACTIVE_QUIET_END_HOUR = "gen_proactive_quiet_end_hour"
    const val KEY_PROACTIVE_BATCH_MAX = "gen_proactive_batch_max"
    const val KEY_PROACTIVE_SCAN_LIMIT = "gen_proactive_scan_limit"
    const val KEY_PROACTIVE_USER_ACTIVE_GRACE_MS = "gen_proactive_user_active_grace_ms"
    const val KEY_PROACTIVE_OFFLINE_THRESHOLD_MS = "gen_proactive_offline_threshold_ms"

    /** 扫描轮转游标(D2:每轮 +1,页序循环防旧会话漏扫)。 */
    const val KEY_PROACTIVE_SCAN_CURSOR = "gen_proactive_scan_cursor"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String, def: Boolean): Boolean =
        runCatching { prefs(context).getBoolean(key, def) }.getOrDefault(def)

    private fun write(context: Context, key: String, value: Boolean) {
        runCatching { prefs(context).edit().putBoolean(key, value).apply() }
    }

    /** 一次性继承旧总开关，已有的新设置绝不被覆盖。 */
    @Synchronized
    fun migrateContentPrefs(context: Context) {
        runCatching {
            val store = prefs(context)
            if (store.getInt(KEY_CONTENT_PREFS_VERSION, 0) < 1) {
                val legacy = store.getBoolean(KEY_DERIVATIVE_AUTO, false)
                val editor = store.edit()
                if (!store.contains(KEY_HANDBOOK_AUTO)) editor.putBoolean(KEY_HANDBOOK_AUTO, legacy)
                if (!store.contains(KEY_NOVEL_AUTO)) editor.putBoolean(KEY_NOVEL_AUTO, legacy)
                editor.putInt(KEY_CONTENT_PREFS_VERSION, 1).apply()
            }
        }
    }

    fun handbookAuto(context: Context): Boolean {
        migrateContentPrefs(context)
        return read(context, KEY_HANDBOOK_AUTO, false)
    }
    fun setHandbookAuto(context: Context, enabled: Boolean) {
        migrateContentPrefs(context)
        write(context, KEY_HANDBOOK_AUTO, enabled)
    }
    fun novelAuto(context: Context): Boolean {
        migrateContentPrefs(context)
        return read(context, KEY_NOVEL_AUTO, false)
    }
    fun setNovelAuto(context: Context, enabled: Boolean) {
        migrateContentPrefs(context)
        write(context, KEY_NOVEL_AUTO, enabled)
    }

    // ── 开关读写 ───────────────────────────────────────────────────

    /** 心声正文自动生成，默认关。 */
    fun innerVoiceAuto(context: Context): Boolean =
        read(context, KEY_INNER_VOICE_AUTO, false)

    fun setInnerVoiceAuto(context: Context, enabled: Boolean) =
        write(context, KEY_INNER_VOICE_AUTO, enabled)

    /** 朋友圈正文自动生成，默认关。 */
    fun momentsDaily(context: Context): Boolean =
        read(context, KEY_MOMENTS_DAILY, false)

    fun setMomentsDaily(context: Context, enabled: Boolean) =
        write(context, KEY_MOMENTS_DAILY, enabled)

    fun innerVoiceImages(context: Context): Boolean = read(context, KEY_INNER_VOICE_IMAGES, true)
    fun setInnerVoiceImages(context: Context, enabled: Boolean) = write(context, KEY_INNER_VOICE_IMAGES, enabled)
    fun momentsImages(context: Context): Boolean = read(context, KEY_MOMENTS_IMAGES, true)
    fun setMomentsImages(context: Context, enabled: Boolean) = write(context, KEY_MOMENTS_IMAGES, enabled)

    /** 番外配图(默认关;F 工作包接入前无消费方)。 */
    fun sidestoryImages(context: Context): Boolean = read(context, KEY_SIDESTORY_IMAGES, false)

    fun setSidestoryImages(context: Context, enabled: Boolean) =
        write(context, KEY_SIDESTORY_IMAGES, enabled)

    /** 手帐配图(G1;默认关,文本先出图片可选)。 */
    fun handbookImages(context: Context): Boolean = read(context, KEY_HANDBOOK_IMAGES, false)

    fun setHandbookImages(context: Context, enabled: Boolean) =
        write(context, KEY_HANDBOOK_IMAGES, enabled)

    /** 隔夜记忆总结兜底(默认关:记忆页已有手动「立即总结」兜底)。 */
    fun memoryOvernight(context: Context): Boolean =
        read(context, KEY_MEMORY_OVERNIGHT, false)

    fun setMemoryOvernight(context: Context, enabled: Boolean) =
        write(context, KEY_MEMORY_OVERNIGHT, enabled)

    /** 离线主动消息(默认开:短输出单请求,文档核心场景)。 */
    fun proactiveOutbox(context: Context): Boolean =
        read(context, KEY_PROACTIVE_OUTBOX, true)

    fun setProactiveOutbox(context: Context, enabled: Boolean) =
        write(context, KEY_PROACTIVE_OUTBOX, enabled)

    /** 手帐/小说/合照自动(默认关:M2 接入,量级高)。 */
    fun derivativeAuto(context: Context): Boolean =
        read(context, KEY_DERIVATIVE_AUTO, false)

    fun setDerivativeAuto(context: Context, enabled: Boolean) =
        write(context, KEY_DERIVATIVE_AUTO, enabled)

    /** 完成通知(默认开:0 token,拒绝系统权限时降级为仅 App 内可见)。 */
    fun notifyEnabled(context: Context): Boolean =
        read(context, KEY_NOTIFY_ENABLED, true)

    fun setNotifyEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_NOTIFY_ENABLED, enabled)

    // ── 朋友圈日戳 / outbox 计数 ─────────────────────────────────────

    /** 朋友圈上次自动发帖的 epoch day(未发过 = -1)。 */
    fun momentsLastDay(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_MOMENTS_LAST_DAY, -1L) }.getOrDefault(-1L)

    fun setMomentsLastDay(context: Context, day: Long) {
        runCatching { prefs(context).edit().putLong(KEY_MOMENTS_LAST_DAY, day).apply() }
    }

    /** outbox 本轮命中的会话数(通知文案「离线期间发来 N 条」)。 */
    fun setOutboxCount(context: Context, count: Int) {
        runCatching { prefs(context).edit().putInt(KEY_OUTBOX_COUNT, count).apply() }
    }

    fun outboxCount(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_OUTBOX_COUNT, 0) }.getOrDefault(0)

    // ── 主动消息频控参数读写(D2)────────────────────────────────────

    /** 策略显式覆盖值(未写过的键返回 null → 调用方回落 ProactivePolicy.Config 默认)。 */
    fun proactiveOverrideLong(context: Context, key: String): Long? =
        runCatching { prefs(context).getLong(key, Long.MIN_VALUE) }
            .getOrDefault(Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }

    fun proactiveOverrideInt(context: Context, key: String): Int? =
        runCatching { prefs(context).getInt(key, Int.MIN_VALUE) }
            .getOrDefault(Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }

    fun setProactiveOverride(context: Context, key: String, value: Long) {
        runCatching { prefs(context).edit().putLong(key, value).apply() }
    }

    fun setProactiveOverride(context: Context, key: String, value: Int) {
        runCatching { prefs(context).edit().putInt(key, value).apply() }
    }

    /** 扫描轮转游标(读+推进;轮转算法在 ProactivePolicy.nextScanOffset)。 */
    fun proactiveScanCursor(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_PROACTIVE_SCAN_CURSOR, 0) }.getOrDefault(0)

    fun advanceProactiveScanCursor(context: Context) {
        runCatching { prefs(context).edit().putInt(KEY_PROACTIVE_SCAN_CURSOR, proactiveScanCursor(context) + 1).apply() }
    }
}

package com.neethu.gouxiang.ui.innervoice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 心声详情页背景音乐:进入详情立即随机播 assets/bgm 下的一首(循环),
 * 离开释放。TTS 朗读时压低音量([setDucked]),停读恢复,不打断播放。
 *
 * 仅前台短时播放,不申请 audio focus(工程无先例,详情页退出即停);
 * assets/bgm 无曲目或播放失败静默降级为无 BGM(详情页其余功能不受影响)。
 */
class InnerVoiceBgmPlayer(context: Context) {

    /** 正常伴奏音量 / 朗读压低音量(相对媒体总音量的比例)。 */
    private val normalVolume = 0.35f
    private val duckedVolume = 0.12f

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var ducked = false

    init {
        start()
    }

    private fun start() {
        try {
            val names = appContext.assets.list(BGM_DIR)?.filter { it.endsWith(".mp3", ignoreCase = true) }.orEmpty()
            if (names.isEmpty()) return
            val picked = names.random()
            val mp = MediaPlayer()
            // 保存实例后再配置，确保任何一步失败都能释放播放器。
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            appContext.assets.openFd("$BGM_DIR/$picked").use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.isLooping = true
            mp.setOnPreparedListener {
                if (player === it) {
                    val volume = if (ducked) duckedVolume else normalVolume
                    it.setVolume(volume, volume)
                    it.start()
                }
            }
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "bgm playback failed: $what/$extra")
                if (player === failed) releasePlayer()
                true
            }
            mp.prepareAsync()
        } catch (t: Exception) {
            Log.w(TAG, "bgm start failed", t)
            releasePlayer()
        }
    }

    /** TTS 朗读联动:压低(true)/恢复(false)音量;未在播时为空操作。 */
    fun setDucked(value: Boolean) {
        if (ducked == value) return
        ducked = value
        val mp = player ?: return
        val v = if (value) duckedVolume else normalVolume
        try {
            mp.setVolume(v, v)
        } catch (_: Exception) {
        }
    }

    /** 释放(离开详情页;幂等)。 */
    fun release() {
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

    private companion object {
        const val TAG = "InnerVoiceBgm"
        const val BGM_DIR = "bgm"
    }
}

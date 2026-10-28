package com.example.camwall

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 一个画面格子：RTSP 播放 + 名称 + 状态提示。
 * 断线自动 5 秒重连；画面卡住 30 秒也会自动重连，适合 24 小时挂着。
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
class CameraCell(context: Context) : FrameLayout(context) {

    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
    }
    private val status = TextView(context).apply {
        setTextColor(0xFFBBBBBB.toInt())
        textSize = 13f
        gravity = Gravity.CENTER
    }

    private var player: ExoPlayer? = null
    private var device: Device? = null
    private var useSub = false
    @Volatile
    private var lastFrameAt = 0L

    init {
        setBackgroundColor(Color.BLACK)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        )
    }

    fun bind(d: Device?, sub: Boolean) {
        release()
        device = d
        useSub = sub
        if (d == null) {
            label.text = ""
            status.text = "—"
            status.visibility = VISIBLE
            return
        }
        label.text = d.name.ifBlank { d.ip }
        startPlayer()
    }

    private fun startPlayer() {
        val d = device ?: return
        status.text = "连接中…"
        status.visibility = VISIBLE

        // 小缓冲 = 低延迟
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250)
            .build()
        val p = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)   // RTP over TCP，WiFi 下更稳，不花屏
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(d.rtspUrl(useSub)))

        p.volume = 0f
        p.addListener(listener)
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        playerView.player = p
        player = p

        // 每渲染一帧记一下时间，用来判断画面是否卡死
        lastFrameAt = SystemClock.uptimeMillis()
        p.setVideoFrameMetadataListener { _, _, _, _ -> lastFrameAt = SystemClock.uptimeMillis() }
        postDelayed(watchdog, WATCHDOG_MS)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> status.visibility = GONE
                Player.STATE_ENDED -> scheduleRetry("视频流已结束")
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry(error.errorCodeName)
        }
    }

    private val retry = Runnable {
        releasePlayer()
        startPlayer()
    }

    /** 每 10 秒检查一次：超过 30 秒没有新画面帧就重连 */
    private val watchdog = object : Runnable {
        override fun run() {
            if (player == null) return
            if (SystemClock.uptimeMillis() - lastFrameAt > STALL_MS) {
                scheduleRetry("画面无更新")
                return
            }
            postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun scheduleRetry(reason: String) {
        removeCallbacks(retry)
        removeCallbacks(watchdog)
        status.text = "连接失败：$reason\n5 秒后重试"
        status.visibility = VISIBLE
        postDelayed(retry, RETRY_MS)
    }

    private fun releasePlayer() {
        removeCallbacks(watchdog)
        playerView.player = null
        player?.removeListener(listener)
        player?.release()
        player = null
    }

    fun release() {
        removeCallbacks(retry)
        releasePlayer()
    }

    companion object {
        private const val WATCHDOG_MS = 10_000L
        private const val RETRY_MS = 5_000L
        private const val STALL_MS = 30_000L
    }
}

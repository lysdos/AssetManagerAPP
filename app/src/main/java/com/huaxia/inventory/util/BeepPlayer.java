package com.huaxia.inventory.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;

/**
 * 短促确认音播放. 用 SoundPool 加载一段 ogg, 每次 play() 播一次.
 *
 * <p>用途: RFID/手动盘点页扫到一个标签后给操作员一个可听反馈.
 * 用 {@link #init(Context, int)} 加载音频, 在 Fragment onDestroyView 调 {@link #release()}.</p>
 *
 * <p>音量跟随媒体流 STREAM_MUSIC 当前音量 (跟旧版 ScanFragment 行为一致).</p>
 */
public class BeepPlayer {

    private static final int MAX_STREAMS = 2;

    private SoundPool soundPool;
    private int soundId;
    private float volumeRatio = 0.5f;

    /** 加载音频资源. 重复调用是 no-op (已加载直接返回). */
    public void init(@NonNull Context ctx, @RawRes int rawResId) {
        if (soundPool != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(attrs)
                .build();
        soundId = soundPool.load(ctx, rawResId, 1);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            float max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeRatio = max > 0 ? cur / max : 0.5f;
        }
    }

    /** 播放一次. 未 init 时是 no-op. */
    public void play() {
        if (soundPool != null) {
            soundPool.play(soundId, volumeRatio, volumeRatio, 1, 0, 1f);
        }
    }

    /** 释放 SoundPool. 重复调用安全. */
    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
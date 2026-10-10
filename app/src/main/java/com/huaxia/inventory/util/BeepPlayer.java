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

    /**
     * {@code volatile}: {@link #play()} 在 RFID poll 线程调用, {@link #init(Context, int)}
     * /{@link #release()} 在主线程 — 跨线程可见性, 且 play 里取本地快照避免 NPE.
     */
    private volatile SoundPool soundPool;
    private int soundId;
    private float volumeRatio = 0.5f;

    /** 加载音频资源. 重复调用是 no-op (已加载直接返回). */
    public void init(@NonNull Context ctx, @RawRes int rawResId) {
        if (soundPool != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        SoundPool sp = new SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(attrs)
                .build();
        int sid = sp.load(ctx, rawResId, 1);
        float vol = 0.5f;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            float max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            vol = max > 0 ? cur / max : 0.5f;
        }
        // 先写普通字段, 最后 volatile 写 soundPool — 借 volatile 的 release 语义
        // 让 soundId / volumeRatio 对 play()(RFID 线程) 可见.
        soundId = sid;
        volumeRatio = vol;
        soundPool = sp;
    }

    /** 播放一次. 未 init 时是 no-op. */
    public void play() {
        // 本地快照: release() 可能在本行之后把 soundPool 置 null, 本地引用消除
        // check-then-use 之间的 NPE (play 在 RFID poll 线程高频调用).
        SoundPool sp = soundPool;
        if (sp != null) {
            sp.play(soundId, volumeRatio, volumeRatio, 1, 0, 1f);
        }
    }

    /** 释放 SoundPool. 重复调用安全. */
    public void release() {
        SoundPool sp = soundPool;
        if (sp != null) {
            soundPool = null;   // 先置 null, 让并发 play() 快照到 null 而非正在释放的实例
            sp.release();
        }
    }
}
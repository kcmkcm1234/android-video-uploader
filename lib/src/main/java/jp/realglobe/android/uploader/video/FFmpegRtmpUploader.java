/*----------------------------------------------------------------------
 * Copyright 2017 realglobe Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *----------------------------------------------------------------------*/

package jp.realglobe.android.uploader.video;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import jp.realglobe.android.function.Consumer;

/**
 * FFmpeg を使って生の H264 映像を RTMP でアップロードする。
 * Created by fukuchidaisuke on 17/06/29.
 */
public final class FFmpegRtmpUploader {

    private static final String TAG = FFmpegRtmpUploader.class.getName();

    private final PipedFFmpegRunner runner;

    public FFmpegRtmpUploader() {
        this.runner = new PipedFFmpegRunner();
    }

    public synchronized boolean isRunning() {
        return this.runner.isRunning();
    }

    /**
     * 動かす。
     * 既に動いてたら何もしない
     *
     * @param ffmpeg   FFmpeg の実行可能バイナリ
     * @param url      アップロード先 URL
     * @param onError  エラー時に実行される関数
     * @param capacity バッファサイズ
     * @return 動かしたら true
     * @throws IOException FFmpeg の実行エラー
     */
    public synchronized boolean start(@NonNull File ffmpeg, @NonNull String url, @Nullable Consumer<Exception> onError, long capacity) throws IOException {
        return this.runner.start(ffmpeg, new String[]{
                "-loglevel", "error",
                "-f", "h264",
                "-i", "pipe:0",
                "-c:v", "copy",
                "-an",
                "-f", "flv",
                url,
        }, onError, capacity);
    }

    /**
     * 止める。
     * 既に止まってたら何もしない
     *
     * @return 止めたら true
     */
    public synchronized boolean stop() {
        return this.runner.stop();
    }

    /**
     * 映像をアップロードする
     *
     * @param data 生の H264 映像データ
     * @return バッファをクリアしたら true
     */
    public synchronized boolean sendVideo(@NonNull byte[] data) {
        return this.runner.write(data);
    }

}

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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import jp.realglobe.android.logger.simple.Log;

/**
 * FFmpeg を使って生の H264 映像を RTMP でアップロードする。
 * Created by fukuchidaisuke on 17/06/29.
 */
public final class FFmpegRtmpUploader implements VideoUploader {

    private static final String TAG = FFmpegRtmpUploader.class.getName();

    /**
     * FFmpeg に映像データを送る
     */
    private static final class Writer extends Handler implements Closeable {

        private static final int MSG_DATA = 0;

        private final OutputStream output;
        @NonNull
        private final Consumer<Exception> onError;
        private final long capacity;
        private final long threshold;

        private final AtomicLong size;
        private boolean dropping;

        Writer(@NonNull Looper looper, @NonNull OutputStream output, @Nullable Consumer<Exception> onError, long capacity, long threshold) {
            super(looper);

            this.output = output;
            this.onError = (onError != null ? onError : (Exception e) -> Log.e(TAG, "Error occurred", e));
            this.capacity = capacity;
            this.threshold = threshold;

            this.size = new AtomicLong(0L);
            this.dropping = false;
        }

        void sendVideo(@NonNull byte[] data) {
            final long currentSize = this.size.getAndAdd(data.length);

            if (currentSize >= this.capacity && !this.dropping) {
                Log.w(TAG, "Start dropping");
                this.dropping = true;
            }

            if (this.dropping) {
                if (currentSize >= this.threshold) {
                    // 捨てる
                    this.size.addAndGet(-data.length);
                    return;
                }
                Log.w(TAG, "Stop dropping");
                this.dropping = false;
            }
            sendMessage(obtainMessage(Writer.MSG_DATA, data));
        }

        @Override
        public void close() throws IOException {
            this.output.close();
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_DATA: {
                        upload((byte[]) msg.obj);
                        break;
                    }
                }
            } catch (IOException e) {
                this.onError.accept(e);
            }
        }

        private void upload(@NonNull byte[] data) throws IOException {
            this.size.addAndGet(-data.length);
            output.write(data);
        }

    }

    /**
     * FFmpeg の出力を読む
     */
    private static final class Reader extends Handler implements Closeable {

        private final BufferedReader input;
        private final Consumer<String> onLineRead;
        @NonNull
        private final Consumer<Exception> onError;

        Reader(@NonNull Looper looper, @NonNull BufferedReader input, @Nullable Consumer<String> onLineRead, @Nullable Consumer<Exception> onError) {
            super(looper);

            this.input = input;
            this.onLineRead = onLineRead;
            this.onError = (onError != null ? onError : (Exception e) -> Log.e(TAG, "Error occurred", e));
        }

        void start() {
            post(() -> {
                try {
                    while (true) {
                        final String line = input.readLine();
                        if (line == null) {
                            break;
                        }
                        if (this.onLineRead != null) {
                            this.onLineRead.accept(line);
                        }
                    }
                } catch (IOException e) {
                    this.onError.accept(e);
                }
            });
        }

        @Override
        public void close() throws IOException {
            this.input.close();
        }

    }

    @NonNull
    private final Consumer<Exception> onError;

    private volatile boolean finish;
    private final Writer writer;
    private final Reader stdoutReader;
    private final Reader stderrReader;

    public FFmpegRtmpUploader(File ffmpeg, String url, Consumer<Exception> onError, long capacity, long threshold) throws IOException {

        this.onError = (onError != null ? onError : (Exception e) -> Log.e(TAG, "FFmpeg error occurred", e));

        this.finish = false;

        final String[] command = new String[]{
                ffmpeg.getAbsolutePath(),
                "-loglevel", "error",
                "-f", "h264",
                "-i", "pipe:0",
                "-c:v", "copy",
                "-an",
                "-f", "flv",
                url,
        };
        Log.v(TAG, "Execute " + Arrays.toString(command));
        final Process process = new ProcessBuilder(command).start();

        final HandlerThread writerThread = new HandlerThread(getClass().getName() + ":writer");
        final HandlerThread stdoutThread = new HandlerThread(getClass().getName() + ":stdout");
        final HandlerThread stderrThread = new HandlerThread(getClass().getName() + ":stderr");
        writerThread.start();
        stdoutThread.start();
        stderrThread.start();

        this.writer = new Writer(writerThread.getLooper(), new BufferedOutputStream(process.getOutputStream()), (Exception e) -> {
            if (!this.finish) {
                this.onError.accept(e);
            }
        }, capacity, threshold);
        this.stdoutReader = new Reader(stdoutThread.getLooper(), new BufferedReader(new InputStreamReader(process.getInputStream())), (String line) -> Log.v(TAG, "FFmpeg stdout: " + line), (Exception e) -> {
            if (!this.finish) {
                Log.w(TAG, "Reading ffmpeg stdout failed", e);
            }
        });
        this.stderrReader = new Reader(stderrThread.getLooper(), new BufferedReader(new InputStreamReader(process.getErrorStream())), (String line) -> Log.w(TAG, "FFmpeg stderr: " + line), (Exception e) -> {
            if (!this.finish) {
                Log.w(TAG, "Reading ffmpeg stderr failed", e);
            }
        });
        this.stdoutReader.start();
        this.stderrReader.start();
    }

    @Override
    public void sendVideo(@NonNull byte[] data) {
        this.writer.sendVideo(data);
    }

    @Override
    public void close() {
        this.finish = true;
        this.writer.getLooper().quitSafely();
        this.stdoutReader.getLooper().quitSafely();
        this.stderrReader.getLooper().quitSafely();
        close(this.writer);
        close(this.stdoutReader);
        close(this.stderrReader);
    }

    private void close(@NonNull Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.e(TAG, "Closing error", e);
        }
    }

}

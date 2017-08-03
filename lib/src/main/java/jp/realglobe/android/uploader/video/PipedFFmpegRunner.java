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

import jp.realglobe.android.function.Consumer;
import jp.realglobe.android.logger.simple.Log;

/**
 * 後から映像データを渡せるようにパイプして FFmpeg を実行する。
 * Created by fukuchidaisuke on 17/08/03.
 */
public class PipedFFmpegRunner {

    private static final String TAG = PipedFFmpegRunner.class.getName();

    /**
     * FFmpeg にデータを渡す
     */
    private static final class Writer extends Handler implements Closeable {

        private static final int MSG_DATA = 0;

        private final OutputStream output;
        @NonNull
        private final Consumer<Exception> onError;
        private final long capacity;

        private final AtomicLong size;

        private volatile boolean closed;

        private Writer(@NonNull Looper looper, @NonNull OutputStream output, @Nullable Consumer<Exception> onError, long capacity) {
            super(looper);

            this.output = output;
            this.onError = (onError != null ? onError : (Exception e) -> Log.e(TAG, "Error occurred", e));
            this.capacity = capacity;

            this.size = new AtomicLong(0L);

            this.closed = false;
        }

        private boolean write(@NonNull byte[] data) {
            final long currentSize = this.size.getAndAdd(data.length);

            boolean clear = (currentSize >= this.capacity);
            if (clear) {
                removeMessages(MSG_DATA);
                Log.w(TAG, (currentSize - data.length) + " bytes were dropped");
                this.size.set(data.length);
            }

            sendMessage(obtainMessage(MSG_DATA, data));
            return clear;
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
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
                if (!this.closed) {
                    this.onError.accept(e);
                }
            }
        }

        private void upload(@NonNull byte[] data) throws IOException {
            this.size.addAndGet(-data.length);
            this.output.write(data);
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

        private volatile boolean closed;

        private Reader(@NonNull Looper looper, @NonNull BufferedReader input, @Nullable Consumer<String> onLineRead, @Nullable Consumer<Exception> onError) {
            super(looper);

            this.input = input;
            this.onLineRead = onLineRead;
            this.onError = (onError != null ? onError : (Exception e) -> Log.e(TAG, "Error occurred", e));

            this.closed = false;
        }

        private void start() {
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
                    if (!this.closed) {
                        this.onError.accept(e);
                    }
                }
            });
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            this.input.close();
        }

    }

    public PipedFFmpegRunner() {
    }

    private Process process;
    private Writer writer;
    private Reader stdoutReader;
    private Reader stderrReader;

    /**
     * @return 動いてたら true
     */
    public synchronized boolean isRunning() {
        return this.writer != null;
    }

    /**
     * 動かす。
     * 既に動いてたら何もしない
     *
     * @param ffmpeg   FFmpeg の実行可能バイナリ
     * @param args     FFmpeg の実行引数
     * @param onError  エラー時に実行される関数
     * @param capacity バッファサイズ
     * @return 動かしたら true
     * @throws IOException FFmpeg の実行エラー
     */
    public synchronized boolean start(@NonNull File ffmpeg, @NonNull String[] args, @Nullable Consumer<Exception> onError, long capacity) throws IOException {
        if (this.writer != null) {
            return false;
        }

        final String[] command = new String[1 + args.length];
        command[0] = ffmpeg.getAbsolutePath();
        System.arraycopy(args, 0, command, 1, args.length);
        Log.v(TAG, "Execute " + Arrays.toString(command));
        this.process = new ProcessBuilder(command).start();

        // パイプ書き込みはブロックすることも多いので自前のスレッドを使う
        final HandlerThread writerThread = new HandlerThread(getClass().getName() + ":writer");
        // パイプ読み込みはブロックするので自前のスレッドを使う
        final HandlerThread stdoutThread = new HandlerThread(getClass().getName() + ":stdout");
        final HandlerThread stderrThread = new HandlerThread(getClass().getName() + ":stderr");

        writerThread.start();
        stdoutThread.start();
        stderrThread.start();

        this.writer = new Writer(writerThread.getLooper(), new BufferedOutputStream(this.process.getOutputStream()), (onError != null ? onError : (Exception e) -> Log.e(TAG, "Writing data to piped ffmpeg failed", e)), capacity);
        this.stdoutReader = new Reader(stdoutThread.getLooper(), new BufferedReader(new InputStreamReader(process.getInputStream())), (String line) -> Log.v(TAG, "FFmpeg stdout: " + line), (Exception e) -> Log.w(TAG, "Reading ffmpeg stdout failed", e));
        this.stderrReader = new Reader(stderrThread.getLooper(), new BufferedReader(new InputStreamReader(process.getErrorStream())), (String line) -> Log.w(TAG, "FFmpeg stderr: " + line), (Exception e) -> Log.w(TAG, "Reading ffmpeg stderr failed", e));
        this.stdoutReader.start();
        this.stderrReader.start();

        return true;
    }

    /**
     * 止める。
     * 既に止まってたら何もしない
     *
     * @return 止めたら true
     */
    public synchronized boolean stop() {
        if (this.writer == null) {
            return false;
        }

        this.writer.getLooper().quit();
        this.stdoutReader.getLooper().quit();
        this.stderrReader.getLooper().quit();

        closeWithoutException(this.writer);
        closeWithoutException(this.stdoutReader);
        closeWithoutException(this.stderrReader);

        this.process.destroy();

        this.writer = null;
        this.stdoutReader = null;
        this.stderrReader = null;
        return true;
    }

    private void closeWithoutException(@NonNull Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.e(TAG, "Closing error", e);
        }
    }

    /**
     * データをパイプに書き込む
     *
     * @param data データ
     * @return バッファをクリアしたら true
     */
    public synchronized boolean write(@NonNull byte[] data) {
        if (this.writer == null) {
            return false;
        }
        return this.writer.write(data);
    }

}

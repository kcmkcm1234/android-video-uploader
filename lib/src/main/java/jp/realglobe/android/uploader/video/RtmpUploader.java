package jp.realglobe.android.uploader.video;

import android.media.MediaCodec;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.ossrs.rtmp.ConnectCheckerRtmp;
import net.ossrs.rtmp.SrsFlvMuxer;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import jp.realglobe.android.logger.simple.Log;
import jp.realglobe.lib.util.ShutdownHookableRunner;

/**
 * 生の H264 を RTMP でアップロードする。
 * Created by fukuchidaisuke on 17/06/23.
 */
public final class RtmpUploader extends ShutdownHookableRunner {

    private static final String TAG = RtmpUploader.class.getName();

    private static final int BUFFER_SIZE = 1 << 25; // 32MB

    private final VideoQueue queue;
    private final String url;

    public RtmpUploader(@NonNull VideoQueue queue, @NonNull String url, @Nullable Runnable shutdownHook) {
        super(shutdownHook);

        this.queue = queue;
        this.url = url;
    }

    @Override
    protected void runCore() {
        final BlockingQueue<Boolean> success = new ArrayBlockingQueue<>(1);

        final SrsFlvMuxer muxer = new SrsFlvMuxer(new ConnectCheckerRtmp() {
            @Override
            public void onConnectionSuccessRtmp() {
                Log.v(TAG, "Connection success");
                success.offer(true);
            }

            @Override
            public void onConnectionFailedRtmp() {
                Log.e(TAG, "Connection failed");
                success.offer(false);
            }

            @Override
            public void onDisconnectRtmp() {
                Log.e(TAG, "Disconnected");
                success.offer(false);
            }

            @Override
            public void onAuthErrorRtmp() {
                Log.e(TAG, "Auth error");
                success.offer(false);
            }

            @Override
            public void onAuthSuccessRtmp() {
                Log.v(TAG, "Auth success");
            }
        });
        muxer.start(this.url);

        try {
            if (!success.take()) {
                return;
            }

            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (!Thread.currentThread().isInterrupted()) {
                final byte[] data = this.queue.take();

                buffer.clear();
                buffer.put(data);
                buffer.flip();
                final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.size = data.length;
//                info.set(buffer.arrayOffset(), data.length, System.currentTimeMillis(), 0);

                muxer.sendVideo(buffer, info);
            }
        } catch (InterruptedException e) {
            // 終了
        } finally {
            muxer.stop();
        }
    }

}

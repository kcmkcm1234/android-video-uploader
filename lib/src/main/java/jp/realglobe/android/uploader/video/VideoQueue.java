package jp.realglobe.android.uploader.video;

import android.support.annotation.NonNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 映像データの処理で描画スレッドを止めないように、データを他のスレッドに渡すためのキュー。
 * 飛び飛びでも映像の体をなすように、容量付近まで詰まると、閾値付近まで空くまでデータを捨てる。
 * データを渡すスレッドは 1 つでなければならない。
 * PipedInputStream/PipedOutputStream での受け渡しが遅すぎたのでこれにした。
 * Created by fukuchidaisuke on 17/03/28.
 */
public final class VideoQueue {

    private final long capacity;
    private final long threshold;
    private final BlockingQueue<byte[]> queue;
    private final AtomicLong size;

    // データを捨てるかどうか
    // データを渡すスレッドは 1 つなので同期する必要は無い
    private boolean dropping;

    /**
     * @param capacity  容量（バイト）
     * @param threshold データの受け渡しを再開するサイズ（バイト）
     */
    public VideoQueue(long capacity, long threshold) {
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity");
        } else if (threshold < 0) {
            throw new IllegalArgumentException("negative threshold");
        } else if (capacity < threshold) {
            throw new IllegalArgumentException("capacity less than threshold");
        }
        this.capacity = capacity;
        this.threshold = threshold;
        this.queue = new LinkedBlockingQueue<>();
        this.size = new AtomicLong(0L);
        this.dropping = false;
    }


    /**
     * データを受け取る。
     * 受け取れるデータが無いときは、データが渡されるまで待つ
     *
     * @return データ
     * @throws InterruptedException 割り込まれた場合
     */
    @NonNull
    public byte[] take() throws InterruptedException {
        final byte[] data = this.queue.take();
        this.size.addAndGet(-data.length);
        return data;
    }

    /**
     * データを渡す。
     *
     * @param data データ
     * @return データを捨てたときのみ false
     */
    public boolean offer(@NonNull byte[] data) {
        final long currentSize = this.size.getAndAdd(data.length);

        if (currentSize >= this.capacity) {
            this.dropping = true;
        }

        if (this.dropping) {
            if (currentSize >= this.threshold) {
                // 捨てる
                this.size.addAndGet(-data.length);
                return false;
            }
            this.dropping = false;
        }
        this.queue.offer(data);
        return true;
    }

}

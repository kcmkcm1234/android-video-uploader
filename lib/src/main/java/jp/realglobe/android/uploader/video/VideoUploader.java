package jp.realglobe.android.uploader.video;

import android.support.annotation.NonNull;

import java.io.Closeable;

/**
 * 映像をアップロードする。
 * Created by fukuchidaisuke on 17/06/29.
 */
public interface VideoUploader extends Closeable {

    /**
     * 映像をアップロードする
     *
     * @param data 映像データ
     */
    void sendVideo(@NonNull byte[] data);

}

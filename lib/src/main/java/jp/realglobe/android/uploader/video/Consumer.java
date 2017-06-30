package jp.realglobe.android.uploader.video;

/**
 * TODO Android Studio 3.0 からは java.util.function.Consumer が使えるっぽい
 */
public interface Consumer<T> {
    void accept(T t);
}

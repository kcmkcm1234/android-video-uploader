package jp.realglobe.android.uploader.video.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import java.io.File;

import jp.realglobe.android.util.PreferenceUtils;

/**
 * 設定。
 * Created by fukuchidaisuke on 17/06/29.
 */
final class Setting {

    private static final String KEY_VIDEO_PATH = "videoPath";
    private static final String KEY_UPLOAD_URL = "uploadUrl";

    private final String videoPath;
    private final String uploadUrl;

    Setting(@NonNull String videoPath, @NonNull String uploadUrl) {
        this.videoPath = videoPath;
        this.uploadUrl = uploadUrl;
    }

    @NonNull
    static Setting load(@NonNull Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return new Setting(
                PreferenceUtils.getNonEmptyString(preferences, KEY_VIDEO_PATH, (new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), context.getString(R.string.dummy_video_file))).getAbsolutePath()),
                PreferenceUtils.getNonEmptyString(preferences, KEY_UPLOAD_URL, context.getString(R.string.dummy_upload_url))
        );
    }

    void save(@NonNull Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit()
                .putString(KEY_VIDEO_PATH, this.videoPath)
                .putString(KEY_UPLOAD_URL, this.uploadUrl)
                .apply();
    }

    String getVideoPath() {
        return this.videoPath;
    }

    String getUploadUrl() {
        return this.uploadUrl;
    }

}

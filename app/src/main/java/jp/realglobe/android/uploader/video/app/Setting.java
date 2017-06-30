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

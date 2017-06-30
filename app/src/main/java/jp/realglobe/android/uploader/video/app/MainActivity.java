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

import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import jp.realglobe.android.logger.simple.Log;
import jp.realglobe.android.uploader.video.FFmpegHelper;
import jp.realglobe.android.uploader.video.FFmpegRtmpUploader;
import jp.realglobe.android.uploader.video.VideoUploader;
import jp.realglobe.android.util.BaseActivity;

public class MainActivity extends BaseActivity {

    private static final String TAG = MainActivity.class.getName();

    private static final long QUEUE_CAPACITY = 1 << 23; // 8MB
    private static final long QUEUE_THRESHOLD = 1 << 20; // 1MB
    private static final int DATA_SIZE = (1_000 << 10) / 8; // 1000Kb
    private static final long READ_INTERVAL = 100; // ミリ秒

    private static final String[] PERMISSIONS = new String[]{
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    private EditText editPath;
    private EditText editUrl;
    private Button buttonStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUi();

        checkPermission(PERMISSIONS, makePermissionRequestCallback(() -> {
        }, (String[] denied) -> {
            showToast(getString(R.string.notification_permissions));
            Log.w(TAG, Arrays.toString(denied) + " are denied");
        }));
    }

    private void initUi() {
        this.editPath = (EditText) findViewById(R.id.edit_path);
        this.editUrl = (EditText) findViewById(R.id.edit_url);
        this.buttonStart = (Button) findViewById(R.id.button_start);

        loadPreferences();

        this.buttonStart.setOnClickListener((View v) -> {
            this.buttonStart.setEnabled(false);
            savePreferences();
            start();
        });
    }

    /**
     * 設定を読み込む
     */
    private void loadPreferences() {
        final Setting setting = Setting.load(getApplicationContext());
        this.editPath.setText(setting.getVideoPath());
        this.editUrl.setText(setting.getUploadUrl());
    }

    /**
     * 設定を保存する
     */
    private void savePreferences() {
        (new Setting(
                this.editPath.getText().toString(),
                this.editUrl.getText().toString()
        )).save(getApplicationContext());
    }


    private void start() {
        FFmpegHelper.asyncDownload(getString(R.string.ffmpeg_root_url), getDownloadDir(), false, 30_000, (File ffmpeg) -> runOnUiThread(() -> {
            if (ffmpeg == null) {
                showToast("対応するFFmpegを用意できませんでした");
                this.buttonStart.setEnabled(true);
                return;
            }
            try {
                upload(ffmpeg);
            } catch (IOException e) {
                Log.e(TAG, "Upload error", e);
                showToast("アップロードに失敗しました");
                this.buttonStart.setEnabled(true);
            }
        }), (Exception e) -> runOnUiThread(() -> {
            Log.e(TAG, "Downloading FFmpeg failed", e);
            showToast("FFmpegのダウンロードに失敗しました");
            this.buttonStart.setEnabled(true);
        }));
    }

    @NonNull
    private File getDownloadDir() {
        // 外部ストレージは実行権限を付けられないらしい
//        final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
//        if (dir != null) {
//            return dir;
//        }
        return getApplicationContext().getFilesDir().getAbsoluteFile();
    }

    private void upload(File ffmpeg) throws IOException {
        final Setting setting = Setting.load(getApplicationContext());

        final AtomicBoolean running = new AtomicBoolean(true);

        final VideoUploader uploader = new FFmpegRtmpUploader(ffmpeg, setting.getUploadUrl(), (Exception e) -> runOnUiThread(() -> {
            running.set(false);
            this.buttonStart.setEnabled(true);
        }), QUEUE_CAPACITY, QUEUE_THRESHOLD);
        final InputStream input = new BufferedInputStream(new FileInputStream(setting.getVideoPath()));

        final HandlerThread thread = new HandlerThread(getClass().getName());
        thread.start();

        final Handler handler = new Handler(thread.getLooper());

        final byte[] buff = new byte[DATA_SIZE];
        final Runnable step = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!running.get()) {
                        input.close();
                        uploader.close();
                        MainActivity.this.buttonStart.post(() -> MainActivity.this.buttonStart.setEnabled(true));
                        return;
                    }

                    final int size = input.read(buff);
                    if (size <= 0) {
                        input.close();
                        uploader.close();
                        MainActivity.this.buttonStart.post(() -> MainActivity.this.buttonStart.setEnabled(true));
                        return;
                    }
                    uploader.sendVideo(Arrays.copyOf(buff, size));
                } catch (IOException e) {
                    Log.e(TAG, "Reading " + setting.getVideoPath() + " failed", e);
                    runOnUiThread(() -> {
                        showToast("映像データの読み取りに失敗しました");
                        MainActivity.this.buttonStart.setEnabled(true);
                    });
                    return;
                }

                handler.postDelayed(this, READ_INTERVAL);
            }
        };

        handler.post(step);
    }
}

package jp.realglobe.android.uploader.video.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import jp.realglobe.android.uploader.video.RtmpUploader;
import jp.realglobe.android.uploader.video.VideoQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();

    private static final String KEY_PATH = "path";
    private static final String KEY_URL = "url";

    private static final long QUEUE_CAPACITY = 1 << 23; // 8MB
    private static final long QUEUE_THRESHOLD = 1 << 20; // 1MB
    private static final int DATA_SIZE = (1_000 << 10) / 8; // 1000Kb
    private static final long READ_INTERVAL = 100; // ミリ秒

    private static final String[] PERMISSIONS = new String[]{
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };
    private static final int PERMISSION_REQUEST_CODE = 30236;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkPermission();
    }

    /**
     * 権限を確認する
     */
    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_CODE);
        } else {
            start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.notification_permissions, Toast.LENGTH_LONG).show();
                Log.w(TAG, permissions[i] + " are denied");
                return;
            }
        }

        start();
    }

    private void start() {
        final EditText editPath = (EditText) findViewById(R.id.edit_path);
        final EditText editUrl = (EditText) findViewById(R.id.edit_url);
        final Button buttonStart = (Button) findViewById(R.id.button_start);

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editPath.setText(preferences.getString(KEY_PATH, (new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "video.h264")).getAbsolutePath()));
        editUrl.setText(preferences.getString(KEY_URL, "rtmp://example.com/live/0"));

        final ExecutorService executor = Executors.newCachedThreadPool();

        buttonStart.setOnClickListener((View v) -> {
            buttonStart.setEnabled(false);

            final String path = editPath.getText().toString();
            final String url = editUrl.getText().toString();

            preferences.edit().putString(KEY_PATH, path).putString(KEY_URL, url).apply();

            final AtomicBoolean running = new AtomicBoolean(true);
            final VideoQueue queue = new VideoQueue(QUEUE_CAPACITY, QUEUE_THRESHOLD);

            final RtmpUploader uploader = new RtmpUploader(queue, url, () -> {
                running.set(false);
                runOnUiThread(() -> buttonStart.setEnabled(true));
            });
            final Future<?> future = executor.submit(uploader);
            executor.submit(() -> {
                try (final InputStream input = new BufferedInputStream(new FileInputStream(path))) {
                    final byte[] buff = new byte[DATA_SIZE];
                    while (running.get()) {
                        final int size = input.read(buff);
                        if (size <= 0) {
                            break;
                        }
                        queue.offer(Arrays.copyOf(buff, size));
                        Thread.sleep(READ_INTERVAL);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "File error", e);
                } catch (InterruptedException e) {
                    // 終了
                } finally {
                    future.cancel(true);
                }
            });
        });
    }
}

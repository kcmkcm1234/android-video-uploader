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

import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import jp.realglobe.android.logger.simple.Log;

/**
 * FFmpeg を準備する。
 * Created by fukuchidaisuke on 17/06/29.
 */
public final class FFmpegHelper {

    private static final String TAG = FFmpegHelper.class.getName();

    private static final int BUFFER_SIZE = 1 << 13; // 8K

    /**
     * FFmpeg をダウンロードする。
     *
     * @param urlRoot   ダウンロード元の URL。この下に {CPUアーキテクチャ}/ffmpeg でバイナリが置いてある
     * @param saveDir   保存先。直下に ffmpeg としてバイナリを配置する
     * @param overwrite 上書きするか。false かつ既に保存先に ffmpeg ファイルが存在する場合、何もしない
     * @param timeout   接続タイムアウト（ミリ秒）
     * @return FFmpeg のパス。対応する FFmpeg をダウンロードできなかった場合は null
     */
    @Nullable
    public static File download(@NonNull String urlRoot, @NonNull File saveDir, boolean overwrite, int timeout) throws IOException, URISyntaxException {

        final File savePath = new File(saveDir, "ffmpeg");
        if (!overwrite && savePath.exists()) {
            return savePath;
        }

        for (String architecture : getArchitectures()) {
            final String url = addUrlPath(urlRoot, "/" + architecture + "/ffmpeg");
            if (download(new URL(url), savePath, timeout)) {
                break;
            }
        }

        if (!savePath.exists()) {
            // 失敗
            return null;
        } else if (!savePath.canExecute() && savePath.setExecutable(true)) {
            Log.e(TAG, "Cannot set executable permission to " + savePath);
            return null;
        }
        return savePath;
    }

    private static String addUrlPath(String base, String subPath) throws MalformedURLException, URISyntaxException {
        // 普通にやると % 周りが勝手にデコードされてしまうので頑張る
        final URI uri = new URI(base);

        String url = uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() + "/" + subPath).replaceAll("/+", "/");
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            url += "?" + uri.getRawQuery();
        }
        if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
            url += "#" + uri.getRawFragment();
        }
        return url;
    }

    /**
     * ダウンロードする
     *
     * @param url      ダウンロード元 URL
     * @param savePath ダウンロード先ファイルパス
     * @param timeout  タイムアウト（ミリ秒）
     * @return 成功したら true
     * @throws IOException ネットワークエラー
     */
    private static boolean download(URL url, File savePath, int timeout) throws IOException {
        if (savePath.getParentFile().mkdirs()) {
            Log.i(TAG, "Made directory " + savePath.getParent());
        }

        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(timeout);
            connection.setDoOutput(false);
            connection.setDoInput(true);
            connection.connect();

            final int status = connection.getResponseCode();
            if (status != 200) {
                Log.i(TAG, "Downloading from " + url + " resulted in " + status);
                return false;
            }

            try (final InputStream input = new BufferedInputStream(connection.getInputStream());
                 final OutputStream output = new BufferedOutputStream(new FileOutputStream(savePath))) {
                copy(input, output);
            }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * @return CPU アーキテクチャ名のリスト
     */
    private static List<String> getArchitectures() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Arrays.asList(Build.SUPPORTED_ABIS);
        }

        final List<String> architectures = new ArrayList<>();
        for (String architecture : new String[]{Build.CPU_ABI, Build.CPU_ABI2}) {
            if (architecture != null && architecture.isEmpty()) {
                architectures.add(architecture);
            }
        }
        return architectures;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            final int size = input.read(buffer);
            if (size <= 0) {
                break;
            }
            output.write(buffer, 0, size);
        }
    }

    public static void asyncDownload(@NonNull String urlRoot, @NonNull File saveDir, boolean overwrite, int timeout, @Nullable Consumer<File> onSuccess, @Nullable Consumer<Exception> onError) {
        (new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                final File path;
                try {
                    path = download(urlRoot, saveDir, overwrite, timeout);
                } catch (Exception e) {
                    if (onError != null) {
                        onError.accept(e);
                    } else {
                        Log.e(TAG, "Error occurred", e);
                    }
                    return null;
                }

                if (onSuccess != null) {
                    onSuccess.accept(path);
                }

                return null;
            }
        }).execute();
    }

}

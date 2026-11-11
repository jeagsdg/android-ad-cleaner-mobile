package app.clearscreen;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Reads the public release feed from the same branch through either GitHub endpoint. */
final class ReleaseUpdateTransport {
    private static final String API_URL =
            "https://api.github.com/repos/jeagsdg/android-ad-cleaner-mobile/contents/update.json?ref=main";
    private static final String RAW_URL =
            "https://raw.githubusercontent.com/jeagsdg/android-ad-cleaner-mobile/main/update.json";

    private ReleaseUpdateTransport() { }

    static ReleaseUpdates.Info fetch(int maxBytes) throws IOException {
        try {
            return ReleaseUpdates.parse(read(API_URL, "application/vnd.github.raw+json", maxBytes));
        } catch (IOException apiFailure) {
            try {
                return ReleaseUpdates.parse(read(RAW_URL, "text/plain", maxBytes));
            } catch (IOException rawFailure) {
                rawFailure.addSuppressed(apiFailure);
                throw rawFailure;
            }
        }
    }

    private static String read(String url, String accept, int maxBytes) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "ClearScreen-Update");
        try {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK)
                throw new IOException("无法读取 GitHub 更新文件");
            checkDeadline(deadline);
            if (connection.getContentLength() > maxBytes) throw new IOException("更新文件过大");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    checkDeadline(deadline);
                    if (count > maxBytes - body.size()) throw new IOException("更新文件过大");
                    body.write(buffer, 0, count);
                }
                checkDeadline(deadline);
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body.toByteArray())).toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("检查更新超时");
    }
}

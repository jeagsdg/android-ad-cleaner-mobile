package app.clearscreen;

import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.HttpsURLConnection;

/** Bounded reads from this project's public GitHub main branch. */
final class GithubReleaseFiles {
    static final String API_ACCEPT = "application/vnd.github.raw+json";
    private static final int MAX_ARCHIVE_ENTRIES = 128;
    private static final long MAX_SKIPPED_BYTES = 4L * 1024 * 1024;

    private GithubReleaseFiles() { }

    static byte[] readDirect(String path, boolean api, int maxBytes) throws IOException {
        checkRelativePath(path);
        long deadline = SystemClock.elapsedRealtime() + 6000;
        HttpsURLConnection connection = open(ReleaseUpdates.fileUrl(path, api), api ? API_ACCEPT : "*/*");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int expected = connection.getContentLength();
            if (expected > maxBytes) throw oversized();
            long count = copy(input, output, maxBytes, deadline);
            if (expected >= 0 && count != expected) throw new IOException("下载的文件不完整");
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readArchiveEntry(String path, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        readArchiveEntry(path, output, maxBytes, 12000);
        return output.toByteArray();
    }

    static void readArchiveEntry(String path, OutputStream output, long maxBytes, long timeoutMillis)
            throws IOException {
        checkRelativePath(path);
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        HttpsURLConnection connection = open(ReleaseUpdates.ARCHIVE_URL, "application/zip");
        try (InputStream input = connection.getInputStream()) {
            copyArchiveEntry(input, path, output, maxBytes, deadline);
        } finally {
            connection.disconnect();
        }
    }

    static void copyArchiveEntry(InputStream input, String path, OutputStream output, long maxBytes, long deadline)
            throws IOException {
        checkRelativePath(path);
        String expected = ReleaseUpdates.ARCHIVE_ROOT + path;
        // The release files sort before app sources and historical APKs. Bound skipped data as well as target data.
        long skipRemaining = MAX_SKIPPED_BYTES;
        long compressedLimit = maxBytes + MAX_SKIPPED_BYTES + 2L * 1024 * 1024;
        try (ZipInputStream archive = new ZipInputStream(new BufferedInputStream(
                new ArchiveInput(input, compressedLimit, deadline), 16384), StandardCharsets.UTF_8)) {
            for (int index = 0; index < MAX_ARCHIVE_ENTRIES; index++) {
                checkDeadline(deadline);
                ZipEntry entry = archive.getNextEntry();
                if (entry == null) break;
                if (expected.equals(entry.getName()) && !entry.isDirectory()) {
                    if (entry.getSize() > maxBytes) throw oversized();
                    // Reading the complete entry checks the ZIP CRC even though we don't need the archive tail.
                    if (copy(archive, output, maxBytes, deadline) == 0) throw new IOException("更新文件为空");
                    return;
                }
                if (entry.getSize() > skipRemaining) throw oversized();
                skipRemaining -= copy(archive, null, skipRemaining, deadline);
            }
        } catch (IllegalArgumentException invalidZip) {
            throw new IOException("更新归档无效", invalidZip);
        }
        throw new IOException("更新归档缺少所需文件");
    }

    static String decodeUtf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static long copy(InputStream input, OutputStream output, long limit, long deadline) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        while (true) {
            checkDeadline(deadline);
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total + 1));
            if (count == -1) return total;
            if (count > limit - total) throw oversized();
            if (output != null) output.write(buffer, 0, count);
            total += count;
        }
    }

    private static void checkRelativePath(String path) throws IOException {
        for (String segment : path.split("/", -1)) {
            if (!segment.matches("[.A-Za-z0-9_-]+") || segment.equals(".") || segment.equals(".."))
                throw new IOException("更新文件路径无效");
        }
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "ClearScreen-Update");
        try {
            if (connection.getResponseCode() == HttpsURLConnection.HTTP_OK) return connection;
            throw new IOException("GitHub 更新文件暂时不可用");
        } catch (IOException | RuntimeException failure) {
            connection.disconnect();
            throw failure;
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline)
            throw new InterruptedIOException("下载已取消或超时");
    }

    private static IOException oversized() { return new IOException("更新文件超过大小限制"); }

    private static final class ArchiveInput extends FilterInputStream {
        private long remaining;
        private final long deadline;

        ArchiveInput(InputStream input, long limit, long deadline) {
            super(input);
            remaining = limit;
            this.deadline = deadline;
        }

        @Override public int read() throws IOException {
            checkAvailable();
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            checkAvailable();
            int count = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }

        private void checkAvailable() throws IOException {
            checkDeadline(deadline);
            if (remaining == 0) throw oversized();
        }
    }
}

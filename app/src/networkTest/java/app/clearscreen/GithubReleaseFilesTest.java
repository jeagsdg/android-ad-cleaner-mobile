package app.clearscreen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GithubReleaseFilesTest {
    private static final String MANIFEST = ".update.json";
    private static final String MANIFEST_ENTRY = ReleaseUpdates.ARCHIVE_ROOT + MANIFEST;
    private static final byte[] MANIFEST_BYTES = {1, 2, 3, 4};

    @Test public void validEarlyEntryDoesNotRequireDownloadingArchiveTail() throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(archive(MANIFEST_ENTRY, true));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        GithubReleaseFiles.copyArchiveEntry(input, MANIFEST, output, MANIFEST_BYTES.length, Long.MAX_VALUE);

        assertArrayEquals(MANIFEST_BYTES, output.toByteArray());
        assertTrue(input.available() > 64 * 1024);
    }

    @Test public void exactEntryRejectsWrongRootTraversalAndOversizedFiles() throws IOException {
        byte[] substituted = archive(ReleaseUpdates.ARCHIVE_ROOT + "other/../" + MANIFEST, false);
        byte[] wrongRoot = archive("other-source/" + MANIFEST, false);
        byte[] correct = archive(MANIFEST_ENTRY, false);
        assertThrows(IOException.class, () -> extract(substituted, MANIFEST, MANIFEST_BYTES.length));
        assertThrows(IOException.class, () -> extract(wrongRoot, MANIFEST, MANIFEST_BYTES.length));
        assertThrows(IOException.class, () -> extract(correct, "../" + MANIFEST, MANIFEST_BYTES.length));
        assertThrows(IOException.class, () -> extract(correct, MANIFEST, MANIFEST_BYTES.length - 1));
    }

    @Test public void rejectsCorruptOrTruncatedTargetEntry() throws IOException {
        byte[] bytes = archive(MANIFEST_ENTRY, false);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int payloadStart = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
        byte[] truncated = Arrays.copyOf(bytes, payloadStart + MANIFEST_BYTES.length - 1);
        bytes[payloadStart] ^= 0x04;

        assertThrows(IOException.class, () -> extract(truncated, MANIFEST, MANIFEST_BYTES.length));
        assertThrows(IOException.class, () -> extract(bytes, MANIFEST, MANIFEST_BYTES.length));
    }

    private static void extract(byte[] bytes, String path, int maxBytes) throws IOException {
        GithubReleaseFiles.copyArchiveEntry(new ByteArrayInputStream(bytes), path,
                new ByteArrayOutputStream(), maxBytes, Long.MAX_VALUE);
    }

    private static byte[] archive(String manifestPath, boolean addTail) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addEntry(zip, manifestPath, MANIFEST_BYTES);
            if (addTail) addEntry(zip, ReleaseUpdates.ARCHIVE_ROOT + "dist/history.apk", new byte[96 * 1024]);
        }
        return bytes.toByteArray();
    }

    private static void addEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}

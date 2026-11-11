package app.clearscreen;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;

/** Read-only checks against the project's public GitHub update feed. */
final class ReleaseUpdates {
    private static final String REPOSITORY = "jeagsdg/android-ad-cleaner-mobile";
    private static final String APK_ROOT = "https://github.com/" + REPOSITORY + "/raw/refs/heads/main/dist/";
    private static final String FILE_PAGE = "https://github.com/" + REPOSITORY + "/blob/main/dist/";
    private static final int MAX_FEED_BYTES = 16 * 1024;
    private static final int MAX_NOTES_LENGTH = 1200;
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]{0,9}");
    private static final String COMPONENT = "(?:0|[1-9][0-9]{0,4})";
    private static final Pattern VERSION = Pattern.compile(COMPONENT + "\\." + COMPONENT + "\\." + COMPONENT);

    private ReleaseUpdates() {}

    static final class Info {
        final int versionCode;
        final String versionName;
        final String notes;

        private Info(int versionCode, String versionName, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.notes = notes;
        }

        Uri downloadUri() {
            return Uri.parse(APK_ROOT + apkName());
        }

        Uri filePageUri() {
            return Uri.parse(FILE_PAGE + apkName());
        }

        private String apkName() {
            return "ClearScreen-" + versionName + ".apk";
        }
    }

    static Info fetch() throws IOException {
        return parse(ReleaseUpdateTransport.read(MAX_FEED_BYTES));
    }

    static Info parse(String source) throws IOException {
        if (source == null || source.length() > MAX_FEED_BYTES) throw invalidFeed();
        Integer schema = null, versionCode = null;
        String versionName = null, notes = null;
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "schema":
                        if (schema != null) throw invalidFeed();
                        schema = readInteger(reader);
                        break;
                    case "versionCode":
                        if (versionCode != null) throw invalidFeed();
                        versionCode = readInteger(reader);
                        break;
                    case "versionName":
                        if (versionName != null) throw invalidFeed();
                        versionName = readString(reader);
                        break;
                    case "notes":
                        if (notes != null) throw invalidFeed();
                        notes = readString(reader);
                        break;
                    default:
                        throw invalidFeed();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalidFeed();
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw invalidFeed();
        }
        if (schema == null || schema != 1 || versionCode == null || versionCode < 1
                || versionName == null || !VERSION.matcher(versionName).matches()
                || notes == null || notes.length() > MAX_NOTES_LENGTH
                || notes.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalidFeed();
        }
        return new Info(versionCode, versionName, notes);
    }

    private static int readInteger(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalidFeed();
        String value = reader.nextString();
        if (!INTEGER.matcher(value).matches()) throw invalidFeed();
        return Integer.parseInt(value);
    }

    private static String readString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalidFeed();
        return reader.nextString();
    }

    private static IOException invalidFeed() {
        return new IOException("更新文件格式无效");
    }
}

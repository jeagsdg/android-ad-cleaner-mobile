package app.clearscreen;

import java.io.IOException;

/** Only the fixed official GitHub repository path can supply a candidate. */
final class RulePackTransport {
    static final boolean AVAILABLE = true;
    private static final String PATH = "0-update/rules.json";

    private RulePackTransport() { }

    static RulePack fetch(RulePacks.Decoder decoder) throws IOException {
        int limit = RulePack.MAX_ENVELOPE_BYTES;
        IOException unavailable = new IOException("无法获取有效的官方规则包");
        try {
            return decoder.verify(GithubReleaseFiles.readDirect(PATH, true, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        try {
            return decoder.verify(GithubReleaseFiles.readDirect(PATH, false, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        try {
            return decoder.verify(GithubReleaseFiles.readArchiveEntry(PATH, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        throw unavailable;
    }
}

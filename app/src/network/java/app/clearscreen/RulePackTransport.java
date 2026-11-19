package app.clearscreen;

import java.io.IOException;

/** Only the fixed official GitHub repository path can supply a candidate. */
final class RulePackTransport {
    static final boolean AVAILABLE = true;

    private RulePackTransport() { }

    static RulePack fetch(RulePacks.Decoder decoder) throws IOException {
        int limit = RulePack.MAX_ENVELOPE_BYTES;
        String path = RulePack.OFFICIAL_PATH;
        IOException unavailable = new IOException("无法获取有效的官方规则包");
        try {
            return decoder.verify(GithubReleaseFiles.readDirect(path, true, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        try {
            return decoder.verify(GithubReleaseFiles.readDirect(path, false, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        try {
            return decoder.verify(GithubReleaseFiles.readArchiveEntry(path, limit));
        } catch (IOException e) {
            unavailable.addSuppressed(e);
        }
        throw unavailable;
    }
}

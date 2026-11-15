package app.clearscreen;

import java.io.IOException;

final class ReleaseUpdateTransport {
    private ReleaseUpdateTransport() { }

    static ReleaseUpdates.Info fetch(int maxBytes) throws IOException {
        IOException firstFailure = null;
        for (ReleaseUpdates.Source source : new ReleaseUpdates.Source[]{ReleaseUpdates.Source.API,
                ReleaseUpdates.Source.RAW, ReleaseUpdates.Source.ARCHIVE}) {
            try {
                byte[] data = source == ReleaseUpdates.Source.ARCHIVE
                        ? GithubReleaseFiles.readArchiveEntry(".update.json", maxBytes)
                        : GithubReleaseFiles.readDirect("update.json", source == ReleaseUpdates.Source.API, maxBytes);
                return ReleaseUpdates.parse(GithubReleaseFiles.decodeUtf8(data), source);
            } catch (IOException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        throw new IOException("无法读取 GitHub 更新文件", firstFailure);
    }
}

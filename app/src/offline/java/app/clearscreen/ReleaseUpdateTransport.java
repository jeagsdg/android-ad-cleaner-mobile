package app.clearscreen;

import java.io.IOException;

final class ReleaseUpdateTransport {
    private ReleaseUpdateTransport() { }

    static String read(int maxBytes) throws IOException {
        throw new IOException("纯离线版可在浏览器中查看更新");
    }
}

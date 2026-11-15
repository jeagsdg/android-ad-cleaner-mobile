package app.clearscreen;

import java.io.IOException;

final class RulePackTransport {
    static final boolean AVAILABLE = false;

    private RulePackTransport() { }

    static RulePack fetch(RulePacks.Decoder decoder) throws IOException {
        throw new IOException("纯离线版使用内置规则");
    }
}

package org.atp.proxy;

/** Process entrypoint. Excluded from coverage because it only exits. */
public final class TxEngineMain {
    private TxEngineMain() {
    }

    public static void main(String[] args) {
        int code = TxCommands.run(args, System.in, System.out, System.err);
        System.exit(code);
    }
}

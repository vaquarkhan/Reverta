package org.atp.proxy;

import org.atp.chaos.ChaosHarness;
import org.atp.crypto.Signatures;
import org.atp.demo.DemoWorld;
import org.atp.engine.TransactionEngine;
import org.atp.json.JsonCodec;
import org.atp.log.FileDurableLog;
import org.atp.model.Types.AtpException;
import org.atp.policy.Policy;
import org.atp.replay.ReplayEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Operator commands. The process entrypoint only calls {@link #run} and exits. */
public final class TxCommands {
    static final AtomicReference<ProxyServer> ACTIVE = new AtomicReference<>();

    private TxCommands() {
    }

    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            usage(err);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "keys" -> keys(out);
                case "sign" -> sign(args, out);
                case "chaos" -> chaos(args);
                case "replay" -> replay(args, out);
                case "stdio" -> stdio(in, out);
                case "serve", "demo" -> serve(args, out);
                default -> {
                    usage(err);
                    yield 2;
                }
            };
        } catch (AtpException | IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return 1;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            err.println(ex.getMessage());
            return 1;
        }
    }

    private static int keys(PrintStream out) {
        KeyPair pair = Signatures.generate();
        out.println(Signatures.encodePublic(pair.getPublic()));
        out.println(Signatures.encodePrivate(pair.getPrivate()));
        return 0;
    }

    private static int sign(String[] args, PrintStream out) throws IOException {
        String kid = option(args, "--kid");
        String raw = option(args, "--private");
        String payloadPath = option(args, "--payload");
        if (kid == null || raw == null || payloadPath == null) {
            throw new IllegalArgumentException("sign requires --kid, --private, and --payload");
        }
        Map<String, Object> payload = JsonCodec.asMap(JsonCodec.parse(Files.readString(Path.of(payloadPath))));
        out.println(Policy.sign(payload, kid, Signatures.parsePrivate(raw)));
        return 0;
    }

    private static int chaos(String[] args) throws IOException {
        String out = option(args, "--out");
        if (out == null) {
            throw new IllegalArgumentException("chaos requires --out");
        }
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, ChaosHarness.csv(ChaosHarness.runAll(DemoWorld.create())));
        return 0;
    }

    private static int replay(String[] args, PrintStream out) throws IOException {
        String tx = option(args, "--tx");
        String dir = option(args, "--wal-dir");
        if (tx == null || dir == null || !has(args, "--sandbox")) {
            throw new IllegalArgumentException("replay requires --tx, --wal-dir, and --sandbox");
        }
        int upTo = 0;
        String raw = option(args, "--up-to-step");
        if (raw != null) {
            upTo = Integer.parseInt(raw);
        }
        try (FileDurableLog log = new FileDurableLog(Path.of(dir))) {
            TransactionEngine engine = DemoWorld.create().engine(log);
            var replay = ReplayEngine.replay(engine.ledger(), tx, upTo, true);
            out.println(replay.finalState().name() + " " + replay.timeline().size());
        }
        return 0;
    }

    private static int stdio(InputStream in, PrintStream out) throws IOException {
        DemoWorld world = DemoWorld.create();
        RpcHandler rpc = new RpcHandler(world, world.engine(new org.atp.log.MemoryLog()));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                out.println(rpc.handle(line));
            }
        }
        return 0;
    }

    private static int serve(String[] args, PrintStream out) throws IOException, InterruptedException {
        int port = 8080;
        String raw = option(args, "--port");
        if (raw != null) {
            port = Integer.parseInt(raw);
        }
        ProxyServer server = ProxyServer.demo(port);
        ACTIVE.set(server);
        out.println("listening " + server.port());
        try {
            server.await();
        } finally {
            server.close();
            ACTIVE.compareAndSet(server, null);
        }
        return 0;
    }

    private static boolean has(String[] args, String name) {
        for (String arg : args) {
            if (name.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static void usage(PrintStream err) {
        err.println("usage: tx-engine keys | sign | chaos | replay | stdio | demo | serve");
    }
}

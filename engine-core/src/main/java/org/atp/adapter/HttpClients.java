// SPDX-License-Identifier: Apache-2.0
package org.atp.adapter;

import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.json.JsonCodec;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.DiffReport;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HttpClients {
    private HttpClients() {
    }

    public record SimpleResponse(int status, String body) {
    }

    public interface SimpleHttp {
        SimpleResponse send(String method, String url, Map<String, String> headers, String body);
    }

    public static final class JdkSimpleHttp implements SimpleHttp {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();

        @Override
        public SimpleResponse send(String method, String url, Map<String, String> headers, String body) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
                if (headers != null) {
                    headers.forEach(builder::header);
                }
                if ("GET".equals(method) || "DELETE".equals(method)) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                }
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new SimpleResponse(response.statusCode(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AtpException(AtpErrorCode.INVALID, "http interrupted");
            } catch (Exception ex) {
                throw new AtpException(AtpErrorCode.INVALID, "http: " + ex.getMessage());
            }
        }
    }

    /** Nessie API v2 branch lifecycle: create, merge into main, delete. */
    public static final class NessieClient {
        private final SimpleHttp http;
        private final String base;

        public NessieClient(SimpleHttp http, String baseUrl) {
            this.http = http;
            this.base = trim(baseUrl);
        }

        public String createBranch(String name, String from) {
            String hash = hashOf(from);
            String url = base + "/trees?name=" + enc(name) + "&type=BRANCH";
            SimpleResponse response = http.send("POST", url, json(), JsonCodec.canonical(Map.of(
                    "name", from, "type", "BRANCH", "hash", hash)));
            if (response.status == 409) {
                return hash;
            }
            require2xx(response, "create branch");
            return hash;
        }

        public void mergeIntoMain(String branch) {
            String main = hashOf("main");
            String from = hashOf(branch);
            String url = base + "/trees/" + enc("main") + "@" + enc(main) + "/history/merge";
            SimpleResponse response = http.send("POST", url, json(), JsonCodec.canonical(Map.of(
                    "fromRefName", branch, "fromHash", from)));
            require2xx(response, "merge");
        }

        public void deleteBranch(String name) {
            SimpleResponse response = http.send("DELETE", base + "/trees/" + enc(name) + "?type=BRANCH", json(), "");
            if (response.status == 404) {
                return;
            }
            require2xx(response, "delete branch");
        }

        @SuppressWarnings("unchecked")
        private String hashOf(String name) {
            SimpleResponse response = http.send("GET", base + "/trees", json(), "");
            require2xx(response, "list refs");
            Map<String, Object> body = JsonCodec.asMap(JsonCodec.parse(response.body));
            Object refs = body.get("references");
            if (refs instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && name.equals(String.valueOf(map.get("name")))) {
                        return String.valueOf(map.get("hash"));
                    }
                }
            }
            throw new AtpException(AtpErrorCode.INVALID, "nessie ref not found: " + name);
        }
    }

    /** Neon API v2: create a copy-on-write branch and delete it when the saga ends. */
    public static final class NeonClient {
        private final SimpleHttp http;
        private final String base;
        private final String projectId;
        private final String token;
        private final Map<String, String> ids = new ConcurrentHashMap<>();

        public NeonClient(SimpleHttp http, String baseUrl, String projectId, String token) {
            if (token == null || token.isBlank()) {
                throw new AtpException(AtpErrorCode.INVALID, "neon token is required");
            }
            this.http = http;
            this.base = trim(baseUrl);
            this.projectId = projectId;
            this.token = token;
        }

        @SuppressWarnings("unchecked")
        public String createBranch(String name) {
            if (ids.containsKey(name)) {
                return ids.get(name);
            }
            String url = base + "/projects/" + enc(projectId) + "/branches";
            SimpleResponse response = http.send("POST", url, auth(), JsonCodec.canonical(Map.of(
                    "branch", Map.of("name", name))));
            require2xx(response, "neon create");
            Map<String, Object> body = JsonCodec.asMap(JsonCodec.parse(response.body));
            Object branch = body.get("branch");
            if (!(branch instanceof Map<?, ?> map) || map.get("id") == null) {
                throw new AtpException(AtpErrorCode.INVALID, "neon response has no branch id");
            }
            String id = String.valueOf(map.get("id"));
            ids.put(name, id);
            return id;
        }

        public void deleteBranch(String name) {
            String id = ids.remove(name);
            if (id == null) {
                return;
            }
            String url = base + "/projects/" + enc(projectId) + "/branches/" + enc(id);
            SimpleResponse response = http.send("DELETE", url, auth(), "");
            if (response.status == 404) {
                return;
            }
            require2xx(response, "neon delete");
        }

        private Map<String, String> auth() {
            return Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json");
        }
    }

    /** Delegates WAP mutations and mirrors branch create/merge/delete onto Nessie. */
    public static final class CatalogBacking implements PhasePort {
        private final PhasePort inner;
        private final NessieClient nessie;
        private final Set<String> opened = ConcurrentHashMap.newKeySet();
        private final Set<String> merged = ConcurrentHashMap.newKeySet();

        public CatalogBacking(PhasePort inner, NessieClient nessie) {
            this.inner = inner;
            this.nessie = nessie;
        }

        @Override
        public EffectResult shadow(Dispatch dispatch) {
            if (nessie != null && opened.add(dispatch.txId())) {
                nessie.createBranch(branch(dispatch.txId()), "main");
            }
            return inner.shadow(dispatch);
        }

        @Override
        public EffectResult live(Dispatch dispatch) {
            EffectResult result = inner.live(dispatch);
            if (nessie != null && result.status() == org.atp.model.Types.EffectStatus.OK && merged.add(dispatch.txId())) {
                nessie.mergeIntoMain(branch(dispatch.txId()));
            }
            return result;
        }

        @Override
        public EffectResult compensate(Dispatch dispatch) {
            EffectResult result = inner.compensate(dispatch);
            if (nessie != null) {
                nessie.deleteBranch(branch(dispatch.txId()));
                opened.remove(dispatch.txId());
            }
            return result;
        }

        @Override
        public EffectResult reconcile(Dispatch dispatch) {
            return inner.reconcile(dispatch);
        }

        @Override
        public DiffReport diff(String txId) {
            return inner.diff(txId);
        }

        @Override
        public void afterTerminal(String txId, boolean committed) {
            inner.afterTerminal(txId, committed);
            if (nessie != null && committed) {
                nessie.deleteBranch(branch(txId));
            }
        }

        private static String branch(String txId) {
            return "atp_" + txId.replaceAll("[^A-Za-z0-9]", "_");
        }
    }

    public static final class NeonBacking implements PhasePort {
        private final PhasePort inner;
        private final NeonClient neon;

        public NeonBacking(PhasePort inner, NeonClient neon) {
            this.inner = inner;
            this.neon = neon;
        }

        @Override
        public EffectResult shadow(Dispatch dispatch) {
            if (neon != null) {
                neon.createBranch("atp-" + dispatch.txId());
            }
            return inner.shadow(dispatch);
        }

        @Override
        public EffectResult live(Dispatch dispatch) {
            return inner.live(dispatch);
        }

        @Override
        public EffectResult compensate(Dispatch dispatch) {
            EffectResult result = inner.compensate(dispatch);
            if (neon != null) {
                neon.deleteBranch("atp-" + dispatch.txId());
            }
            return result;
        }

        @Override
        public EffectResult reconcile(Dispatch dispatch) {
            return inner.reconcile(dispatch);
        }

        @Override
        public DiffReport diff(String txId) {
            return inner.diff(txId);
        }

        @Override
        public void afterTerminal(String txId, boolean committed) {
            inner.afterTerminal(txId, committed);
            if (neon != null) {
                neon.deleteBranch("atp-" + txId);
            }
        }
    }

    private static Map<String, String> json() {
        return Map.of("Content-Type", "application/json");
    }

    private static void require2xx(SimpleResponse response, String action) {
        if (response.status < 200 || response.status >= 300) {
            throw new AtpException(AtpErrorCode.INVALID, action + " failed: HTTP " + response.status);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String base) {
        if (base == null || base.isBlank()) {
            throw new AtpException(AtpErrorCode.INVALID, "base url is required");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}

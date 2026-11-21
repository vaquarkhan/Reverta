// SPDX-License-Identifier: Apache-2.0
package org.atp.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class Sanitizer {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|secret|token|authorization|api[-_]?key|ssn|card|cvv|credential)");

    private Sanitizer() {
    }

    public static Object sanitize(Object value) {
        return walk(value);
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SECRET.matcher(key).find()) {
                    copy.put(key, "[REDACTED]");
                } else {
                    copy.put(key, walk(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(walk(item));
            }
            return copy;
        }
        if (value instanceof String s && "bearer".equals(s.toLowerCase(Locale.ROOT))) {
            return s;
        }
        return value;
    }
}

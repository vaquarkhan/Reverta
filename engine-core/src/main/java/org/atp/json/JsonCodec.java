// SPDX-License-Identifier: Apache-2.0
package org.atp.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON parse and RFC 8785-style canonical encoding used by the hash chain. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private JsonCodec() {
    }

    public static Object parse(String json) {
        try {
            return fromNode(MAPPER.readTree(json));
        } catch (Exception ex) {
            throw new AtpException(AtpErrorCode.INVALID, "invalid JSON: " + ex.getMessage());
        }
    }

    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(canonical(value)));
        } catch (Exception ex) {
            throw new AtpException(AtpErrorCode.INVALID, "pretty print failed: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new AtpException(AtpErrorCode.INVALID, "expected JSON object");
    }

    private static Object fromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(fromNode(child)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), fromNode(entry.getValue())));
            return map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number) {
            sb.append(canonicalNumber(value));
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey());
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new AtpException(AtpErrorCode.INVALID, "cannot canonicalize " + value.getClass().getName());
        }
    }

    static String canonicalNumber(Object value) {
        BigDecimal decimal;
        if (value instanceof BigDecimal d) {
            decimal = d;
        } else if (value instanceof Double || value instanceof Float) {
            decimal = new BigDecimal(value.toString());
        } else {
            decimal = new BigDecimal(value.toString());
        }
        decimal = decimal.stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }
        return decimal.toPlainString();
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

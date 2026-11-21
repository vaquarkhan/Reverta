// SPDX-License-Identifier: Apache-2.0
package org.atp.schema;

import org.atp.model.Types;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON Schema 2020-12 profile used to reject hallucinated tool arguments before any side effect. */
public final class SchemaValidator {
    private SchemaValidator() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            errors.add("schema is empty");
            return errors;
        }
        check(schema, value, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String path, List<String> errors) {
        Object type = schema.get("type");
        if (type instanceof String typeName && !matches(typeName, value)) {
            errors.add(path + " expected " + typeName);
            return;
        }
        if (schema.get("enum") instanceof List<?> options && !enumContains(options, value)) {
            errors.add(path + " not in enum");
        }
        if (value instanceof String s) {
            if (schema.get("minLength") instanceof Number n && s.length() < n.intValue()) {
                errors.add(path + " shorter than minLength");
            }
            if (schema.get("maxLength") instanceof Number n && s.length() > n.intValue()) {
                errors.add(path + " longer than maxLength");
            }
        }
        if (value instanceof Number) {
            BigDecimal number = Types.asDecimal(value);
            if (schema.get("minimum") != null && number.compareTo(Types.asDecimal(schema.get("minimum"))) < 0) {
                errors.add(path + " below minimum");
            }
            if (schema.get("maximum") != null && number.compareTo(Types.asDecimal(schema.get("maximum"))) > 0) {
                errors.add(path + " above maximum");
            }
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number n && list.size() < n.intValue()) {
                errors.add(path + " has too few items");
            }
            if (schema.get("maxItems") instanceof Number n && list.size() > n.intValue()) {
                errors.add(path + " has too many items");
            }
            if (schema.get("items") instanceof Map<?, ?> itemSchema) {
                for (int i = 0; i < list.size(); i++) {
                    check((Map<String, Object>) itemSchema, list.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> object = (Map<String, Object>) map;
            if (schema.get("required") instanceof List<?> required) {
                for (Object name : required) {
                    if (!object.containsKey(String.valueOf(name))) {
                        errors.add(path + "." + name + " is required");
                    }
                }
            }
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw : Map.of();
            boolean additional = schema.get("additionalProperties") == null || Boolean.TRUE.equals(schema.get("additionalProperties"));
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object propertySchema = properties.get(entry.getKey());
                if (propertySchema instanceof Map<?, ?> nested) {
                    check((Map<String, Object>) nested, entry.getValue(), path + "." + entry.getKey(), errors);
                } else if (!additional) {
                    errors.add(path + "." + entry.getKey() + " is not allowed");
                }
            }
        }
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && Types.asDecimal(number).stripTrailingZeros().scale() <= 0;
            default -> true;
        };
    }

    private static boolean enumContains(List<?> options, Object value) {
        for (Object option : options) {
            if (option == null && value == null) {
                return true;
            }
            if (option instanceof Number && value instanceof Number
                    && Types.asDecimal(option).compareTo(Types.asDecimal(value)) == 0) {
                return true;
            }
            if (option != null && option.equals(value)) {
                return true;
            }
        }
        return false;
    }
}

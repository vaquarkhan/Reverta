// SPDX-License-Identifier: Apache-2.0
package org.atp.expr;

import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic CEL subset used for compensation mappings and blast-radius rules.
 * Grammar: literals, paths, calls, + - * / , comparisons, &amp;&amp; || !, and parentheses.
 */
public final class Expressions {
    private Expressions() {
    }

    public static final class ExprException extends RuntimeException {
        public ExprException(String message) {
            super(message);
        }
    }

    public static final class EvalContext {
        private final Map<String, Object> variables;
        private final Map<String, Function<List<Object>, Object>> functions;

        public EvalContext(Map<String, Object> variables, Map<String, Function<List<Object>, Object>> functions) {
            this.variables = variables;
            this.functions = functions;
        }

        public Object variable(String name) {
            return variables.get(name);
        }
    }

    public static void checkSyntax(String source) {
        new Parser(tokenize(source)).parseExpression();
    }

    public static Object eval(String source, EvalContext context) {
        return new Parser(tokenize(source)).parseExpression().eval(context);
    }

    interface Node {
        Object eval(EvalContext context);
    }

    record Literal(Object value) implements Node {
        @Override
        public Object eval(EvalContext context) {
            return value;
        }
    }

    private record Path(List<String> parts) implements Node {
        @Override
        public Object eval(EvalContext context) {
            Object current = context.variable(parts.get(0));
            for (int i = 1; i < parts.size(); i++) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(parts.get(i));
            }
            return current;
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        @Override
        public Object eval(EvalContext context) {
            Function<List<Object>, Object> function = context.functions.get(name);
            if (function == null) {
                throw new ExprException("unknown function " + name);
            }
            List<Object> values = new ArrayList<>();
            for (Node arg : args) {
                values.add(arg.eval(context));
            }
            return function.apply(values);
        }
    }

    private record Unary(String op, Node inner) implements Node {
        @Override
        public Object eval(EvalContext context) {
            Object value = inner.eval(context);
            if ("!".equals(op)) {
                if (!(value instanceof Boolean b)) {
                    throw new ExprException("! expects boolean");
                }
                return !b;
            }
            return decimal(value).negate();
        }
    }

    record Binary(String op, Node left, Node right) implements Node {
        @Override
        public Object eval(EvalContext context) {
            if ("&&".equals(op)) {
                Object l = left.eval(context);
                if (!(l instanceof Boolean lb)) {
                    throw new ExprException("&& expects boolean");
                }
                if (!lb) {
                    return false;
                }
                Object r = right.eval(context);
                if (!(r instanceof Boolean)) {
                    throw new ExprException("&& expects boolean");
                }
                return r;
            }
            if ("||".equals(op)) {
                Object l = left.eval(context);
                if (!(l instanceof Boolean lb)) {
                    throw new ExprException("|| expects boolean");
                }
                if (lb) {
                    return true;
                }
                Object r = right.eval(context);
                if (!(r instanceof Boolean)) {
                    throw new ExprException("|| expects boolean");
                }
                return r;
            }
            Object l = left.eval(context);
            Object r = right.eval(context);
            return switch (op) {
                case "+" -> plus(l, r);
                case "-" -> decimal(l).subtract(decimal(r));
                case "*" -> decimal(l).multiply(decimal(r));
                case "/" -> divide(l, r);
                case "==" -> equalsValue(l, r);
                case "!=" -> !equalsValue(l, r);
                case "<" -> decimal(l).compareTo(decimal(r)) < 0;
                case ">" -> decimal(l).compareTo(decimal(r)) > 0;
                case "<=" -> decimal(l).compareTo(decimal(r)) <= 0;
                case ">=" -> decimal(l).compareTo(decimal(r)) >= 0;
                default -> throw new ExprException("unknown operator " + op);
            };
        }
    }

    private static Object plus(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return stringify(left) + stringify(right);
        }
        return decimal(left).add(decimal(right));
    }

    private static BigDecimal divide(Object left, Object right) {
        BigDecimal divisor = decimal(right);
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ExprException("division by zero");
        }
        return decimal(left).divide(divisor, 8, RoundingMode.HALF_UP);
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        if (left instanceof Boolean && right instanceof Boolean) {
            return left.equals(right);
        }
        return stringify(left).equals(stringify(right));
    }

    public static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new ExprException("expected number but got " + value);
    }

    public static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            decimal = decimal.stripTrailingZeros();
            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    public static Map<String, Function<List<Object>, Object>> baseFunctions() {
        return Map.of(
                "abs", args -> {
                    requireArity("abs", args, 1);
                    return decimal(args.get(0)).abs();
                },
                "len", args -> {
                    requireArity("len", args, 1);
                    Object value = args.get(0);
                    if (value instanceof String s) {
                        return BigDecimal.valueOf(s.length());
                    }
                    if (value instanceof List<?> list) {
                        return BigDecimal.valueOf(list.size());
                    }
                    if (value instanceof Map<?, ?> map) {
                        return BigDecimal.valueOf(map.size());
                    }
                    throw new ExprException("len expects string, list, or map");
                },
                "str", args -> {
                    requireArity("str", args, 1);
                    return stringify(args.get(0));
                },
                "coalesce", args -> {
                    if (args.isEmpty()) {
                        throw new ExprException("coalesce expects arguments");
                    }
                    for (Object arg : args) {
                        if (arg != null) {
                            return arg;
                        }
                    }
                    return null;
                });
    }

    private static void requireArity(String name, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw new ExprException(name + " expects " + expected + " argument");
        }
    }

    private enum Kind {NUMBER, STRING, IDENT, OP, EOF}

    private record Tok(Kind kind, String text) {
    }

    private static List<Tok> tokenize(String source) {
        if (source == null) {
            throw new ExprException("expression is null");
        }
        List<Tok> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < source.length() && source.charAt(j) != quote) {
                    if (source.charAt(j) == '\\' && j + 1 < source.length()) {
                        sb.append(source.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    sb.append(source.charAt(j));
                    j++;
                }
                if (j >= source.length()) {
                    throw new ExprException("unterminated string");
                }
                tokens.add(new Tok(Kind.STRING, sb.toString()));
                i = j + 1;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                int j = i + 1;
                while (j < source.length() && (Character.isDigit(source.charAt(j)) || source.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(new Tok(Kind.NUMBER, source.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < source.length() && (Character.isLetterOrDigit(source.charAt(j)) || source.charAt(j) == '_')) {
                    j++;
                }
                tokens.add(new Tok(Kind.IDENT, source.substring(i, j)));
                i = j;
                continue;
            }
            String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                    || two.equals("<=") || two.equals(">=")) {
                tokens.add(new Tok(Kind.OP, two));
                i += 2;
                continue;
            }
            if ("+-*/<>!(),.".indexOf(c) >= 0) {
                tokens.add(new Tok(Kind.OP, String.valueOf(c)));
                i++;
                continue;
            }
            throw new ExprException("unexpected character '" + c + "'");
        }
        tokens.add(new Tok(Kind.EOF, ""));
        return tokens;
    }

    private static final class Parser {
        private final List<Tok> tokens;
        private int index;

        private Parser(List<Tok> tokens) {
            this.tokens = tokens;
        }

        Node parseExpression() {
            Node node = parseOr();
            if (peek().kind != Kind.EOF) {
                throw new ExprException("trailing input near " + peek().text);
            }
            return node;
        }

        private Node parseOr() {
            Node node = parseAnd();
            while (op("||")) {
                node = new Binary("||", node, parseAnd());
            }
            return node;
        }

        private Node parseAnd() {
            Node node = parseEquality();
            while (op("&&")) {
                node = new Binary("&&", node, parseEquality());
            }
            return node;
        }

        private Node parseEquality() {
            Node node = parseRel();
            while (matchOp("==") || matchOp("!=")) {
                String op = previous().text;
                node = new Binary(op, node, parseRel());
            }
            return node;
        }

        private Node parseRel() {
            Node node = parseAdd();
            while (matchOp("<") || matchOp(">") || matchOp("<=") || matchOp(">=")) {
                String op = previous().text;
                node = new Binary(op, node, parseAdd());
            }
            return node;
        }

        private Node parseAdd() {
            Node node = parseMul();
            while (matchOp("+") || matchOp("-")) {
                String op = previous().text;
                node = new Binary(op, node, parseMul());
            }
            return node;
        }

        private Node parseMul() {
            Node node = parseUnary();
            while (matchOp("*") || matchOp("/")) {
                String op = previous().text;
                node = new Binary(op, node, parseUnary());
            }
            return node;
        }

        private Node parseUnary() {
            if (matchOp("!") || matchOp("-")) {
                String op = previous().text;
                return new Unary(op, parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Tok tok = peek();
            if (tok.kind == Kind.NUMBER) {
                advance();
                return new Literal(new BigDecimal(tok.text));
            }
            if (tok.kind == Kind.STRING) {
                advance();
                return new Literal(tok.text);
            }
            if (tok.kind == Kind.IDENT) {
                advance();
                if (tok.text.equals("true")) {
                    return new Literal(true);
                }
                if (tok.text.equals("false")) {
                    return new Literal(false);
                }
                if (tok.text.equals("null")) {
                    return new Literal(null);
                }
                if (matchOp("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!matchOp(")")) {
                        args.add(parseOr());
                        while (matchOp(",")) {
                            args.add(parseOr());
                        }
                        expect(")");
                    }
                    return new Call(tok.text, args);
                }
                List<String> parts = new ArrayList<>();
                parts.add(tok.text);
                while (matchOp(".")) {
                    Tok next = peek();
                    if (next.kind != Kind.IDENT) {
                        throw new ExprException("expected identifier after '.'");
                    }
                    advance();
                    parts.add(next.text);
                }
                return new Path(parts);
            }
            if (matchOp("(")) {
                Node inner = parseOr();
                expect(")");
                return inner;
            }
            throw new ExprException("unexpected token " + tok.text);
        }

        private boolean op(String value) {
            if (peek().kind == Kind.OP && peek().text.equals(value)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean matchOp(String value) {
            return op(value);
        }

        private void expect(String value) {
            if (!matchOp(value)) {
                throw new ExprException("expected " + value);
            }
        }

        private Tok peek() {
            return tokens.get(index);
        }

        private Tok previous() {
            return tokens.get(index - 1);
        }

        private void advance() {
            if (peek().kind != Kind.EOF) {
                index++;
            }
        }
    }

    public static AtpException asProtocol(String message) {
        return new AtpException(AtpErrorCode.MANIFEST_REJECTED, message);
    }
}

package org.atp.expr;

import org.atp.expr.Expressions.EvalContext;
import org.atp.expr.Expressions.ExprException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprTest {
    private static Object eval(String source) {
        return Expressions.eval(source, new EvalContext(Map.of(
                "input", Map.of("sku", "A", "qty", 2, "name", "box"),
                "output", Map.of("id", "1"),
                "flag", true,
                "items", List.of("a", "b"),
                "bag", Map.of("k", "v")), Expressions.baseFunctions()));
    }

    @Test
    void evaluatesTheDeterministicSubset() {
        assertEquals(new BigDecimal("3"), eval("1 + 2"));
        assertEquals(new BigDecimal("6"), eval("2 * 3"));
        assertEquals(new BigDecimal("-1"), eval("1 - 2"));
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) eval("1 / 2")));
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) eval(".5")));
        assertEquals("comp_A", eval("'comp_' + input.sku"));
        assertEquals("n1", eval("\"n\" + 1"));
        assertEquals(true, eval("1 == 1.0"));
        assertEquals(false, eval("1 != 1"));
        assertEquals(true, eval("1 < 2 && 2 <= 2"));
        assertEquals(true, eval("3 > 2 && 3 >= 3"));
        assertEquals(true, eval("false || true"));
        assertEquals(false, eval("!flag"));
        assertEquals(true, eval("!flag == false"));
        assertEquals(true, eval("(1 + 2) == 3"));
        assertEquals(true, eval("true"));
        assertEquals(false, eval("false"));
        assertNull(eval("null"));
        assertEquals("A", eval("input.sku"));
        assertNull(eval("input.sku.missing"));
        assertNull(eval("missing"));
        assertEquals(new BigDecimal("2"), eval("abs(-2)"));
        assertEquals(new BigDecimal("3"), eval("len('abc')"));
        assertEquals(new BigDecimal("2"), eval("len(items)"));
        assertEquals(new BigDecimal("1"), eval("len(bag)"));
        assertEquals("2", eval("str(2)"));
        assertEquals("box", eval("coalesce(null, input.name)"));
        assertNull(eval("coalesce(null, null)"));
        assertEquals(false, eval("false && nope()"));
        assertEquals(true, eval("true || nope()"));
        assertEquals("null", Expressions.stringify(null));
        assertEquals("100", Expressions.stringify(new BigDecimal("1E+2")));
        assertEquals(new BigDecimal("4"), Expressions.decimal(4));
        assertThrows(ExprException.class, () -> Expressions.decimal("no"));
        Expressions.checkSyntax("1 + 1");
        assertEquals("bad", Expressions.asProtocol("bad").getMessage());
        assertThrows(ExprException.class, () -> new Expressions.Binary("??", new Expressions.Literal(1), new Expressions.Literal(2))
                .eval(new EvalContext(Map.of(), Map.of())));
    }

    @Test
    void rejectsMalformedExpressions() {
        assertThrows(ExprException.class, () -> eval(null));
        assertThrows(ExprException.class, () -> eval("'unterminated"));
        assertThrows(ExprException.class, () -> eval("@"));
        assertThrows(ExprException.class, () -> eval("1 2"));
        assertThrows(ExprException.class, () -> eval("1 / 0"));
        assertThrows(ExprException.class, () -> eval("!1"));
        assertThrows(ExprException.class, () -> eval("1 && true"));
        assertThrows(ExprException.class, () -> eval("true && 1"));
        assertThrows(ExprException.class, () -> eval("1 || false"));
        assertThrows(ExprException.class, () -> eval("false || 1"));
        assertThrows(ExprException.class, () -> eval("nope()"));
        assertThrows(ExprException.class, () -> eval("abs()"));
        assertThrows(ExprException.class, () -> eval("len(1)"));
        assertThrows(ExprException.class, () -> eval("coalesce()"));
        assertThrows(ExprException.class, () -> eval("input."));
        assertThrows(ExprException.class, () -> eval("(1"));
        assertThrows(ExprException.class, () -> eval(")"));
        assertThrows(ExprException.class, () -> eval("abs(1, 2)"));
        assertEquals("quote", eval("'quo\\te'"));
        assertTrue(eval("'a' == 'a'") instanceof Boolean);
        assertFalse((Boolean) eval("null == 1"));
        assertTrue((Boolean) eval("null == null"));
        assertEquals(false, eval("true == false"));
        assertEquals(true, eval("'a' != 'b'"));
    }
}

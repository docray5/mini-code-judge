package com.bilicki.codejudge.worker.judge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OutputComparatorTest {
    private final OutputComparator comparator = new OutputComparator();

    @Test
    void matchesSuccess() {
        assertTrue(comparator.matches("42", "42"));

        assertTrue(comparator.matches("line1\r\nline2", "line1\nline2"));

        assertTrue(comparator.matches("result   \n\n", "result"));
    }

    @Test
    void matchesFailure() {
        assertFalse(comparator.matches("foo", "bar"));

        assertFalse(comparator.matches("   indent", "indent"));

        assertFalse(comparator.matches(null, "expected"));
        assertFalse(comparator.matches("actual", null));
    }
}

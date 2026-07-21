package dev.diogo.paperspotlights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ShapeTest {

    @Test
    void parsesNamesCaseInsensitively() {
        assertEquals(Shape.CIRCLE, Shape.parse("circle"));
        assertEquals(Shape.CIRCLE, Shape.parse("CiRcLe"));
        assertEquals(Shape.SQUARE, Shape.parse(" SQUARE "));
    }

    @Test
    void rejectsMissingAndUnknownNames() {
        assertThrows(IllegalArgumentException.class, () -> Shape.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Shape.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> Shape.parse("triangle"));
    }
}

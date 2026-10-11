package com.block.hazard_api.hazards;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoundingBoxTest {

    @Test
    void parsesLonFirstOrder() {
        // California-ish: west -124.5, south 32.5, east -114, north 42
        assertEquals(new BoundingBox(32.5, 42.0, -124.5, -114.0), BoundingBox.parse("-124.5,32.5,-114,42"));
    }

    @Test
    void allowsSpaces() {
        assertEquals(new BoundingBox(32.5, 42.0, -124.5, -114.0), BoundingBox.parse("-124.5, 32.5, -114, 42"));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.parse("1,2,3"));           // 3 numbers
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.parse("a,2,3,4"));         // not a number
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.parse("-114,32.5,-124.5,42")); // west > east
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.parse("-124,95,-114,96"));  // lat > 90
    }

    @Test
    void overlapsUsesTheHazardRadius() {
        BoundingBox box = new BoundingBox(33, 34, -118, -117);
        assertTrue(box.overlaps(33.5, -117.5, 0));
        assertFalse(box.overlaps(35.0, -117.5, 10));   // ~110 km north, small radius
        assertTrue(box.overlaps(35.0, -117.5, 150));   // big radius reaches the box
    }
}

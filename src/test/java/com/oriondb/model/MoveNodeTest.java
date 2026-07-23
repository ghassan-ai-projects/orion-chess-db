package com.oriondb.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveNodeTest {

    private static MoveNode chainOf(int length) {
        MoveNode root = new MoveNode("e4");
        MoveNode last = root;
        for (int i = 1; i < length; i++) {
            MoveNode next = new MoveNode("m" + i);
            last.setNext(next);
            last = next;
        }
        return root;
    }

    @Test
    void copyAndMakeReadOnlySurviveVeryLongLines() {
        // Far beyond any real game; the previous recursive walk overflowed
        // the stack near 10k plies.
        int plies = 100_000;
        MoveNode root = chainOf(plies);
        root.setComment("start");
        root.addNag(1);

        MoveNode copy = root.copy();

        // Structure and annotations survive.
        assertEquals("start", copy.comment());
        assertEquals(List.of(1), copy.nags());
        int count = 0;
        MoveNode a = root;
        MoveNode b = copy;
        while (a != null) {
            assertNotNull(b, "copy ended early at node " + count);
            assertNotSame(a, b, "copy must be a distinct tree");
            assertEquals(a.san(), b.san());
            a = a.next();
            b = b.next();
            count++;
        }
        assertNull(b);
        assertEquals(plies, count);

        // Read-only freeze walks the same chain iteratively.
        root.makeReadOnly();
        assertTrue(root.isReadOnly());
        assertThrows(UnsupportedOperationException.class, () -> root.setComment("nope"));
        // The copy stays mutable.
        copy.setComment("still editable");
        assertEquals("still editable", copy.comment());
    }

    @Test
    void copyDuplicatesVariationsDeeply() {
        MoveNode root = chainOf(3);
        MoveNode variation = new MoveNode("c5");
        variation.setNext(new MoveNode("Nf3"));
        root.addVariation(variation);

        MoveNode copy = root.copy();
        assertEquals(1, copy.variations().size());
        MoveNode copiedVariation = copy.variations().get(0);
        assertNotSame(variation, copiedVariation);
        assertEquals("c5", copiedVariation.san());
        assertEquals("Nf3", copiedVariation.next().san());

        // Mutating the copy's variation must not touch the original.
        copiedVariation.setComment("copied");
        assertNull(variation.comment());
    }
}

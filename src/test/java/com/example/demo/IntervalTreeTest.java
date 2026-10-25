package com.example.demo;

import com.example.demo.util.IntervalTree;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class IntervalTreeTest {

    @Test
    void testSchedulingConflictDetection() {
        IntervalTree tree = new IntervalTree();
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 9, 0);

        // הוספת תור ראשון: 09:00 עד 10:00
        tree.insert(1L, base, base.plusHours(1));

        // הוספת תור שני: 11:00 עד 12:00
        tree.insert(2L, base.plusHours(2), base.plusHours(3));

        // בדיקה 1: ניסיון לקבוע תור מ-09:30 עד 10:30 (חייב לזהות התנגשות עם תור 1!)
        IntervalTree.IntervalNode conflict = tree.findOverlap(base.plusMinutes(30), base.plusMinutes(90));
        assertNotNull(conflict, "Should detect an overlap with appointment 1");
        assertEquals(1L, conflict.appointmentId);

        // בדיקה 2: ניסיון לקבוע תור מ-10:00 עד 11:00 (חלון פנוי לגמרי - אסור שתהיה התנגשות)
        IntervalTree.IntervalNode freeSlot = tree.findOverlap(base.plusHours(1), base.plusHours(2));
        assertNull(freeSlot, "Slot between 10:00 and 11:00 should be free");
    }
}

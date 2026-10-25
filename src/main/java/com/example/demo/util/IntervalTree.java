   package com.example.demo.util;

import java.time.LocalDateTime;

/**
 * Enterprise-grade Interval Tree implementation for appointment scheduling.
 * Provides O(log N) overlap detection and insertion.
 */
public class IntervalTree {

    public static class IntervalNode {
        public Long appointmentId;
        public LocalDateTime start;
        public LocalDateTime end;
        public LocalDateTime max;

        public IntervalNode left;
        public IntervalNode right;

        public IntervalNode(Long appointmentId, LocalDateTime start, LocalDateTime end) {
            this.appointmentId = appointmentId;
            this.start = start;
            this.end = end;
            this.max = end;
        }

        public boolean overlapsWith(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return this.start.isBefore(otherEnd) && otherStart.isBefore(this.end);
        }
    }

    private IntervalNode root;

    public void insert(Long appointmentId, LocalDateTime start, LocalDateTime end) {
        root = insertRec(root, appointmentId, start, end);
    }

    private IntervalNode insertRec(IntervalNode node, Long appointmentId, LocalDateTime start, LocalDateTime end) {
        if (node == null) {
            return new IntervalNode(appointmentId, start, end);
        }

        if (start.isBefore(node.start)) {
            node.left = insertRec(node.left, appointmentId, start, end);
        } else {
            node.right = insertRec(node.right, appointmentId, start, end);
        }

        if (node.max.isBefore(end)) {
            node.max = end;
        }

        return node;
    }

    public IntervalNode findOverlap(LocalDateTime start, LocalDateTime end) {
        return findOverlapRec(root, start, end);
    }

    private IntervalNode findOverlapRec(IntervalNode node, LocalDateTime start, LocalDateTime end) {
        if (node == null) {
            return null;
        }

        if (node.overlapsWith(start, end)) {
            return node;
        }

        if (node.left != null && node.left.max.isAfter(start)) {
            return findOverlapRec(node.left, start, end);
        }

        return findOverlapRec(node.right, start, end);
    }
}


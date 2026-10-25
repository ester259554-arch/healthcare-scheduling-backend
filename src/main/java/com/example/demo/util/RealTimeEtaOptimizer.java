package com.example.demo.util;

import com.example.demo.model.Appointment;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class RealTimeEtaOptimizer {

    public record PatientNode(
            Appointment appointment,
            LocalDateTime originalStart,
            LocalDateTime originalEnd,
            long durationMinutes,
            LocalDateTime eta,
            long delayMinutes,
            boolean isLate
    ) {}

    public record RescheduledSlot(
            Appointment appointment,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime oldStart,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime oldEnd,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime newStart,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime newEnd,
            long delayMinutes,
            boolean wasMovedToWaitlist
    ) {}

    private double bestTotalCost = Double.MAX_VALUE;
    private List<RescheduledSlot> bestSchedule = null;

    public List<RescheduledSlot> optimizeWindow(
            List<Appointment> windowAppointments,
            Map<Long, LocalDateTime> reportedEtas,
            LocalDateTime windowHardEndBoundary) {

        if (windowAppointments == null || windowAppointments.isEmpty()) {
            return List.of();
        }

        // נעילת זמני מקור ושמירת ה-ETA החדש על האובייקטים עצמם
        for (Appointment app : windowAppointments) {
            if (app.getOriginalStartTime() == null) {
                app.setOriginalStartTime(app.getStartTime());
            }
            if (app.getOriginalEndTime() == null) {
                app.setOriginalEndTime(app.getEndTime());
            }
            if (reportedEtas != null && app.getId() != null && reportedEtas.containsKey(app.getId())) {
                app.setReportedEta(reportedEtas.get(app.getId()));
            }
        }

        // מיון לפי שעת ההתחלה המקורית כדי לשמור על היגיון קדימויות 
        List<Appointment> sorted = new ArrayList<>(windowAppointments);
        sorted.sort(Comparator.comparing(app ->
                app.getOriginalStartTime() != null ? app.getOriginalStartTime() : app.getStartTime()
        ));

        List<PatientNode> nodes = new ArrayList<>();
        for (Appointment app : sorted) {
            LocalDateTime origStart = app.getOriginalStartTime() != null ? app.getOriginalStartTime() : app.getStartTime();
            LocalDateTime origEnd = app.getOriginalEndTime() != null ? app.getOriginalEndTime() : app.getEndTime();
            long duration = Math.max(1, Duration.between(origStart, origEnd).toMinutes());

            // שליפת ה-ETA: מהדיווח הנוכחי, או מדיווח קודם שנשמר על התור, ואם אין - שעת התור המקורית
            LocalDateTime eta = origStart;
            if (reportedEtas != null && app.getId() != null && reportedEtas.containsKey(app.getId())) {
                eta = reportedEtas.get(app.getId());
            } else if (app.getReportedEta() != null) {
                eta = app.getReportedEta();
            }

            long delay = Math.max(0, Duration.between(origStart, eta).toMinutes());
            boolean isLate = eta.isAfter(origStart);

            nodes.add(new PatientNode(app, origStart, origEnd, duration, eta, delay, isLate));
        }

        bestTotalCost = Double.MAX_VALUE;
        bestSchedule = null;

        LocalDateTime windowStart = nodes.get(0).originalStart();

        searchElasticTimeline(
                nodes,
                new boolean[nodes.size()],
                new ArrayList<>(),
                windowStart,
                windowHardEndBoundary,
                0.0
        );

        return bestSchedule != null ? bestSchedule : buildFallbackOriginal(nodes);
    }

    private void searchElasticTimeline(
            List<PatientNode> nodes,
            boolean[] used,
            List<RescheduledSlot> currentSchedule,
            LocalDateTime cursorTime,
            LocalDateTime hardBoundary,
            double currentCost) {

        if (currentCost >= bestTotalCost) {
            return;
        }

        if (currentSchedule.size() == nodes.size()) {
            bestTotalCost = currentCost;
            bestSchedule = new ArrayList<>(currentSchedule);
            return;
        }

        // גיזום מוקדם: אם סמן הזמן כבר חלף על פני שעת התחלה מקורית של מטופל דייקן/מקדים שטרם שובץ - הענף פסול
        for (int j = 0; j < nodes.size(); j++) {
            if (!used[j] && !nodes.get(j).isLate()) {
                if (cursorTime.isAfter(nodes.get(j).originalStart())) {
                    return;
                }
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            if (used[i]) continue;

            PatientNode candidate = nodes.get(i);

            // מטופל יכול להיכנס רק כשהרופא פנוי (cursorTime) ורק אחרי שהמטופל עצמו הגיע (candidate.eta)
            // אם המטופל לא דיווח שהקדים, ה-eta שלו שווה ל-originalStart ולכן הוא לעולם לא יוקדם בטעות!
            LocalDateTime actualStart = cursorTime.isAfter(candidate.eta()) ? cursorTime : candidate.eta();
            LocalDateTime actualEnd = actualStart.plusMinutes(candidate.durationMinutes());

            // 1. !!!הגנה מוחלטת על דייקנים ומקדימים: אסור שיתחילו אחרי השעה המקורית שלהם
            if (!candidate.isLate() && actualStart.isAfter(candidate.originalStart())) {
                continue;
            }

            // 2. מניעת דריסת מטופל דייקן/מקדים עתידי:
            // תור (של מאחר או של מקדים ארוך) לא יכול להיכנס לחור אם הסיום שלו גולש מעבר להתחלה המקורית של דייקן שטרם שובץ
            boolean crushesFuturePunctual = false;
            for (int j = 0; j < nodes.size(); j++) {
                if (j != i && !used[j] && !nodes.get(j).isLate()) {
                    if (actualEnd.isAfter(nodes.get(j).originalStart())) {
                        crushesFuturePunctual = true;
                        break;
                    }
                }
            }

            boolean exceedsHardBoundary = (hardBoundary != null && actualEnd.isAfter(hardBoundary));

            // אם המשבצת חוקית (לא דורסת דייקן עתידי ולא חורגת מגבול החלון)
            if (!crushesFuturePunctual && !exceedsHardBoundary) {
                long doctorIdleMinutes = Math.max(0, Duration.between(cursorTime, actualStart).toMinutes());
                // קנס כבד על זמן מת של רופא כדי לתמרץ הכנסת מטופל שהקדים לתוך החור
                double stepCost = doctorIdleMinutes * 120.0;

                long patientWaitMinutes = Math.max(0, Duration.between(candidate.eta(), actualStart).toMinutes());
                stepCost += patientWaitMinutes * 2.0;

                if (candidate.isLate()) {
                    long shiftFromOriginal = Math.abs(Duration.between(candidate.originalStart(), actualStart).toMinutes());
                    stepCost += shiftFromOriginal * (1.0 + (1.0 / (candidate.delayMinutes() + 1.0)));
                } else {
                    if (actualStart.isBefore(candidate.originalStart())) {
                        // בונוס על ניצול מוצלח של מטופל שהקדים למילוי חור בלו"ז
                        stepCost -= 5.0;
                    } else if (actualStart.equals(candidate.originalStart())) {
                        stepCost -= 0.5;
                    }
                }

                used[i] = true;
                currentSchedule.add(new RescheduledSlot(
                        candidate.appointment(),
                        candidate.originalStart(),
                        candidate.originalEnd(),
                        actualStart,
                        actualEnd,
                        candidate.delayMinutes(),
                        false
                ));

                searchElasticTimeline(nodes, used, currentSchedule, actualEnd, hardBoundary, currentCost + stepCost);

                currentSchedule.remove(currentSchedule.size() - 1);
                used[i] = false;
            }

            // 3. מפלט אחרון למאחרים בלבד:
            // אם מטופל מאחר אינו יכול להשתבץ בשום מקום בלו"ז בלי לפגוע בדייקנים או לחרוג מגבול היום - העברה לרשימת המתנה
            if (candidate.isLate()) {
                double overflowPenalty = 5000.0 + (candidate.delayMinutes() * 10.0);
                used[i] = true;
                currentSchedule.add(new RescheduledSlot(
                        candidate.appointment(),
                        candidate.originalStart(),
                        candidate.originalEnd(),
                        null,
                        null,
                        candidate.delayMinutes(),
                        true
                ));

                searchElasticTimeline(nodes, used, currentSchedule, cursorTime, hardBoundary, currentCost + overflowPenalty);

                currentSchedule.remove(currentSchedule.size() - 1);
                used[i] = false;
            }
        }
    }

    private List<RescheduledSlot> buildFallbackOriginal(List<PatientNode> nodes) {
        List<RescheduledSlot> fallback = new ArrayList<>();
        for (PatientNode n : nodes) {
            fallback.add(new RescheduledSlot(
                    n.appointment(), n.originalStart(), n.originalEnd(),
                    n.originalStart(), n.originalEnd(), n.delayMinutes(), false
            ));
        }
        return fallback;
    }
}
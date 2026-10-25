package com.example.demo;

import com.example.demo.model.Appointment;
import com.example.demo.repository.AppointmentRepository;
import com.example.demo.service.AppointmentService;
import com.example.demo.util.RealTimeEtaOptimizer;
import com.example.demo.util.RealTimeEtaOptimizer.RescheduledSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RealTimeEtaOptimizerTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    private final RealTimeEtaOptimizer optimizer = new RealTimeEtaOptimizer();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @BeforeEach
    void cleanDb() {
        appointmentRepository.deleteAll();
    }

    /**
     * תרחיש 1: פערי זמנים אלסטיים (20 דק' מול 40 דק') + שמירה על דייקן ב-11:00
     */
    @Test
    void testElasticDurationSwap_20mVs40m() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 25, 0, 0);

        Appointment p1 = withId(1L, 101L, "Patient A (20m - Late)", base.withHour(10).withMinute(0), base.withHour(10).withMinute(20));
        Appointment p2 = withId(2L, 101L, "Patient B (40m - Early)", base.withHour(10).withMinute(20), base.withHour(11).withMinute(0));
        Appointment p3 = withId(3L, 101L, "Patient C (20m - Punctual)", base.withHour(11).withMinute(0), base.withHour(11).withMinute(20));

        Map<Long, LocalDateTime> etas = new HashMap<>();
        etas.put(1L, base.withHour(10).withMinute(35)); // מאחר ב-35 דקות (יגיע ב-10:35)
        etas.put(2L, base.withHour(9).withMinute(55));  // הקדים (נמצא כבר מ-09:55)
        etas.put(3L, base.withHour(11).withMinute(0));  // דייקן (מגיע בדיוק ב-11:00)

        List<RescheduledSlot> result = optimizer.optimizeWindow(
                List.of(p1, p2, p3), etas, base.withHour(11).withMinute(20)
        );

        printScenario("SCENARIO 1: Elastic 20m vs 40m Swap", result, etas);

        // אימות: מטופל ב' (40 דק') הוקדם ל-10:00-10:40
        assertEquals(2L, result.get(0).appointment().getId());
        assertEquals(base.withHour(10).withMinute(0), result.get(0).newStart());
        assertEquals(base.withHour(10).withMinute(40), result.get(0).newEnd());

        // אימות: מטופל א' (20 דק') שובץ ל-10:40-11:00 (אחרי שהגיע ב-10:35)
        assertEquals(1L, result.get(1).appointment().getId());
        assertEquals(base.withHour(10).withMinute(40), result.get(1).newStart());
        assertEquals(base.withHour(11).withMinute(0), result.get(1).newEnd());

        // אימות: מטופל ג' הדייקן נכנס ב-11:00 על השנייה בלי להיפגע!
        assertEquals(3L, result.get(2).appointment().getId());
        assertEquals(base.withHour(11).withMinute(0), result.get(2).newStart());
    }

    /**
     * תרחיש 2: מקרה קצה - חסימת תור של 40 דק' מלהקדים אם הוא ידרוס מטופל דייקן באמצע!
     */
    @Test
    void testPunctualShield_Blocks40mFromCrushingPunctualPatient() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 25, 0, 0);

        Appointment p1 = withId(1L, 101L, "Patient A (20m - Late)", base.withHour(10).withMinute(0), base.withHour(10).withMinute(20));
        Appointment p2 = withId(2L, 101L, "Patient B (20m - Strictly Punctual)", base.withHour(10).withMinute(20), base.withHour(10).withMinute(40));
        Appointment p3 = withId(3L, 101L, "Patient C (40m - Early)", base.withHour(10).withMinute(40), base.withHour(11).withMinute(20));

        Map<Long, LocalDateTime> etas = new HashMap<>();
        etas.put(1L, base.withHour(10).withMinute(25)); // מאחר ל-10:25
        etas.put(2L, base.withHour(10).withMinute(20)); // מגיע בדיוק ב-10:20 (לא הקדים!)
        etas.put(3L, base.withHour(9).withMinute(55));  // תור של 40 דק' שהקדים ל-09:55

        List<RescheduledSlot> result = optimizer.optimizeWindow(
                List.of(p1, p2, p3), etas, base.withHour(11).withMinute(20)
        );

        printScenario("SCENARIO 2: Punctual Shield (Preventing 40m Block from Crushing 10:20 Punctual Patient)", result, etas);

        // אימות שכל המטופלים הדייקנים (B ו-C) לא נדחו אפילו בדקה אחת אחרי השעה המקורית שלהם!
        for (RescheduledSlot slot : result) {
            if (!slot.wasMovedToWaitlist() && slot.delayMinutes() == 0) {
                assertFalse(slot.newStart().isAfter(slot.oldStart()),
                        "Punctual patient must NEVER be delayed past original start time!");
            }
        }
    }

    /**
     * תרחיש 3: פקק תנועה מרובה משתתפים (6 מטופלים: 3 מאחרים בגדלים שונים, 2 מקדימים, ודייקנית אחת)
     */
    @Test
    void testMultiLateTrafficJam_6PatientsMixedDurations() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 25, 0, 0);

        Appointment p1 = withId(1L, 101L, "1. Dani (20m - Late +15m)", base.withHour(9).withMinute(0), base.withHour(9).withMinute(20));
        Appointment p2 = withId(2L, 101L, "2. Michal (20m - Late +35m)", base.withHour(9).withMinute(20), base.withHour(9).withMinute(40));
        Appointment p3 = withId(3L, 101L, "3. Yossi (20m - Early)", base.withHour(9).withMinute(40), base.withHour(10).withMinute(0));
        Appointment p4 = withId(4L, 101L, "4. Ronit (40m - Late +15m)", base.withHour(10).withMinute(0), base.withHour(10).withMinute(40));
        Appointment p5 = withId(5L, 101L, "5. Omer (20m - Early)", base.withHour(10).withMinute(40), base.withHour(11).withMinute(0));
        Appointment p6 = withId(6L, 101L, "6. Noa (30m - Punctual)", base.withHour(11).withMinute(0), base.withHour(11).withMinute(30));

        Map<Long, LocalDateTime> etas = new HashMap<>();
        etas.put(1L, base.withHour(9).withMinute(15));  // דני (09:00): מאחר ב-15 דק' -> יגיע ב-09:15
        etas.put(2L, base.withHour(9).withMinute(55));  // מיכל (09:20): מאחרת ב-35 דק' -> תגיע ב-09:55
        etas.put(3L, base.withHour(9).withMinute(0));   // יוסי (09:40): הקדים ל-09:00
        etas.put(4L, base.withHour(10).withMinute(15)); // רונית (10:00, 40 דק'): מאחרת ב-15 דק' -> תגיע ב-10:15
        etas.put(5L, base.withHour(9).withMinute(35));  // עומר (10:40): הקדים ל-09:35
        etas.put(6L, base.withHour(11).withMinute(0));  // נועה (11:00, 30 דק'): דייקנית על הדקה -> 11:00

        List<RescheduledSlot> result = optimizer.optimizeWindow(
                List.of(p1, p2, p3, p4, p5, p6), etas, base.withHour(11).withMinute(30)
        );

        printScenario("SCENARIO 3: 6-Patient Traffic Jam (Multi-Late + Variable Durations 20m/30m/40m)", result, etas);

        // 1. יוסי שהקדים ל-09:00 נכנס ראשון ב-09:00-09:20 (0 זמן מת לרופא)
        assertEquals(3L, result.get(0).appointment().getId());
        assertEquals(base.withHour(9).withMinute(0), result.get(0).newStart());

        // 2. דני (שאיחר רק ב-15 דק' ל-09:15) נכנס ב-09:20-09:40 לפני מיכל (שאיחרה ב-35 דק')!
        assertEquals(1L, result.get(1).appointment().getId());
        assertEquals(base.withHour(9).withMinute(20), result.get(1).newStart());

        // 3. עומר (שהקדים ל-09:35) ממלא את החור של 09:40-10:00
        assertEquals(5L, result.get(2).appointment().getId());
        assertEquals(base.withHour(9).withMinute(40), result.get(2).newStart());

        // 4. מיכל (שהגיעה ב-09:55) נכנסת ב-10:00-10:20
        assertEquals(2L, result.get(3).appointment().getId());
        assertEquals(base.withHour(10).withMinute(0), result.get(3).newStart());

        // 5. רונית (תור ארוך של 40 דק', הגיעה ב-10:15) נכנסת ב-10:20-11:00
        assertEquals(4L, result.get(4).appointment().getId());
        assertEquals(base.withHour(10).withMinute(20), result.get(4).newStart());
        assertEquals(base.withHour(11).withMinute(0), result.get(4).newEnd());

        // 6. נועה הדייקנית נכנסת ב-11:00-11:30 בדיוק על השנייה!
        assertEquals(6L, result.get(5).appointment().getId());
        assertEquals(base.withHour(11).withMinute(0), result.get(5).newStart());
    }

    /**
     * תרחיש 4: אינטגרציה מלאה מול ה-Service, מסד הנתונים וה-PriorityQueue במקרה של איחור קטלני
     */
    @Test
    void testDatabaseIntegrationAndExtremeOverflowToWaitlist() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 26, 0, 0);
        Long doctorId = 777L;

        // שמירת 3 תורים אמיתיים במסד הנתונים
        Appointment saved1 = appointmentService.scheduleAppointment(
                new Appointment(doctorId, "Extreme Late Patient (20m)", base.withHour(10).withMinute(0), base.withHour(10).withMinute(20))
        );
        Appointment saved2 = appointmentService.scheduleAppointment(
                new Appointment(doctorId, "Early Patient (20m)", base.withHour(10).withMinute(20), base.withHour(10).withMinute(40))
        );
        Appointment saved3 = appointmentService.scheduleAppointment(
                new Appointment(doctorId, "Next Window Anchor (20m)", base.withHour(12).withMinute(0), base.withHour(12).withMinute(20))
        );

        // המטופל של 10:00 מדווח שיגיע רק ב-12:15 (איחור קטלני של שעתיים ורבע שחוצה את גבול החלון!)
        List<RescheduledSlot> plan = appointmentService.reportPatientEta(
                saved1.getId(), base.withHour(12).withMinute(15), 5
        );

        printScenario("SCENARIO 4: Extreme Delay Overflow to PriorityQueue Waitlist", plan, Map.of(
                saved1.getId(), base.withHour(12).withMinute(15)
        ));

        // אימות שהמאחר הקיצוני הועבר אוטומטית לתור העדיפויות (Waitlist) של הרופא
        assertEquals(1, appointmentService.getWaitlistSize(doctorId));
        assertNotNull(saved2.getId());
        assertNotNull(saved3.getId());
    }

    private Appointment withId(Long id, Long doctorId, String name, LocalDateTime start, LocalDateTime end) {
        Appointment app = new Appointment(doctorId, name, start, end);
        app.setId(id);
        return app;
    }

    private void printScenario(String title, List<RescheduledSlot> slots, Map<Long, LocalDateTime> etas) {
        System.out.println("\n==========================================================================================");
        System.out.println("  " + title);
        System.out.println("==========================================================================================");
        System.out.printf("%-32s | %-13s | %-8s | %-13s | %-12s%n",
                "Patient", "Original Slot", "ETA", "New Slot", "Status");
        System.out.println("------------------------------------------------------------------------------------------");
        for (RescheduledSlot s : slots) {
            String orig = s.oldStart().format(TIME_FMT) + "-" + s.oldEnd().format(TIME_FMT);
            LocalDateTime etaTime = etas.getOrDefault(s.appointment().getId(), s.oldStart());
            String etaStr = etaTime.format(TIME_FMT);
            String newSlotStr = s.wasMovedToWaitlist()
                    ? "-> WAITLIST"
                    : s.newStart().format(TIME_FMT) + "-" + s.newEnd().format(TIME_FMT);
            String status = s.wasMovedToWaitlist()
                    ? "OVERFLOW (PQ)"
                    : (s.delayMinutes() > 0 ? "LATE (+" + s.delayMinutes() + "m)" : "PROTECTED");

            System.out.printf("%-32s | %-13s | %-8s | %-13s | %-12s%n",
                    s.appointment().getPatientName(), orig, etaStr, newSlotStr, status);
        }
        System.out.println("==========================================================================================\n");
    }
}


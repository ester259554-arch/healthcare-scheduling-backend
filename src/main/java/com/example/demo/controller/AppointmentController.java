package com.example.demo.controller;

import com.example.demo.model.Appointment;
import com.example.demo.model.WaitlistEntry;
import com.example.demo.service.AppointmentService;
import com.example.demo.util.RealTimeEtaOptimizer.RescheduledSlot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> createAppointment(@RequestBody Appointment appointment) {
        try {
            Appointment scheduled = service.scheduleAppointment(appointment);
            return new ResponseEntity<>(scheduled, HttpStatus.CREATED);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // נתפס על ידי ה-Interval Tree (התנגשות טווחים)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // נתפס על ידי ה-Database Unique Constraint (לחיצה באותה מילי-שנייה)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("התור נתפס כרגע על ידי משתמש אחר, אנא בחר מועד אחר.");
        }
    }

    @GetMapping
    public List<Appointment> getAllAppointments() {
        return service.getAllAppointments();
    }

    // הוספת מטופל לרשימת ההמתנה (PriorityQueue)
    @PostMapping("/waitlist")
    public ResponseEntity<String> addToWaitlist(@RequestBody WaitlistEntry entry) {
        service.addToWaitlist(entry);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Patient added to priority waitlist. Current queue size: " + service.getWaitlistSize(entry.getDoctorId()));
    }

    // ביטול תור ושיבוץ אוטומטי של הממתין הדחוף ביותר
    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelAppointment(@PathVariable Long id) {
        try {
            Optional<Appointment> reassigned = service.cancelAppointment(id);
            if (reassigned.isPresent()) {
                return ResponseEntity.ok(reassigned.get());
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            // תפיסת שגיאה במקרה שהתור לא נמצא והחזרת סטטוס 404
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    // דיווח ETA בזמן אמת והרצת אלגוריתם האופטימיזציה האלסטי
    @PostMapping("/{id}/eta")
    public ResponseEntity<List<RescheduledSlot>> reportEta(
        @PathVariable Long id,
        @RequestParam String estimatedArrival,
        @RequestParam(defaultValue = "3") int urgencyIfWaitlisted) {
    
        // המרה ל-LocalDateTime בתוך הפונקציה (מותאם לשעות ודקות בלבד, ללא שניות)
        LocalDateTime parsedArrival = LocalDateTime.parse(estimatedArrival, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        
        List<RescheduledSlot> updatedSchedule = service.reportPatientEta(id, parsedArrival, urgencyIfWaitlisted);
        return ResponseEntity.ok(updatedSchedule);
    }
}
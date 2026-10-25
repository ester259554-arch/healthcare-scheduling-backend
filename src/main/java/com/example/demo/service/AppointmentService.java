package com.example.demo.service;

import com.example.demo.model.Appointment;
import com.example.demo.model.WaitlistEntry;
import com.example.demo.repository.AppointmentRepository;
import com.example.demo.util.IntervalTree;
import com.example.demo.util.RealTimeEtaOptimizer;
import com.example.demo.util.RealTimeEtaOptimizer.RescheduledSlot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;

    // ניהול ערימת עדיפויות (PriorityQueue) בזיכרון לכל רופא בנפרד
    private final Map<Long, PriorityQueue<WaitlistEntry>> waitlistMap = new ConcurrentHashMap<>();

    // מנעולים ברמת הרופא (Lock Striping) ומפת זמני הגעה משוערים בזמן אמת
    private final Map<Long, ReentrantLock> doctorLocks = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, LocalDateTime>> liveDoctorEtas = new ConcurrentHashMap<>();
    private final RealTimeEtaOptimizer etaOptimizer = new RealTimeEtaOptimizer();

    public AppointmentService(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Transactional
    public Appointment scheduleAppointment(Appointment newAppointment) {
        List<Appointment> existingAppointments = appointmentRepository.findByDoctorId(newAppointment.getDoctorId());

        IntervalTree tree = new IntervalTree();
        for (Appointment app : existingAppointments) {
            tree.insert(app.getId(), app.getStartTime(), app.getEndTime());
        }

        if (tree.findOverlap(newAppointment.getStartTime(), newAppointment.getEndTime()) != null) {
            throw new IllegalArgumentException("Appointment overlaps with an existing schedule!");
        }

        return appointmentRepository.save(newAppointment);
    }
    
    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAll();
    }

    public synchronized void addToWaitlist(WaitlistEntry entry) {
        waitlistMap.computeIfAbsent(entry.getDoctorId(), k -> new PriorityQueue<>()).offer(entry);
    }

    @Transactional
    public synchronized Optional<Appointment> cancelAppointment(Long appointmentId) {
        Appointment cancelled = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found with id: " + appointmentId));

        Long doctorId = cancelled.getDoctorId();
        var startTime = cancelled.getStartTime();
        var endTime = cancelled.getEndTime();

        // 1. מחיקת התור המבוטל
        appointmentRepository.delete(cancelled);
        
        // 2. הפעלת flush כדי לאלץ את מסד הנתונים למחוק פיזית עכשיו, לפני שננסה לשמור את החדש באותה שעה!
        appointmentRepository.flush();

        PriorityQueue<WaitlistEntry> queue = waitlistMap.get(doctorId);
        if (queue != null && !queue.isEmpty()) {
            WaitlistEntry nextPatient = queue.poll();

            Appointment reassigned = new Appointment(
                    doctorId,
                    nextPatient.getPatientName(),
                    startTime,
                    endTime
            );
            // עכשיו השמירה תעבור חלק כי המשבצת באמת פנויה במסד הנתונים
            return Optional.of(appointmentRepository.save(reassigned));
        }

        return Optional.empty();
    }

    public synchronized int getWaitlistSize(Long doctorId) {
        PriorityQueue<WaitlistEntry> queue = waitlistMap.get(doctorId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * המתודה המסונכרנת: מקבלת דיווח איחור/הקדמה (ETA) של מטופל,
     * נועלת רק את היומן של הרופא הרלוונטי, ומבצעת אופטימיזציית חלון אלסטית.
     */
    @Transactional
    public List<RescheduledSlot> reportPatientEta(
            Long appointmentId,
            LocalDateTime estimatedArrival,
            int urgencyIfWaitlisted) {

        Appointment targetApp = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found: " + appointmentId));

        Long doctorId = targetApp.getDoctorId();

        // סנכרון ברמת הרופא הבודד (Fair ReentrantLock)
        ReentrantLock lock = doctorLocks.computeIfAbsent(doctorId, k -> new ReentrantLock(true));

        lock.lock();
        try {
            Map<Long, LocalDateTime> doctorEtaMap = liveDoctorEtas.computeIfAbsent(
                    doctorId, k -> new ConcurrentHashMap<>()
            );
            doctorEtaMap.put(appointmentId, estimatedArrival);

            LocalDateTime windowStart = targetApp.getStartTime().minusMinutes(30);
            LocalDateTime windowEnd = targetApp.getStartTime().plusHours(2);

            List<Appointment> allDoctorApps = appointmentRepository.findByDoctorId(doctorId);
            List<Appointment> windowApps = allDoctorApps.stream()
                    .filter(a -> !a.getStartTime().isBefore(windowStart) && a.getStartTime().isBefore(windowEnd))
                    .sorted(Comparator.comparing(Appointment::getStartTime))
                    .toList();

            LocalDateTime hardBoundary = allDoctorApps.stream()
                    .map(Appointment::getStartTime)
                    .filter(t -> !t.isBefore(windowEnd))
                    .min(LocalDateTime::compareTo)
                    .orElse(windowEnd.plusHours(1));

            List<RescheduledSlot> optimizedPlan = etaOptimizer.optimizeWindow(windowApps, doctorEtaMap, hardBoundary);

            // שלב א': הזזה זמנית לאזור חיץ למניעת התנגשות עם ה-Unique Constraint בטבלה
            for (int i = 0; i < optimizedPlan.size(); i++) {
                RescheduledSlot slot = optimizedPlan.get(i);
                Appointment app = slot.appointment();
                if (slot.wasMovedToWaitlist()) {
                    appointmentRepository.delete(app);
                    addToWaitlist(new WaitlistEntry(app.getId(), doctorId, app.getPatientName(), urgencyIfWaitlisted));
                    continue;
                } else if (!slot.newStart().equals(slot.oldStart()) || !slot.newEnd().equals(slot.oldEnd())) {
                    app.setStartTime(slot.oldStart().plusYears(100).plusMinutes(i));
                    app.setEndTime(slot.oldEnd().plusYears(100).plusMinutes(i));
                    appointmentRepository.save(app);
                }
            }
            appointmentRepository.flush();

            // שלב ב': שמירת השעות החדשות הסופיות
            for (RescheduledSlot slot : optimizedPlan) {
                if (!slot.wasMovedToWaitlist() &&
                    (!slot.newStart().equals(slot.oldStart()) || !slot.newEnd().equals(slot.oldEnd()))) {
                    Appointment app = slot.appointment();
                    app.setStartTime(slot.newStart());
                    app.setEndTime(slot.newEnd());
                    appointmentRepository.save(app);
                }
            }
            appointmentRepository.flush();

            return optimizedPlan;
        } finally {
            lock.unlock();
        }
    }
}
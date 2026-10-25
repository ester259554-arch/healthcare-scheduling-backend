package com.example.demo;

import com.example.demo.model.Appointment;
import com.example.demo.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class AppointmentConcurrencyTest {

    @Autowired
    private AppointmentService appointmentService;

    @Test
    void testConcurrentBookingConflict() throws InterruptedException {
        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Long doctorId = 99L; // מזהה ייעודי לבדיקה
        LocalDateTime startTime = LocalDateTime.of(2026, 11, 1, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 11, 1, 10, 45);

        for (int i = 0; i < numberOfThreads; i++) {
            final String patientName = "Patient_" + i;
            executor.submit(() -> {
                readyLatch.countDown(); // עומד על קו הזינוק
                try {
                    startGun.await(); // מחכה לירייה
                    
                    Appointment appointment = new Appointment(doctorId, patientName, startTime, endTime);
                    appointmentService.scheduleAppointment(appointment);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // כאן תיתפס החריגה (מה-IntervalTree או מה-DB Unique Constraint)
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(); // מחכים ששני התהליכים יתייצבו
        startGun.countDown(); // יריית זינוק - שניהם תוקפים את ה-Service יחד!
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // בדיקה שאחד בדיוק ניצח והשני נחסם
        assertEquals(1, successCount.get(), "Exactly one booking should succeed");
        assertEquals(1, failureCount.get(), "The duplicate booking should fail");
    }
}
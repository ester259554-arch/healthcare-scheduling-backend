package com.example.demo;

import com.example.demo.model.Appointment;
import com.example.demo.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.demo.service.AppointmentService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository repository;

    @InjectMocks
    private AppointmentService service;

    private Long doctorId;
    private LocalDateTime baseTime;

    @BeforeEach
    void setUp() {
        doctorId = 1L;
        // נקבע זמן בסיס: מחר ב-10:00 בבוקר
        baseTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    @Test
    void scheduleAppointment_whenNoOverlap_shouldSaveSuccessfully() {
        // תרחיש: אין תורים קודמים לרופא
        when(repository.findByDoctorId(doctorId)).thenReturn(List.of());

        Appointment newApp = new Appointment(doctorId, "Alice", baseTime, baseTime.plusMinutes(45));
        when(repository.save(newApp)).thenReturn(newApp);

        Appointment result = service.scheduleAppointment(newApp);

        assertNotNull(result);
        assertEquals("Alice", result.getPatientName());
        verify(repository, times(1)).save(newApp);
    }

    @Test
    void scheduleAppointment_whenDifferentDurationsOverlap_shouldThrowConflict() {
        //תרחיש מקרה הקצה:
        // תור קיים ארוך: 10:00 עד 11:30 (90 דקות)
        Appointment existingApp = new Appointment(doctorId, "Bob", baseTime, baseTime.plusMinutes(90));
        existingApp.setId(101L);

        when(repository.findByDoctorId(doctorId)).thenReturn(List.of(existingApp));

        // תור חדש קצר: מתחיל בתוך התור הקיים מ-10:30 עד 11:15 (45 דקות)
        Appointment conflictingApp = new Appointment(
                doctorId,
                "Charlie",
                baseTime.plusMinutes(30), // 10:30
                baseTime.plusMinutes(75)  // 11:15
        );

        // וידוא שה-Service זורק IllegalStateException בגלל ה-IntervalTree
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            service.scheduleAppointment(conflictingApp);
        });

        assertTrue(exception.getMessage().contains("Conflict detected"));
        assertTrue(exception.getMessage().contains("101")); // תפס את מזהה התור המתנגש
        
        // מוודא שה-Repository לא ניסה לשמור בסיס נתונים
        verify(repository, never()).save(any());
    }
}
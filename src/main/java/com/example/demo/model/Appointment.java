package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "appointments",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_doctor_start_time",
            columnNames = {"doctorId", "startTime"}
        )
    }
)
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Auto-generated unique identifier", accessMode = Schema.AccessMode.READ_ONLY, example = "1")
    private Long id;

    @Schema(description = "ID of the doctor", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long doctorId;

    @Schema(description = "Name of the patient", example = "YOSI", requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    @Schema(
        type = "string", 
        pattern = "yyyy-MM-dd'T'HH:mm", 
        example = "2026-10-25T10:00", 
        description = "Appointment start time (Format: yyyy-MM-dd'T'HH:mm)"
    )
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    @Schema(
        type = "string", 
        pattern = "yyyy-MM-dd'T'HH:mm", 
        example = "2026-10-25T10:30", 
        description = "Appointment end time (Format: yyyy-MM-dd'T'HH:mm)"
    )
    private LocalDateTime endTime;

    // שדות הזיכרון הפנימיים נשארים מוסתרים לחלוטין מ-Swagger ומ-JSON
    @JsonIgnore
    private LocalDateTime originalStartTime;

    @JsonIgnore
    private LocalDateTime originalEndTime;

    @JsonIgnore
    private LocalDateTime reportedEta;

    public Appointment() {}

    public Appointment(Long doctorId, String patientName, LocalDateTime startTime, LocalDateTime endTime) {
        this.doctorId = doctorId;
        this.patientName = patientName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.originalStartTime = startTime;
        this.originalEndTime = endTime;
    }

    // Getters and Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDoctorId() { return doctorId; }
    public void setDoctorId(Long doctorId) { this.doctorId = doctorId; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
        if (this.originalStartTime == null && startTime != null) {
            this.originalStartTime = startTime;
        }
    }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
        if (this.originalEndTime == null && endTime != null) {
            this.originalEndTime = endTime;
        }
    }

    public LocalDateTime getOriginalStartTime() { return originalStartTime; }
    public void setOriginalStartTime(LocalDateTime originalStartTime) { this.originalStartTime = originalStartTime; }

    public LocalDateTime getOriginalEndTime() { return originalEndTime; }
    public void setOriginalEndTime(LocalDateTime originalEndTime) { this.originalEndTime = originalEndTime; }

    public LocalDateTime getReportedEta() { return reportedEta; }
    public void setReportedEta(LocalDateTime reportedEta) { this.reportedEta = reportedEta; }
}
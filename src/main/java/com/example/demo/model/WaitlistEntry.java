package com.example.demo.model;

import java.time.Duration;
import java.time.LocalDateTime;

public class WaitlistEntry implements Comparable<WaitlistEntry> {

    private Long id;
    private Long doctorId;
    private String patientName;
    private LocalDateTime requestTime;
    private int urgencyLevel; // 1 (Routine) to 5 (Urgent)

    public WaitlistEntry(Long id, Long doctorId, String patientName, int urgencyLevel) {
        this.id = id;
        this.doctorId = doctorId;
        this.patientName = patientName;
        this.urgencyLevel = urgencyLevel;
        this.requestTime = LocalDateTime.now();
    }

    public double calculateScore() {
        long waitHours = Duration.between(requestTime, LocalDateTime.now()).toHours();
        double w1 = 1.5;
        double w2 = 10.0;
        return (w1 * waitHours) + (w2 * urgencyLevel);
    }

    @Override
    public int compareTo(WaitlistEntry other) {
        return Double.compare(other.calculateScore(), this.calculateScore());
    }

    public Long getId() { return id; }
    public Long getDoctorId() { return doctorId; }
    public String getPatientName() { return patientName; }
    public int getUrgencyLevel() { return urgencyLevel; }
    public LocalDateTime getRequestTime() { return requestTime; }
}
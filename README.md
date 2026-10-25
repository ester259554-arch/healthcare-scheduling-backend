# 🏥 Smart Elastic Appointment Scheduling Engine

Advanced enterprise-grade **Spring Boot** backend system for intelligent doctor schedule optimization, real-time ETA management, and minimization of physician idle time under strict concurrency constraints.

---

## 🎯 The Engineering & Business Problem
Traditional appointment systems treat doctor schedules as static blocks. In reality, patient delays create two major systemic failures:
1. **Physician Idle Time:** Doctors sit completely idle waiting for late patients.
2. **Cascading Delays:** Subsequent appointments get pushed back, penalizing punctual patients who arrived on time.

This system solves these issues through a **Real-Time Elastic Scheduling Engine (`RealTimeEtaOptimizer`)** that dynamically recalculates the active time window upon receiving ETA reports (delays or early arrivals), while strictly enforcing two ironclad business constraints:
* **Punctuality Protection:** A patient who arrives on time (or early) will *never* be penalized or pushed past their original appointment slot (`actualStart <= originalStart`).
* **Physical Availability Constraint:** The system will *never* advance an appointment for a patient who has not physically checked in (`actualStart >= max(cursorTime, reportedEta)`).

---

## 🧠 Architecture, Algorithms & System Design Decisions

### 1. Elastic Scheduling Algorithm (`Branch-and-Bound` with Asymmetric Cost Function)
Instead of a greedy heuristic that might squeeze a slot into a gap and break downstream constraints, the engine runs a state-space search (Backtracking with Early Pruning) over the active window:
* **Smart Gap Filling & Swapping:** If Patient A (30-min slot at 10:00) is delayed to 10:10, and Patient B (10-min slot at 10:30) reports arriving early at 09:59, the cost function penalizes physician idleness heavily ($120.0$ points/min) while rewarding early slot utilization ($-5.0$ points). The engine automatically swaps them: Patient B enters at 10:00–10:10, and Patient A enters right after at 10:10–10:40. **Zero physician idle time**.
* **Tiered Late Penalization:** A hyperbolic penalty function ensures that minor delays are kept as close to original slots as possible, while extreme delays trigger automatic offloading to a prioritized waitlist (`PriorityQueue`).

### 2. Stateful Temporal Anchors (Preventing Schedule Drift)
To support independent HTTP requests throughout the day (e.g., an early arrival report followed later by a delay report), the `Appointment` entity preserves original anchors (`originalStartTime`, `originalEndTime`) and reported ETAs behind-the-scenes using `@JsonIgnore`. This decouples internal stateful calculations from clean external DTO contracts.

### 3. Dual-Layer TOCTOU Race Condition Protection
When two concurrent users attempt booking the same doctor slot down to the exact millisecond, in-memory checks fail due to Time-Of-Check to Time-Of-Use race conditions. The system implements defense-in-depth:
1. **In-Memory Layer (`IntervalTree`):** Custom interval tree checking arbitrary time range overlaps in $O(\log N)$ time complexity.
2. **Database Layer (`@UniqueConstraint`):** Atomic composite unique index on `(doctorId, startTime)`. If two threads bypass the interval tree simultaneously, the DB storage engine atomically blocks the second write, throwing a `DataIntegrityViolationException` cleanly translated by the Controller into an HTTP `409 Conflict`.

### 4. Per-Doctor Concurrency Control (`Lock Striping`)
To avoid global locking bottlenecks (`synchronized`), the service implements **Lock Striping** via a `ConcurrentHashMap<Long, ReentrantLock>` utilizing fair reentrant locks (`fair = true`). Each doctor's schedule is managed under an independent lock, allowing high concurrency across different doctors while ensuring absolute thread safety within a single calendar.

### 5. Hibernate Write-Behind Synchronization (`Two-Phase Update` & `flush()`)
Within `@Transactional` boundaries, Hibernate queues SQL operations (`INSERT`, `UPDATE`, `DELETE`). To prevent unique constraint violations during slot swapping or immediate waitlist re-assignments, the system performs a two-phase buffer zone shifting (`+100 years`) coupled with explicit `flush()` invocations to force immediate DB synchronization.

---

## 🚀 REST API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/appointments` | Create a new appointment (validates overlaps via `IntervalTree` & DB Unique Index) |
| `GET` | `/api/appointments` | Retrieve all active appointments |
| `POST` | `/api/appointments/{id}/eta` | Report ETA (delay/early arrival in `yyyy-MM-dd'T'HH:mm`) & trigger elastic scheduling |
| `POST` | `/api/appointments/waitlist` | Add patient to prioritized waitlist (`PriorityQueue`) by urgency level |
| `DELETE` | `/api/appointments/{id}` | Cancel appointment & instantly auto-reassign the highest-priority waitlisted patient |

---

## ▶️ Quick Start

### Local Maven Execution:
```bash
./mvnw spring-boot:run
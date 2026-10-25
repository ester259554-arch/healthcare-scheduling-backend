# 🏥 Smart Elastic Appointment Scheduling Engine

> 🔍 **Interactive API Documentation:** This project features a built-in **Swagger UI** for real-time testing of all REST endpoints. *(Available locally at `/swagger-ui/index.html`)*

Advanced enterprise-grade **Spring Boot** backend system for intelligent doctor schedule optimization, real-time ETA management, and minimization of physician idle time under strict concurrency constraints.

---

## 🎯 The Engineering & Business Problem
Traditional appointment systems treat doctor schedules as rigid, static blocks. While clinical operations strictly value punctuality—and this system is not designed to normalize patient tardiness—real-world disruptions (traffic, emergencies, transit delays) are statistically inevitable. Ignoring them in software design leads to two systemic failures:
1. **Physician Idle Time:** Doctors sit completely idle waiting for delayed patients while other patients are already in the waiting room.
2. **Cascading Delays:** Subsequent appointments get pushed back in a domino effect, unfairly penalizing punctual patients.

Instead of relying on static schedules that break under real-world friction, this system implements a **Real-Time Elastic Scheduling Engine (`RealTimeEtaOptimizer`)**. Upon receiving live ETA updates (delays or early arrivals), the engine dynamically recalculates the active time window while strictly enforcing two ironclad business constraints:
* **Punctuality Protection:** A patient who arrives on time (or early) will *never* be penalized or pushed past their original appointment slot (`actualStart <= originalStart`).
* **Physical Availability Constraint:** The system will *never* advance an appointment for a patient who has not physically checked in (`actualStart >= max(cursorTime, reportedEta)`).

---

## 🧠 Architecture, Algorithms & System Design Decisions

### 1. Elastic Scheduling Algorithm (`Branch-and-Bound` with Asymmetric Cost Function)
Rather than performing naive 1-to-1 slot swaps or relying on a greedy heuristic that might squeeze a slot into a gap and break downstream constraints, the engine executes a multi-variable state-space search (**Backtracking with Early Pruning**) across the entire active window:
* **Combinatorial Evaluation & Smart Gap Filling:** The algorithm evaluates appointment durations, exact arrival timestamps, and downstream ripple effects before committing to a schedule. For example, if Patient A (30-min slot at 10:00) is delayed to 10:10, and Patient B (10-min slot at 10:30) checks in early at 09:59, the cost function heavily penalizes physician idleness ($120.0$ points/min) while rewarding early slot utilization ($-5.0$ points). Recognizing that Patient B's 10-minute duration fits seamlessly into the newly created 10-minute gap without delaying Patient A's 10:10 arrival, the engine reorders them: Patient B is seen at 10:00–10:10, and Patient A immediately follows at 10:10–10:40. **Result: Zero physician idle time and zero downstream disruption.**
* **Multi-Patient Delay Resolution & Magnitude Weighing:** When multiple patients report delays simultaneously, a hyperbolic penalty function evaluates the exact magnitude of each delay relative to slot durations. Minor delays (e.g., 5 minutes) are prioritized and kept anchored as close as possible to their original times, whereas severe delays that mathematically cannot fit into any valid permutation without violating a punctual patient's slot are automatically isolated and offloaded to a prioritized waitlist (`PriorityQueue`).

### 2. Stateful Temporal Anchors (Preventing Schedule Drift)
To support independent HTTP requests throughout the day (e.g., an early arrival report followed later by multiple delay reports), the `Appointment` entity preserves immutable original anchors (`originalStartTime`, `originalEndTime`) and reported ETAs behind the scenes using `@JsonIgnore`. This ensures the optimization algorithm always measures drift against the true baseline schedule while keeping external API payloads clean.

### 3. Dual-Layer TOCTOU Race Condition Protection
When two concurrent requests attempt to book the exact same doctor slot within the same millisecond, in-memory checks alone fail due to Time-Of-Check to Time-Of-Use (TOCTOU) race conditions. The system implements defense-in-depth:
1. **In-Memory Layer (`IntervalTree`):** Custom interval tree validating arbitrary time range overlaps (e.g., `10:00–10:30` vs. `10:15–10:45`) in $O(\log N)$ time complexity.
2. **Database Layer (`@UniqueConstraint`):** Atomic composite unique index on `(doctorId, startTime)`. If two concurrent threads bypass the in-memory tree at the exact same millisecond, the database storage engine atomically blocks the second write, throwing a `DataIntegrityViolationException` which is cleanly translated into an HTTP `409 Conflict`.

### 4. Per-Doctor Concurrency Control (`Lock Striping`)
To avoid global locking bottlenecks (`synchronized`), the service implements **Lock Striping** via a `ConcurrentHashMap<Long, ReentrantLock>` utilizing fair reentrant locks (`fair = true`). Each doctor's schedule is governed by an isolated lock, enabling high-throughput parallel processing across different doctors while guaranteeing strict thread safety within any single calendar.

### 5. Hibernate Write-Behind Synchronization (`Two-Phase Update` & `flush()`)
Within `@Transactional` boundaries, Hibernate queues SQL statements in a strict order (`INSERT`, `UPDATE`, `DELETE`). To prevent unique constraint collisions when swapping slots or immediately assigning a waitlisted patient into a newly canceled slot, the system executes a two-phase buffer shift (`+100 years`) paired with explicit `flush()` calls to enforce deterministic physical execution at the database level.

---

## 🚀 REST API Endpoints (Swagger UI)

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/appointments` | Create a new appointment (validates overlaps via `IntervalTree` & DB Unique Index) |
| `GET` | `/api/appointments` | Retrieve all active appointments |
| `POST` | `/api/appointments/{id}/eta` | Report live ETA (delay/early arrival in `yyyy-MM-dd'T'HH:mm`) & trigger elastic optimization |
| `POST` | `/api/appointments/waitlist` | Add patient to prioritized waitlist (`PriorityQueue`) by urgency level |
| `DELETE` | `/api/appointments/{id}` | Cancel appointment & instantly auto-assign the highest-priority waitlisted patient |

---

## ▶️ Quick Start & Testing

### Local Maven Execution:
```bash
./mvnw spring-boot:run
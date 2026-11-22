# Hospital Appointment and Emergency Queue Manager

Spring Boot 3 (Java 17) + Spring JDBC + MySQL + plain HTML/CSS/JS.
Emergency cases are served first; many registration desks can book at the same time while many doctors call patients,
and no appointment is ever assigned twice.

> **Honest status (read this first).** This project was written in a sandbox that has a Java 21 runtime but **no Maven,
> no MySQL, no internet and no Spring/JUnit jars**. So:
> * **Executed and passing here:** the framework-free core (priority comparator, thread-safe queue, claim logic,
>   symptom analyzer, 5 desks / 3 doctors / 100 appointments stress test) - 15 tests, see [Test results](#test-results).
> * **Written but NOT executed here:** everything that needs Spring, JDBC or MySQL (repositories, services,
>   `@Transactional` completion + rollback, DI/notification selection, dashboard queries, REST API, simulation against the DB,
>   the browser UI). Their code was only type-checked against hand-written stubs of the Spring API, which finds my own typos
>   but cannot prove it works with real Spring/MySQL. **Run `mvn test` on your machine (step 3) before trusting it.**

## 1. Prerequisites
* JDK 17+ (`java -version`), Maven 3.8+ (`mvn -v`), MySQL 8.x running locally.

## 2. Database
```bash
mysql -u root -p < database/create_database.sql      # creates database hospital_db and the 4 tables
mysql -u root -p hospital_db < database/sample_data.sql   # optional demo doctors + patients
```
Then put your MySQL username/password in `src/main/resources/application.properties`
(`spring.datasource.username`, `spring.datasource.password`). The app also runs `schema.sql` on startup
(`CREATE TABLE IF NOT EXISTS`, so it is harmless if the tables already exist).

## 3. Test, build, run
```bash
mvn test                  # unit + integration tests (integration tests use H2 in MySQL mode, no MySQL needed)
mvn spring-boot:run       # starts backend AND frontend on http://localhost:8080
```
There is no separate frontend server: the HTML/CSS/JS in `src/main/resources/static` is served by Spring Boot.
Open **http://localhost:8080**.

Switch notification implementation (requirement 5) in `application.properties`:
`app.notification.type=console` (default) or `app.notification.type=email`.

## 4. Where each of the six requirements is implemented
| # | Requirement | Where |
|---|-------------|-------|
| 1 | Patient, Doctor, Appointment models; department-wise doctors in a Map; priority-based collection | `model/Patient`, `model/Doctor`, `model/Appointment`; `DoctorService.doctorsByDepartment` (`Map<String, List<Doctor>>`); `core/AppointmentQueue` (wraps `java.util.PriorityQueue`) |
| 2 | Emergency level descending, request time ascending | `Appointment.PRIORITY_ORDER` comparator (level weight desc, then `requestTime` asc, then `id` only as a stable tie-breaker). Tested in `AppointmentQueueTest` |
| 3 | Concurrent desks + doctor workers, no double assignment | `AppointmentQueue` (one `ReentrantLock` + `Condition`), `Appointment.tryClaim` (`AtomicBoolean` CAS), `SimulationService` (`ExecutorService`, desk and doctor threads), `AppointmentService.callNext/assign` |
| 4 | JDBC persistence; transactional completion | `repository/*` (Spring `JdbcTemplate`, `SimpleJdbcInsert`, prepared statements), `schema.sql`; `AppointmentService.complete` is `@Transactional(rollbackFor = Exception.class)` |
| 5 | Spring DI of `AppointmentService`, `DoctorService`, `PatientRepository`, `NotificationService` with two implementations | Constructor injection throughout; `PatientRepository` (interface) + `JdbcPatientRepository`; `ConsoleNotificationService` / `EmailNotificationService` chosen by `@ConditionalOnProperty(app.notification.type)` |
| 6 | Waiting/treated counts, doctor workload, highest-priority pending case | `DashboardService` (SQL counts + `GROUP BY` workload; top case from `queue.peek()`), `GET /api/dashboard`, Dashboard / Doctor Workload pages |

### How a duplicate assignment is prevented (three independent layers)
1. **Queue lock:** `AppointmentQueue.poll()` removes the head while holding a `ReentrantLock`; two doctors can never receive the same object.
   (`PriorityQueue` itself is *not* thread-safe, so every access goes through this lock.)
2. **Atomic claim:** `Appointment.tryClaim(doctorId)` is an `AtomicBoolean.compareAndSet(false, true)`; only one caller can ever win.
3. **Database guard:** `UPDATE appointments SET status='IN_PROGRESS', doctor_id=? WHERE id=? AND status='WAITING'`; the affected-row count must be 1.

### How the transaction works
`complete()` does (1) `UPDATE ... status='COMPLETED' WHERE status='IN_PROGRESS'` and (2) `INSERT INTO consultation_history`.
If (2) throws, Spring rolls back (1). `complete()` is only ever called through the injected `AppointmentService`
bean (a Spring proxy), never from another method of the same class, because a self-call would bypass the proxy and skip the
transaction. A guard at the top of the method throws if no transaction is active. The patient notification is sent
`afterCommit`, so a rolled-back completion never notifies anyone.

### Emergency detection is a suggestion only
`SymptomAnalyzer` matches keywords (e.g. "chest pain", "difficulty breathing", "unconscious", "severe bleeding" -> CRITICAL;
"mild fever", "common cold" -> LOW). A nurse/doctor must open **Appointment Management**, review the suggestion, enter their name,
and either *Confirm* or *Change priority*. The API rejects an appointment without a reviewer name, and the suggested level, final level
and reviewer are stored. This is a software demonstration, not medical advice.

## 5. Project layout
```
pom.xml
database/create_database.sql, sample_data.sql
src/main/java/com/hospital/queue/
  model/        Patient, Doctor, Appointment, EmergencyLevel, AppointmentStatus, views/DTO records
  core/         AppointmentQueue                  (no Spring imports)
  triage/       SymptomAnalyzer                   (no Spring imports)
  repository/   PatientRepository + JdbcPatientRepository, Doctor/Appointment/ConsultationRepository
  service/      Patient/Doctor/Appointment/Dashboard/SimulationService, NotificationService (+2 impls), StartupLoader
  controller/   REST controllers + ApiExceptionHandler
  config/       AppConfig
src/main/resources/  application.properties, schema.sql, static/ (index.html, css/style.css, js/app.js)
src/test/java/...    core/ (queue + concurrency), triage/, integration/ (Spring + DB, notification selection)
```

## 6. Demo workflow (UI, ~3 minutes)
1. **Doctor Management:** add `Dr. Anita Rao / Cardiologist / Cardiology` and `Dr. Meera Nair / Physician / General`.
2. **Patient Registration:** register `Rohit Sharma, 58, Male, 9810000001, "Severe chest pain"` and `Priya Singh, 27, Female, 9810000002, "Mild fever and common cold"`.
3. **Appointment Management:** pick Priya -> *Get priority suggestion* (LOW) -> reviewer `Nurse Joy` -> *Confirm*. Do Rohit -> suggestion CRITICAL -> confirm. (Try *Change priority* on one to see the override recorded.)
4. **Emergency Queue:** Rohit (CRITICAL) is listed above Priya although Priya registered first. Choose Dr. Rao -> *Call next patient* -> Rohit is called.
5. Under *In consultation* press *Complete consultation*, enter a diagnosis -> the status update and history row are saved in one transaction.
6. **Dashboard / Doctor Workload / Consultation History** update. On the Dashboard, *Run simulation* (5 desks, 3 doctors, 100 appointments; needs >= 3 available doctors and an empty queue) and check "duplicate assignments: 0".

## 7. Test results
**Executed in the build sandbox (15 tests, all passed).** Because Maven/JUnit were unavailable, the three Spring-free test classes
(`AppointmentQueueTest`, `ConcurrencyTest`, `SymptomAnalyzerTest`) were compiled unchanged and run with a ~30-line stand-in for the JUnit 5
annotations/assertions, on Java 21. The concurrency tests were repeated 25 times with 0 failures. As a sanity check that they can fail, replacing
`tryClaim`'s compare-and-set with a non-atomic check made `onlyOneThreadCanClaimTheSameAppointment` fail (3 winners instead of 1).

| Test class | Covers |
|---|---|
| `AppointmentQueueTest` (7) | emergency-level ordering, earlier-request-time ordering, level beats time, id tie-break, peek, ordered snapshot, timed poll |
| `ConcurrencyTest` (4) | 5 concurrent desks keep correct order, single claim among 16 threads, 3 doctor workers drain 60 items with no duplicates, **stress: 5 desks + 3 doctors + 100 appointments, each assigned exactly once** |
| `SymptomAnalyzerTest` (4) | emergency vs normal suggestions, most-severe-wins, blank input |

**Written but NOT executed (need Spring + a database; run `mvn test`):** `HospitalIntegrationTest` (patient registration + unique IDs,
department Map, mandatory reviewer, appointment completion, **transaction rollback**, completing a waiting appointment is rejected,
dashboard statistics, and the **5 desks / 3 doctors / 100 appointments stress test through the real services and DB**) and
`NotificationSelectionTest` (console default vs email by configuration). The integration tests use H2 in MySQL-compatibility mode
(test scope only) so they need no MySQL server; the application itself uses MySQL.

## 8. Limitations
* Not compiled or run against real Spring Boot / MySQL / a browser in the build environment (see top). Expect that a first `mvn test`
  may surface small integration issues (e.g. H2-vs-MySQL SQL differences) that need fixing.
* `EmailNotificationService` is simulated (logs and keeps an in-memory outbox); no SMTP is contacted.
* The priority queue lives in memory. On restart it is rebuilt from `WAITING` rows (`StartupLoader`); it is a single-instance design (no clustering).
* Keyword triage is deliberately simple (substring match, no negation handling: "no chest pain" still suggests CRITICAL); staff always decide.
* No authentication/roles: "reviewed by" is a typed name, not a verified login.
* "Emergency patients" on the dashboard = appointments whose final level is CRITICAL or HIGH. "Available doctors" = on duty and not in a consultation.
* A doctor handles one consultation at a time (`call-next` is refused while one is in progress).
* The simulation creates demo patients ("Sim Patient N") and permanent rows; it requires an empty waiting queue.

## Bug-fix notes (latest)

* **Priority suggestion leaked between patients (UI):** `static/js/app.js` now clears the suggestion as soon as another patient is
  chosen, ignores a late response for a previously selected patient, and refuses to book if the suggestion belongs to a different patient.
  Test: `cd src/test/js && npm install && npm test` (jsdom, 4 tests).
* **Doctor could get two patients on a double-click:** `AppointmentService.callNext/callNextWaiting` now run "busy check -> take next -> assign"
  under a per-doctor lock. The second simultaneous call gets HTTP 409 ("still with a patient"); no patient is lost.
  Test: `DoubleAssignmentTest` (4 tests, part of `mvn test`; total is now 29 Java tests).
* The per-doctor lock works inside ONE application instance (which is how this project runs: one in-memory queue).
  Running several server instances against one database would need a database-level guard as well.

// ============================================================
// Workflow State Transition Logger — Java Implementation
// Design: Observer + Strategy + State Machine patterns
// Standard: Java 17
// Compile: javac WorkflowLogger.java
// Run:     java WorkflowLogger
// ============================================================

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// ============================================================
// SECTION 1: WorkflowState enum
// ============================================================

enum WorkflowState {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED
}

// ============================================================
// SECTION 2: StateTransition — the core log record
// ============================================================

class StateTransition {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    final String transitionId;
    final String workflowId;
    final String stepId;
    final WorkflowState fromState;
    final WorkflowState toState;
    final Instant timestamp;
    final String triggeredBy;
    final Map<String, String> metadata;

    StateTransition(String transitionId, String workflowId, String stepId,
                    WorkflowState fromState, WorkflowState toState,
                    Instant timestamp, String triggeredBy,
                    Map<String, String> metadata) {
        this.transitionId = transitionId;
        this.workflowId   = workflowId;
        this.stepId       = stepId;
        this.fromState    = fromState;
        this.toState      = toState;
        this.timestamp    = timestamp;
        this.triggeredBy  = triggeredBy;
        this.metadata     = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(FMT.format(timestamp)).append("] ")
          .append("TID=").append(transitionId)
          .append(" | WF=").append(workflowId)
          .append(" | STEP=").append(stepId)
          .append(" | ").append(fromState)
          .append(" -> ").append(toState)
          .append(" | by=").append(triggeredBy);
        if (!metadata.isEmpty()) {
            sb.append(" | meta={");
            StringJoiner sj = new StringJoiner(", ");
            metadata.forEach((k, v) -> sj.add(k + ":" + v));
            sb.append(sj).append("}");
        }
        return sb.toString();
    }
}

// ============================================================
// SECTION 3: TransitionValidator — enforces the state machine
// ============================================================

class TransitionValidator {
    private final Map<WorkflowState, Set<WorkflowState>> validTransitions =
        new EnumMap<>(WorkflowState.class);

    TransitionValidator() {
        validTransitions.put(WorkflowState.PENDING,    EnumSet.of(
            WorkflowState.RUNNING, WorkflowState.CANCELLED, WorkflowState.SKIPPED));
        validTransitions.put(WorkflowState.RUNNING,    EnumSet.of(
            WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.CANCELLED));
        validTransitions.put(WorkflowState.FAILED,     EnumSet.of(WorkflowState.PENDING)); // retry
        validTransitions.put(WorkflowState.COMPLETED,  EnumSet.noneOf(WorkflowState.class)); // terminal
        validTransitions.put(WorkflowState.CANCELLED,  EnumSet.noneOf(WorkflowState.class)); // terminal
        validTransitions.put(WorkflowState.SKIPPED,    EnumSet.noneOf(WorkflowState.class)); // terminal
    }

    boolean isValid(WorkflowState from, WorkflowState to) {
        Set<WorkflowState> targets = validTransitions.get(from);
        return targets != null && targets.contains(to);
    }

    String validTargetsFor(WorkflowState from) {
        Set<WorkflowState> targets = validTransitions.get(from);
        if (targets == null || targets.isEmpty()) return "none (terminal state)";
        StringJoiner sj = new StringJoiner(", ");
        targets.forEach(s -> sj.add(s.name()));
        return sj.toString();
    }
}

// ============================================================
// SECTION 4: Observer interface
// ============================================================

interface ITransitionObserver {
    void onTransition(StateTransition transition);
}

// ============================================================
// SECTION 5: Logger interface (Strategy pattern) + implementations
// ============================================================

interface ITransitionLogger {
    void log(StateTransition transition);
    List<StateTransition> getHistory(String stepId);
    List<StateTransition> getAllHistory();
}

// ---- Concrete Strategy A: In-Memory Logger ----

class InMemoryLogger implements ITransitionLogger {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, List<StateTransition>> stepHistory = new HashMap<>();
    private final List<StateTransition> allTransitions = new ArrayList<>();

    @Override
    public void log(StateTransition transition) {
        lock.lock();
        try {
            stepHistory.computeIfAbsent(transition.stepId, k -> new ArrayList<>())
                       .add(transition);
            allTransitions.add(transition);
            System.out.println("[LOG] " + transition);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StateTransition> getHistory(String stepId) {
        lock.lock();
        try {
            return new ArrayList<>(stepHistory.getOrDefault(stepId, Collections.emptyList()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StateTransition> getAllHistory() {
        lock.lock();
        try { return new ArrayList<>(allTransitions); }
        finally { lock.unlock(); }
    }

    // Persist all transitions to a CSV file for offline analysis
    public void exportToCSV(String filepath) throws IOException {
        lock.lock();
        try (PrintWriter pw = new PrintWriter(new FileWriter(filepath))) {
            pw.println("transition_id,workflow_id,step_id,from_state,to_state,timestamp,triggered_by");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                                                     .withZone(ZoneId.systemDefault());
            for (StateTransition t : allTransitions) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s%n",
                    t.transitionId, t.workflowId, t.stepId,
                    t.fromState,    t.toState,
                    fmt.format(t.timestamp), t.triggeredBy);
            }
        } finally {
            lock.unlock();
        }
    }
}

// ---- Concrete Strategy B: File Logger (write-through) ----

class FileLogger implements ITransitionLogger {
    private final ReentrantLock lock = new ReentrantLock();
    private final String filepath;
    private final Map<String, List<StateTransition>> stepHistory = new HashMap<>();

    FileLogger(String filepath) throws IOException {
        this.filepath = filepath;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filepath, true))) {
            pw.println("=== WorkflowTransitionLog opened at " + Instant.now() + " ===");
        }
    }

    @Override
    public void log(StateTransition transition) {
        lock.lock();
        try {
            stepHistory.computeIfAbsent(transition.stepId, k -> new ArrayList<>())
                       .add(transition);
            try (PrintWriter pw = new PrintWriter(new FileWriter(filepath, true))) {
                pw.println(transition);
            } catch (IOException e) {
                System.err.println("[FileLogger] Write error: " + e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StateTransition> getHistory(String stepId) {
        lock.lock();
        try {
            return new ArrayList<>(stepHistory.getOrDefault(stepId, Collections.emptyList()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StateTransition> getAllHistory() {
        lock.lock();
        try {
            List<StateTransition> all = new ArrayList<>();
            stepHistory.values().forEach(all::addAll);
            return all;
        } finally {
            lock.unlock();
        }
    }
}

// ============================================================
// SECTION 6: WorkflowStep — owns its state machine
// ============================================================

class WorkflowStep {
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private final String stepId;
    private final String stepName;
    private final String workflowId;
    private WorkflowState currentState;
    private final List<StateTransition> history = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    WorkflowStep(String stepId, String stepName, String workflowId) {
        this.stepId       = stepId;
        this.stepName     = stepName;
        this.workflowId   = workflowId;
        this.currentState = WorkflowState.PENDING;
    }

    String getStepId()   { return stepId;   }
    String getStepName() { return stepName; }

    WorkflowState getCurrentState() {
        lock.lock();
        try { return currentState; }
        finally { lock.unlock(); }
    }

    // Attempt a state transition; throws IllegalArgumentException on invalid edge
    StateTransition transition(WorkflowState newState, TransitionValidator validator,
                               String triggeredBy, Map<String, String> metadata) {
        lock.lock();
        try {
            if (!validator.isValid(currentState, newState)) {
                throw new IllegalArgumentException(
                    "Invalid transition for step '" + stepName + "': "
                    + currentState + " -> " + newState
                    + ". Valid targets from " + currentState
                    + ": " + validator.validTargetsFor(currentState));
            }
            StateTransition t = new StateTransition(
                "T" + COUNTER.incrementAndGet(),
                workflowId, stepId,
                currentState, newState,
                Instant.now(), triggeredBy,
                metadata
            );
            history.add(t);
            currentState = newState;
            return t;
        } finally {
            lock.unlock();
        }
    }

    List<StateTransition> getHistory() {
        lock.lock();
        try { return new ArrayList<>(history); }
        finally { lock.unlock(); }
    }

    boolean isTerminal() {
        WorkflowState s = getCurrentState();
        return s == WorkflowState.COMPLETED
            || s == WorkflowState.CANCELLED
            || s == WorkflowState.SKIPPED;
    }
}

// ============================================================
// SECTION 7: Workflow — container of ordered steps
// ============================================================

class Workflow {
    private final String workflowId;
    private final String workflowName;
    private final List<WorkflowStep> steps = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int stepCounter = 0;

    Workflow(String workflowId, String workflowName) {
        this.workflowId   = workflowId;
        this.workflowName = workflowName;
    }

    String getWorkflowId()   { return workflowId;   }
    String getWorkflowName() { return workflowName; }

    WorkflowStep addStep(String stepName) {
        lock.lock();
        try {
            String stepId = workflowId + "_S" + (++stepCounter);
            WorkflowStep step = new WorkflowStep(stepId, stepName, workflowId);
            steps.add(step);
            return step;
        } finally {
            lock.unlock();
        }
    }

    WorkflowStep getStep(String stepId) {
        lock.lock();
        try {
            return steps.stream()
                        .filter(s -> s.getStepId().equals(stepId))
                        .findFirst()
                        .orElse(null);
        } finally {
            lock.unlock();
        }
    }

    List<WorkflowStep> getSteps() {
        lock.lock();
        try { return new ArrayList<>(steps); }
        finally { lock.unlock(); }
    }

    // Aggregate status: FAILED > CANCELLED > RUNNING > PENDING > COMPLETED
    WorkflowState getOverallStatus() {
        lock.lock();
        try {
            boolean hasPending = false;
            boolean hasRunning = false;
            for (WorkflowStep step : steps) {
                WorkflowState s = step.getCurrentState();
                if (s == WorkflowState.FAILED)    return WorkflowState.FAILED;
                if (s == WorkflowState.CANCELLED) return WorkflowState.CANCELLED;
                if (s == WorkflowState.RUNNING)   hasRunning = true;
                if (s == WorkflowState.PENDING)   hasPending = true;
            }
            if (hasRunning) return WorkflowState.RUNNING;
            if (hasPending) return WorkflowState.PENDING;
            return WorkflowState.COMPLETED;
        } finally {
            lock.unlock();
        }
    }

    void printStatus() {
        System.out.println("\n=== Workflow: " + workflowName + " [" + workflowId + "] ===");
        System.out.println("Overall Status: " + getOverallStatus());
        System.out.println("-".repeat(55));
        for (WorkflowStep step : getSteps()) {
            System.out.printf("  [%-10s] %-25s => %s%n",
                step.getStepId(), step.getStepName(), step.getCurrentState());
        }
        System.out.println();
    }
}

// ============================================================
// SECTION 8: Concrete Observers
// ============================================================

// Sends an alert whenever a step fails or is cancelled
class AlertObserver implements ITransitionObserver {
    @Override
    public void onTransition(StateTransition t) {
        if (t.toState == WorkflowState.FAILED) {
            System.out.printf("[ALERT] *** Step %s in workflow %s FAILED! (triggered by: %s)%n",
                t.stepId, t.workflowId, t.triggeredBy);
        }
        if (t.toState == WorkflowState.CANCELLED) {
            System.out.printf("[ALERT] Step %s was CANCELLED in workflow %s%n",
                t.stepId, t.workflowId);
        }
    }
}

// Keeps an immutable audit log of every transition
class AuditObserver implements ITransitionObserver {
    private final List<StateTransition> auditLog = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void onTransition(StateTransition t) {
        lock.lock();
        try {
            auditLog.add(t);
            System.out.printf("[AUDIT] Recorded %s (%s -> %s) for step %s%n",
                t.transitionId, t.fromState, t.toState, t.stepId);
        } finally {
            lock.unlock();
        }
    }

    int getAuditCount() {
        lock.lock();
        try { return auditLog.size(); }
        finally { lock.unlock(); }
    }
}

// ============================================================
// SECTION 9: WorkflowEngine — coordinates everything
// ============================================================

class WorkflowEngine {
    private final Map<String, Workflow> workflows = new HashMap<>();
    private final ITransitionLogger logger;
    private final List<ITransitionObserver> observers = new ArrayList<>();
    private final TransitionValidator validator = new TransitionValidator();
    private final ReentrantLock lock = new ReentrantLock();
    private int workflowCounter = 0;

    WorkflowEngine(ITransitionLogger logger) {
        this.logger = logger;
    }

    void addObserver(ITransitionObserver observer) {
        lock.lock();
        try { observers.add(observer); }
        finally { lock.unlock(); }
    }

    Workflow createWorkflow(String name) {
        lock.lock();
        try {
            String id = "WF" + (++workflowCounter);
            Workflow wf = new Workflow(id, name);
            workflows.put(id, wf);
            System.out.println("[ENGINE] Created workflow: " + name + " [" + id + "]");
            return wf;
        } finally {
            lock.unlock();
        }
    }

    Workflow getWorkflow(String wfId) {
        lock.lock();
        try { return workflows.get(wfId); }
        finally { lock.unlock(); }
    }

    boolean transitionStep(String wfId, String stepId, WorkflowState newState,
                           String triggeredBy, Map<String, String> metadata) {
        Workflow wf = getWorkflow(wfId);
        if (wf == null) {
            System.err.println("[ENGINE] Workflow not found: " + wfId);
            return false;
        }
        WorkflowStep step = wf.getStep(stepId);
        if (step == null) {
            System.err.println("[ENGINE] Step not found: " + stepId);
            return false;
        }
        try {
            StateTransition t = step.transition(newState, validator, triggeredBy, metadata);
            logger.log(t);
            notifyObservers(t);
            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("[ENGINE] Transition rejected: " + e.getMessage());
            return false;
        }
    }

    private void notifyObservers(StateTransition t) {
        List<ITransitionObserver> snapshot;
        lock.lock();
        try { snapshot = new ArrayList<>(observers); }
        finally { lock.unlock(); }
        // Notify outside the lock to avoid potential deadlock if an observer
        // calls back into the engine
        for (ITransitionObserver obs : snapshot) obs.onTransition(t);
    }

    void printStepHistory(String stepId) {
        List<StateTransition> history = logger.getHistory(stepId);
        System.out.println("\n--- Transition history for step: " + stepId + " ---");
        if (history.isEmpty()) {
            System.out.println("  (no transitions recorded)");
            return;
        }
        history.forEach(t -> System.out.println("  " + t));
        System.out.println();
    }

    void printAllWorkflows() {
        lock.lock();
        try { workflows.values().forEach(Workflow::printStatus); }
        finally { lock.unlock(); }
    }
}

// ============================================================
// SECTION 10: Main — demonstration scenarios
// ============================================================

public class WorkflowLogger {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================");
        System.out.println("   Workflow State Transition Logger — Demo");
        System.out.println("================================================\n");

        InMemoryLogger logger = new InMemoryLogger();
        WorkflowEngine engine = new WorkflowEngine(logger);

        engine.addObserver(new AlertObserver());
        AuditObserver auditObs = new AuditObserver();
        engine.addObserver(auditObs);

        Map<String, String> noMeta = Collections.emptyMap();

        // ------------------------------------------------
        // Scenario 1: Order Processing (happy path + retry)
        // ------------------------------------------------
        System.out.println("\n=== SCENARIO 1: Order Processing ===");
        Workflow wf1         = engine.createWorkflow("Order Processing");
        WorkflowStep sValidate   = wf1.addStep("Validate Order");
        WorkflowStep sPayment    = wf1.addStep("Process Payment");
        WorkflowStep sInventory  = wf1.addStep("Check Inventory");
        WorkflowStep sShip       = wf1.addStep("Ship Order");

        wf1.printStatus();

        engine.transitionStep(wf1.getWorkflowId(), sValidate.getStepId(),
                              WorkflowState.RUNNING,   "order-service", noMeta);
        engine.transitionStep(wf1.getWorkflowId(), sValidate.getStepId(),
                              WorkflowState.COMPLETED, "order-service", noMeta);

        engine.transitionStep(wf1.getWorkflowId(), sPayment.getStepId(),
                              WorkflowState.RUNNING,   "payment-service", noMeta);
        engine.transitionStep(wf1.getWorkflowId(), sPayment.getStepId(),
                              WorkflowState.COMPLETED, "payment-service", noMeta);

        engine.transitionStep(wf1.getWorkflowId(), sInventory.getStepId(),
                              WorkflowState.RUNNING, "inventory-service", noMeta);
        // Simulate failure
        engine.transitionStep(wf1.getWorkflowId(), sInventory.getStepId(),
                              WorkflowState.FAILED, "inventory-service",
                              Map.of("reason", "out_of_stock", "sku", "SKU-9921"));
        // Retry: FAILED -> PENDING -> RUNNING -> COMPLETED
        engine.transitionStep(wf1.getWorkflowId(), sInventory.getStepId(),
                              WorkflowState.PENDING,   "retry-handler", noMeta);
        engine.transitionStep(wf1.getWorkflowId(), sInventory.getStepId(),
                              WorkflowState.RUNNING,   "inventory-service", noMeta);
        engine.transitionStep(wf1.getWorkflowId(), sInventory.getStepId(),
                              WorkflowState.COMPLETED, "inventory-service", noMeta);

        engine.transitionStep(wf1.getWorkflowId(), sShip.getStepId(),
                              WorkflowState.RUNNING,   "shipping-service", noMeta);
        engine.transitionStep(wf1.getWorkflowId(), sShip.getStepId(),
                              WorkflowState.COMPLETED, "shipping-service", noMeta);

        wf1.printStatus();

        // ------------------------------------------------
        // Scenario 2: Data Migration (failure, no retry)
        // ------------------------------------------------
        System.out.println("\n=== SCENARIO 2: Data Migration ===");
        Workflow wf2         = engine.createWorkflow("Data Migration");
        WorkflowStep sExtract   = wf2.addStep("Extract Data");
        WorkflowStep sTransform = wf2.addStep("Transform Data");
        wf2.addStep("Load to DB");
        wf2.addStep("Verify Integrity");

        engine.transitionStep(wf2.getWorkflowId(), sExtract.getStepId(),
                              WorkflowState.RUNNING,   "etl-pipeline", noMeta);
        engine.transitionStep(wf2.getWorkflowId(), sExtract.getStepId(),
                              WorkflowState.COMPLETED, "etl-pipeline", noMeta);
        engine.transitionStep(wf2.getWorkflowId(), sTransform.getStepId(),
                              WorkflowState.RUNNING,   "etl-pipeline", noMeta);
        engine.transitionStep(wf2.getWorkflowId(), sTransform.getStepId(),
                              WorkflowState.FAILED,    "etl-pipeline",
                              Map.of("error", "schema_mismatch", "column", "user_id"));

        wf2.printStatus();

        // ------------------------------------------------
        // Scenario 3: CI Pipeline (skipped optional step)
        // ------------------------------------------------
        System.out.println("\n=== SCENARIO 3: CI Pipeline with optional step ===");
        Workflow wf3       = engine.createWorkflow("CI Pipeline");
        WorkflowStep sBuild  = wf3.addStep("Build");
        WorkflowStep sLint   = wf3.addStep("Lint (optional)");
        WorkflowStep sTest   = wf3.addStep("Unit Tests");
        WorkflowStep sDeploy = wf3.addStep("Deploy to Staging");

        engine.transitionStep(wf3.getWorkflowId(), sBuild.getStepId(),
                              WorkflowState.RUNNING,   "ci-runner", noMeta);
        engine.transitionStep(wf3.getWorkflowId(), sBuild.getStepId(),
                              WorkflowState.COMPLETED, "ci-runner", noMeta);
        // Lint is skipped by configuration
        engine.transitionStep(wf3.getWorkflowId(), sLint.getStepId(),
                              WorkflowState.SKIPPED,   "ci-runner",
                              Map.of("reason", "skipped_by_config"));
        engine.transitionStep(wf3.getWorkflowId(), sTest.getStepId(),
                              WorkflowState.RUNNING,   "ci-runner", noMeta);
        engine.transitionStep(wf3.getWorkflowId(), sTest.getStepId(),
                              WorkflowState.COMPLETED, "ci-runner", noMeta);
        engine.transitionStep(wf3.getWorkflowId(), sDeploy.getStepId(),
                              WorkflowState.RUNNING,   "ci-runner", noMeta);
        engine.transitionStep(wf3.getWorkflowId(), sDeploy.getStepId(),
                              WorkflowState.COMPLETED, "ci-runner", noMeta);

        wf3.printStatus();

        // ------------------------------------------------
        // Guard: test invalid transition rejection
        // ------------------------------------------------
        System.out.println("=== Testing invalid transition (COMPLETED -> RUNNING) ===");
        engine.transitionStep(wf1.getWorkflowId(), sShip.getStepId(),
                              WorkflowState.RUNNING, "test-harness", noMeta);

        // ------------------------------------------------
        // Print per-step history
        // ------------------------------------------------
        engine.printStepHistory(sInventory.getStepId());
        engine.printStepHistory(sTransform.getStepId());

        // ------------------------------------------------
        // Summary and export
        // ------------------------------------------------
        System.out.println("[AUDIT] Total transitions recorded: " + auditObs.getAuditCount());
        logger.exportToCSV("workflow_transitions.csv");
        System.out.println("[EXPORT] Transition log written to workflow_transitions.csv");
    }
}

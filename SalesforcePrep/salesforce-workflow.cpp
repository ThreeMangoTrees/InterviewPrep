// ============================================================
// Workflow State Transition Logger — C++ Implementation
// Design: Observer + Strategy + State Machine patterns
// Standard: C++17
// ============================================================

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <chrono>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <set>
#include <atomic>

// ============================================================
// SECTION 1: ENUMS & HELPERS
// ============================================================

enum class WorkflowState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
};

std::string stateToString(WorkflowState state) {
    switch (state) {
        case WorkflowState::PENDING:   return "PENDING";
        case WorkflowState::RUNNING:   return "RUNNING";
        case WorkflowState::COMPLETED: return "COMPLETED";
        case WorkflowState::FAILED:    return "FAILED";
        case WorkflowState::CANCELLED: return "CANCELLED";
        case WorkflowState::SKIPPED:   return "SKIPPED";
        default:                       return "UNKNOWN";
    }
}

// ============================================================
// SECTION 2: StateTransition — the core log record
// ============================================================

struct StateTransition {
    std::string transition_id;
    std::string workflow_id;
    std::string step_id;
    WorkflowState from_state;
    WorkflowState to_state;
    std::chrono::system_clock::time_point timestamp;
    std::string triggered_by;
    std::map<std::string, std::string> metadata;  // arbitrary key-value context

    std::string toString() const {
        auto time_t = std::chrono::system_clock::to_time_t(timestamp);
        std::ostringstream oss;
        oss << "[" << std::put_time(std::localtime(&time_t), "%Y-%m-%d %H:%M:%S") << "] "
            << "TID=" << transition_id
            << " | WF=" << workflow_id
            << " | STEP=" << step_id
            << " | " << stateToString(from_state)
            << " -> " << stateToString(to_state)
            << " | by=" << triggered_by;
        if (!metadata.empty()) {
            oss << " | meta={";
            bool first = true;
            for (const auto& [k, v] : metadata) {
                if (!first) oss << ", ";
                oss << k << ":" << v;
                first = false;
            }
            oss << "}";
        }
        return oss.str();
    }
};

// ============================================================
// SECTION 3: Transition Validator — enforces the state machine
// ============================================================

class TransitionValidator {
private:
    std::map<WorkflowState, std::set<WorkflowState>> valid_transitions_;

public:
    TransitionValidator() {
        // Define the legal edges in the state machine graph
        valid_transitions_[WorkflowState::PENDING]   = {
            WorkflowState::RUNNING,
            WorkflowState::CANCELLED,
            WorkflowState::SKIPPED
        };
        valid_transitions_[WorkflowState::RUNNING]   = {
            WorkflowState::COMPLETED,
            WorkflowState::FAILED,
            WorkflowState::CANCELLED
        };
        valid_transitions_[WorkflowState::FAILED]    = {
            WorkflowState::PENDING   // retry — reset to PENDING then re-run
        };
        valid_transitions_[WorkflowState::COMPLETED] = {}; // terminal
        valid_transitions_[WorkflowState::CANCELLED] = {}; // terminal
        valid_transitions_[WorkflowState::SKIPPED]   = {}; // terminal
    }

    bool isValid(WorkflowState from, WorkflowState to) const {
        auto it = valid_transitions_.find(from);
        if (it == valid_transitions_.end()) return false;
        return it->second.count(to) > 0;
    }

    std::string validTargetsFor(WorkflowState from) const {
        auto it = valid_transitions_.find(from);
        if (it == valid_transitions_.end() || it->second.empty())
            return "none (terminal state)";
        std::string result;
        for (auto s : it->second) {
            if (!result.empty()) result += ", ";
            result += stateToString(s);
        }
        return result;
    }
};

// ============================================================
// SECTION 4: Observer Interface — react to transitions
// ============================================================

class ITransitionObserver {
public:
    virtual ~ITransitionObserver() = default;
    virtual void onTransition(const StateTransition& transition) = 0;
};

// ============================================================
// SECTION 5: Logger Interface (Strategy pattern)
// ============================================================

class ITransitionLogger {
public:
    virtual ~ITransitionLogger() = default;
    virtual void log(const StateTransition& transition) = 0;
    virtual std::vector<StateTransition> getHistory(const std::string& step_id) const = 0;
    virtual std::vector<StateTransition> getAllHistory() const = 0;
};

// ---- Concrete Strategy A: In-Memory Logger ----

class InMemoryLogger : public ITransitionLogger {
private:
    mutable std::mutex mutex_;
    std::unordered_map<std::string, std::vector<StateTransition>> step_history_;
    std::vector<StateTransition> all_transitions_;

public:
    void log(const StateTransition& transition) override {
        std::lock_guard<std::mutex> lock(mutex_);
        step_history_[transition.step_id].push_back(transition);
        all_transitions_.push_back(transition);
        std::cout << "[LOG] " << transition.toString() << "\n";
    }

    std::vector<StateTransition> getHistory(const std::string& step_id) const override {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = step_history_.find(step_id);
        if (it == step_history_.end()) return {};
        return it->second;
    }

    std::vector<StateTransition> getAllHistory() const override {
        std::lock_guard<std::mutex> lock(mutex_);
        return all_transitions_;
    }

    // Persist all transitions to a CSV file for offline analysis
    void exportToCSV(const std::string& filepath) const {
        std::lock_guard<std::mutex> lock(mutex_);
        std::ofstream out(filepath);
        out << "transition_id,workflow_id,step_id,from_state,to_state,timestamp,triggered_by\n";
        for (const auto& t : all_transitions_) {
            auto time_t = std::chrono::system_clock::to_time_t(t.timestamp);
            std::ostringstream ts;
            ts << std::put_time(std::localtime(&time_t), "%Y-%m-%dT%H:%M:%S");
            out << t.transition_id << ","
                << t.workflow_id   << ","
                << t.step_id       << ","
                << stateToString(t.from_state) << ","
                << stateToString(t.to_state)   << ","
                << ts.str()        << ","
                << t.triggered_by  << "\n";
        }
    }
};

// ---- Concrete Strategy B: File Logger (write-through) ----

class FileLogger : public ITransitionLogger {
private:
    mutable std::mutex mutex_;
    std::string filepath_;
    std::unordered_map<std::string, std::vector<StateTransition>> step_history_;

public:
    explicit FileLogger(const std::string& filepath) : filepath_(filepath) {
        std::ofstream out(filepath_, std::ios::app);
        auto now = std::time(nullptr);
        out << "=== WorkflowTransitionLog opened at "
            << std::put_time(std::localtime(&now), "%Y-%m-%d %H:%M:%S")
            << " ===\n";
    }

    void log(const StateTransition& transition) override {
        std::lock_guard<std::mutex> lock(mutex_);
        step_history_[transition.step_id].push_back(transition);
        std::ofstream out(filepath_, std::ios::app);
        out << transition.toString() << "\n";
    }

    std::vector<StateTransition> getHistory(const std::string& step_id) const override {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = step_history_.find(step_id);
        if (it == step_history_.end()) return {};
        return it->second;
    }

    std::vector<StateTransition> getAllHistory() const override {
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<StateTransition> all;
        for (const auto& [k, v] : step_history_) {
            all.insert(all.end(), v.begin(), v.end());
        }
        return all;
    }
};

// ============================================================
// SECTION 6: WorkflowStep — owns its state machine
// ============================================================

class WorkflowStep {
private:
    std::string step_id_;
    std::string step_name_;
    std::string workflow_id_;
    WorkflowState current_state_;
    std::vector<StateTransition> history_;
    mutable std::mutex mutex_;

    static std::string generateTransitionId() {
        static std::atomic<int> counter{0};
        return "T" + std::to_string(++counter);
    }

public:
    WorkflowStep(std::string step_id, std::string step_name, std::string workflow_id)
        : step_id_(std::move(step_id))
        , step_name_(std::move(step_name))
        , workflow_id_(std::move(workflow_id))
        , current_state_(WorkflowState::PENDING) {}

    const std::string& getStepId()   const { return step_id_;   }
    const std::string& getStepName() const { return step_name_; }

    WorkflowState getCurrentState() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return current_state_;
    }

    // Attempt a state transition; throws std::invalid_argument on invalid edge
    StateTransition transition(
        WorkflowState new_state,
        const TransitionValidator& validator,
        const std::string& triggered_by = "system",
        const std::map<std::string, std::string>& metadata = {}
    ) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!validator.isValid(current_state_, new_state)) {
            throw std::invalid_argument(
                "Invalid transition for step '" + step_name_ + "': "
                + stateToString(current_state_) + " -> " + stateToString(new_state)
                + ". Valid targets from " + stateToString(current_state_)
                + ": " + validator.validTargetsFor(current_state_)
            );
        }

        StateTransition t;
        t.transition_id = generateTransitionId();
        t.workflow_id   = workflow_id_;
        t.step_id       = step_id_;
        t.from_state    = current_state_;
        t.to_state      = new_state;
        t.timestamp     = std::chrono::system_clock::now();
        t.triggered_by  = triggered_by;
        t.metadata      = metadata;

        history_.push_back(t);
        current_state_ = new_state;
        return t;
    }

    std::vector<StateTransition> getHistory() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return history_;
    }

    bool isTerminal() const {
        auto s = getCurrentState();
        return s == WorkflowState::COMPLETED
            || s == WorkflowState::CANCELLED
            || s == WorkflowState::SKIPPED;
    }
};

// ============================================================
// SECTION 7: Workflow — container of ordered steps
// ============================================================

class Workflow {
private:
    std::string workflow_id_;
    std::string workflow_name_;
    std::vector<std::shared_ptr<WorkflowStep>> steps_;
    mutable std::mutex mutex_;
    int step_counter_ = 0;

public:
    Workflow(std::string workflow_id, std::string workflow_name)
        : workflow_id_(std::move(workflow_id))
        , workflow_name_(std::move(workflow_name)) {}

    const std::string& getWorkflowId()   const { return workflow_id_;   }
    const std::string& getWorkflowName() const { return workflow_name_; }

    std::shared_ptr<WorkflowStep> addStep(const std::string& step_name) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::string step_id = workflow_id_ + "_S" + std::to_string(++step_counter_);
        auto step = std::make_shared<WorkflowStep>(step_id, step_name, workflow_id_);
        steps_.push_back(step);
        return step;
    }

    std::shared_ptr<WorkflowStep> getStep(const std::string& step_id) const {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const auto& step : steps_) {
            if (step->getStepId() == step_id) return step;
        }
        return nullptr;
    }

    const std::vector<std::shared_ptr<WorkflowStep>>& getSteps() const {
        return steps_;
    }

    // Aggregate status: FAILED > RUNNING > PENDING > COMPLETED
    WorkflowState getOverallStatus() const {
        std::lock_guard<std::mutex> lock(mutex_);
        bool has_pending    = false;
        bool has_running    = false;

        for (const auto& step : steps_) {
            auto s = step->getCurrentState();
            if (s == WorkflowState::FAILED)    return WorkflowState::FAILED;
            if (s == WorkflowState::CANCELLED) return WorkflowState::CANCELLED;
            if (s == WorkflowState::RUNNING)   has_running = true;
            if (s == WorkflowState::PENDING)   has_pending = true;
        }
        if (has_running) return WorkflowState::RUNNING;
        if (has_pending) return WorkflowState::PENDING;
        return WorkflowState::COMPLETED;
    }

    void printStatus() const {
        std::cout << "\n=== Workflow: " << workflow_name_
                  << " [" << workflow_id_ << "] ===\n";
        std::cout << "Overall Status: " << stateToString(getOverallStatus()) << "\n";
        std::cout << std::string(55, '-') << "\n";
        for (const auto& step : steps_) {
            std::cout << "  [" << step->getStepId() << "] "
                      << std::left << std::setw(25) << step->getStepName()
                      << " => " << stateToString(step->getCurrentState()) << "\n";
        }
        std::cout << "\n";
    }
};

// ============================================================
// SECTION 8: Concrete Observers
// ============================================================

// Sends an alert whenever a step fails
class AlertObserver : public ITransitionObserver {
public:
    void onTransition(const StateTransition& t) override {
        if (t.to_state == WorkflowState::FAILED) {
            std::cout << "[ALERT] *** Step " << t.step_id
                      << " in workflow " << t.workflow_id
                      << " FAILED! (triggered by: " << t.triggered_by << ")\n";
        }
        if (t.to_state == WorkflowState::CANCELLED) {
            std::cout << "[ALERT] Step " << t.step_id
                      << " was CANCELLED in workflow " << t.workflow_id << "\n";
        }
    }
};

// Keeps an immutable audit log of every transition
class AuditObserver : public ITransitionObserver {
private:
    std::vector<StateTransition> audit_log_;
    mutable std::mutex mutex_;

public:
    void onTransition(const StateTransition& t) override {
        std::lock_guard<std::mutex> lock(mutex_);
        audit_log_.push_back(t);
        std::cout << "[AUDIT] Recorded " << t.transition_id
                  << " (" << stateToString(t.from_state)
                  << " -> " << stateToString(t.to_state)
                  << ") for step " << t.step_id << "\n";
    }

    size_t getAuditCount() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return audit_log_.size();
    }
};

// ============================================================
// SECTION 9: WorkflowEngine — coordinates everything
// ============================================================

class WorkflowEngine {
private:
    std::unordered_map<std::string, std::shared_ptr<Workflow>> workflows_;
    std::shared_ptr<ITransitionLogger> logger_;
    std::vector<std::shared_ptr<ITransitionObserver>> observers_;
    TransitionValidator validator_;
    mutable std::mutex mutex_;
    int workflow_counter_ = 0;

    void notifyObservers(const StateTransition& t) {
        for (const auto& obs : observers_) {
            obs->onTransition(t);
        }
    }

public:
    explicit WorkflowEngine(std::shared_ptr<ITransitionLogger> logger)
        : logger_(std::move(logger)) {}

    void addObserver(std::shared_ptr<ITransitionObserver> observer) {
        std::lock_guard<std::mutex> lock(mutex_);
        observers_.push_back(std::move(observer));
    }

    std::shared_ptr<Workflow> createWorkflow(const std::string& name) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::string id = "WF" + std::to_string(++workflow_counter_);
        auto wf = std::make_shared<Workflow>(id, name);
        workflows_[id] = wf;
        std::cout << "[ENGINE] Created workflow: " << name << " [" << id << "]\n";
        return wf;
    }

    std::shared_ptr<Workflow> getWorkflow(const std::string& wf_id) const {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = workflows_.find(wf_id);
        return (it != workflows_.end()) ? it->second : nullptr;
    }

    bool transitionStep(
        const std::string& wf_id,
        const std::string& step_id,
        WorkflowState new_state,
        const std::string& triggered_by = "system",
        const std::map<std::string, std::string>& metadata = {}
    ) {
        auto wf = getWorkflow(wf_id);
        if (!wf) {
            std::cerr << "[ENGINE] Workflow not found: " << wf_id << "\n";
            return false;
        }
        auto step = wf->getStep(step_id);
        if (!step) {
            std::cerr << "[ENGINE] Step not found: " << step_id << "\n";
            return false;
        }
        try {
            auto t = step->transition(new_state, validator_, triggered_by, metadata);
            logger_->log(t);
            notifyObservers(t);
            return true;
        } catch (const std::invalid_argument& e) {
            std::cerr << "[ENGINE] Transition rejected: " << e.what() << "\n";
            return false;
        }
    }

    void printAllWorkflows() const {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const auto& [id, wf] : workflows_) {
            wf->printStatus();
        }
    }

    void printStepHistory(const std::string& step_id) const {
        auto history = logger_->getHistory(step_id);
        std::cout << "\n--- Transition history for step: " << step_id << " ---\n";
        if (history.empty()) {
            std::cout << "  (no transitions recorded)\n";
            return;
        }
        for (const auto& t : history) {
            std::cout << "  " << t.toString() << "\n";
        }
        std::cout << "\n";
    }
};

// ============================================================
// SECTION 10: main — demonstration scenarios
// ============================================================

int main() {
    std::cout << "================================================\n";
    std::cout << "   Workflow State Transition Logger — Demo\n";
    std::cout << "================================================\n\n";

    // Engine with in-memory logger (swap to FileLogger for persistence)
    auto logger = std::make_shared<InMemoryLogger>();
    WorkflowEngine engine(logger);

    // Wire up observers
    engine.addObserver(std::make_shared<AlertObserver>());
    auto audit_obs = std::make_shared<AuditObserver>();
    engine.addObserver(audit_obs);

    // ------------------------------------------------
    // Scenario 1: Order Processing (happy path + retry)
    // ------------------------------------------------
    std::cout << "\n=== SCENARIO 1: Order Processing ===\n";
    auto wf1          = engine.createWorkflow("Order Processing");
    auto s_validate   = wf1->addStep("Validate Order");
    auto s_payment    = wf1->addStep("Process Payment");
    auto s_inventory  = wf1->addStep("Check Inventory");
    auto s_ship       = wf1->addStep("Ship Order");

    wf1->printStatus();

    engine.transitionStep(wf1->getWorkflowId(), s_validate->getStepId(),
                          WorkflowState::RUNNING, "order-service");
    engine.transitionStep(wf1->getWorkflowId(), s_validate->getStepId(),
                          WorkflowState::COMPLETED, "order-service");

    engine.transitionStep(wf1->getWorkflowId(), s_payment->getStepId(),
                          WorkflowState::RUNNING, "payment-service");
    engine.transitionStep(wf1->getWorkflowId(), s_payment->getStepId(),
                          WorkflowState::COMPLETED, "payment-service");

    engine.transitionStep(wf1->getWorkflowId(), s_inventory->getStepId(),
                          WorkflowState::RUNNING, "inventory-service");
    // Simulate failure
    engine.transitionStep(wf1->getWorkflowId(), s_inventory->getStepId(),
                          WorkflowState::FAILED, "inventory-service",
                          {{"reason", "out_of_stock"}, {"sku", "SKU-9921"}});
    // Retry: FAILED -> PENDING -> RUNNING -> COMPLETED
    engine.transitionStep(wf1->getWorkflowId(), s_inventory->getStepId(),
                          WorkflowState::PENDING, "retry-handler");
    engine.transitionStep(wf1->getWorkflowId(), s_inventory->getStepId(),
                          WorkflowState::RUNNING, "inventory-service");
    engine.transitionStep(wf1->getWorkflowId(), s_inventory->getStepId(),
                          WorkflowState::COMPLETED, "inventory-service");

    engine.transitionStep(wf1->getWorkflowId(), s_ship->getStepId(),
                          WorkflowState::RUNNING, "shipping-service");
    engine.transitionStep(wf1->getWorkflowId(), s_ship->getStepId(),
                          WorkflowState::COMPLETED, "shipping-service");

    wf1->printStatus();

    // ------------------------------------------------
    // Scenario 2: Data Migration (failure, no retry)
    // ------------------------------------------------
    std::cout << "\n=== SCENARIO 2: Data Migration ===\n";
    auto wf2        = engine.createWorkflow("Data Migration");
    auto s_extract  = wf2->addStep("Extract Data");
    auto s_transform= wf2->addStep("Transform Data");
    auto s_load     = wf2->addStep("Load to DB");
    auto s_verify   = wf2->addStep("Verify Integrity");

    engine.transitionStep(wf2->getWorkflowId(), s_extract->getStepId(),
                          WorkflowState::RUNNING, "etl-pipeline");
    engine.transitionStep(wf2->getWorkflowId(), s_extract->getStepId(),
                          WorkflowState::COMPLETED, "etl-pipeline");
    engine.transitionStep(wf2->getWorkflowId(), s_transform->getStepId(),
                          WorkflowState::RUNNING, "etl-pipeline");
    engine.transitionStep(wf2->getWorkflowId(), s_transform->getStepId(),
                          WorkflowState::FAILED, "etl-pipeline",
                          {{"error", "schema_mismatch"}, {"column", "user_id"}});

    wf2->printStatus();

    // ------------------------------------------------
    // Scenario 3: Skipped step
    // ------------------------------------------------
    std::cout << "\n=== SCENARIO 3: CI Pipeline with optional step ===\n";
    auto wf3         = engine.createWorkflow("CI Pipeline");
    auto s_build     = wf3->addStep("Build");
    auto s_lint      = wf3->addStep("Lint (optional)");
    auto s_test      = wf3->addStep("Unit Tests");
    auto s_deploy    = wf3->addStep("Deploy to Staging");

    engine.transitionStep(wf3->getWorkflowId(), s_build->getStepId(),
                          WorkflowState::RUNNING, "ci-runner");
    engine.transitionStep(wf3->getWorkflowId(), s_build->getStepId(),
                          WorkflowState::COMPLETED, "ci-runner");
    // Lint is skipped by configuration
    engine.transitionStep(wf3->getWorkflowId(), s_lint->getStepId(),
                          WorkflowState::SKIPPED, "ci-runner",
                          {{"reason", "skipped_by_config"}});
    engine.transitionStep(wf3->getWorkflowId(), s_test->getStepId(),
                          WorkflowState::RUNNING, "ci-runner");
    engine.transitionStep(wf3->getWorkflowId(), s_test->getStepId(),
                          WorkflowState::COMPLETED, "ci-runner");
    engine.transitionStep(wf3->getWorkflowId(), s_deploy->getStepId(),
                          WorkflowState::RUNNING, "ci-runner");
    engine.transitionStep(wf3->getWorkflowId(), s_deploy->getStepId(),
                          WorkflowState::COMPLETED, "ci-runner");

    wf3->printStatus();

    // ------------------------------------------------
    // Guard: Test invalid transition rejection
    // ------------------------------------------------
    std::cout << "=== Testing invalid transition (COMPLETED -> RUNNING) ===\n";
    engine.transitionStep(wf1->getWorkflowId(), s_ship->getStepId(),
                          WorkflowState::RUNNING, "test-harness");

    // ------------------------------------------------
    // Print per-step history
    // ------------------------------------------------
    engine.printStepHistory(s_inventory->getStepId());
    engine.printStepHistory(s_transform->getStepId());

    // ------------------------------------------------
    // Summary
    // ------------------------------------------------
    std::cout << "[AUDIT] Total transitions recorded: "
              << audit_obs->getAuditCount() << "\n";

    logger->exportToCSV("workflow_transitions.csv");
    std::cout << "[EXPORT] Transition log written to workflow_transitions.csv\n";

    return 0;
}

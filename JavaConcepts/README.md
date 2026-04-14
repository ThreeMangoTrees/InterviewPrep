# City Parking System — Java Concepts Showcase

A fully working parking management system for a city lot that handles **Cars, Bikes, Trucks, and Rental Vehicles**. Every file is written to demonstrate a specific set of Java language features, making the project useful both as a real design example and as a study reference for Java concepts.

---

## Table of Contents

1. [System Design](#system-design)
2. [Class Reference](#class-reference)
3. [Java Concepts Index](#java-concepts-index)
4. [How to Run](#how-to-run)
5. [Sample Output](#sample-output)

---

## System Design

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        CITY PARKING LOT                          │
│                                                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐  │
│  │  EntryGate  │    │  ExitGate   │    │  CapacityMonitor    │  │
│  │  (queues,   │    │  (payment,  │    │  (ParkingObserver)  │  │
│  │   admit)    │    │   release)  │    │                     │  │
│  └──────┬──────┘    └──────┬──────┘    └─────────┬───────────┘  │
│         │                  │                     │ (Observer)    │
│         └────────┬─────────┘                     │              │
│                  ▼                                │              │
│         ┌────────────────┐   notifies    ────────┘              │
│         │   ParkingLot   │──────────────▶ observers             │
│         │  (Singleton)   │                                      │
│         └───────┬────────┘                                      │
│                 │  manages                                       │
│         ┌───────▼────────────────────────┐                      │
│         │         ParkingFloor (1..N)     │                      │
│         │  ┌──────────┬────────────────┐ │                      │
│         │  │SmallSpot │  MediumSpot    │ │                      │
│         │  │ (Bikes)  │ (Cars/Rentals) │ │                      │
│         │  └──────────┴────────────────┘ │                      │
│         │  ┌─────────────────────────┐   │                      │
│         │  │       LargeSpot         │   │                      │
│         │  │  (Trucks/RentalTrucks)  │   │                      │
│         │  └─────────────────────────┘   │                      │
│         └────────────────────────────────┘                      │
└──────────────────────────────────────────────────────────────────┘
```

### Vehicle Hierarchy

```
Vehicle  (abstract, implements Comparable<Vehicle>)
├── Bike
├── Car
├── Truck
└── RentalVehicle  (also implements Rentable)
```

### Parking Spot Hierarchy

```
ParkingSpot  (abstract — Template Method pattern)
├── SmallSpot   → accepts Bike only
├── MediumSpot  → accepts Car, RentalVehicle(RENTAL_CAR)
└── LargeSpot   → accepts Truck, RentalVehicle(RENTAL_TRUCK),
                  optionally smaller vehicles (overflow mode)
```

### Design Patterns Used

| Pattern | Where |
|---|---|
| **Singleton** | `ParkingLot` — one instance per JVM |
| **Builder** | `ParkingTicket.Builder` — readable multi-field construction |
| **Factory Method** | `ParkingLotFactory` — builds pre-configured lot instances |
| **Template Method** | `ParkingSpot.park()` calls abstract `canAccept()` hook |
| **Observer** | `ParkingLot` notifies `ParkingObserver` implementations |
| **Strategy** | `ParkingRate.RateStrategy` — swappable rate-computation lambdas |

### Data Flow: Vehicle Parking

```
Vehicle arrives
      │
      ▼
EntryGate.arrive(vehicle)
      │
      ├── Lot full? → queue vehicle (LinkedList Queue)
      │
      └── ParkingLot.parkVehicle(vehicle)
                │
                ├── Check: already parked? → ParkingException
                │
                ├── Stream over all floors → flatMap spots
                │   └── filter: isAvailable() && canFit(vehicleType)
                │
                ├── ParkingSpot.park(vehicle)   [synchronized]
                │   └── canAccept(vehicle)       [abstract hook]
                │
                ├── ParkingTicket.Builder → build()
                │
                └── Notify observers → CapacityMonitor
```

### Data Flow: Vehicle Exit

```
Driver presents ticket
      │
      ▼
ExitGate.processExitCash / processExitCard
      │
      └── ParkingLot.processExit(ticketId, Payable)
                │
                ├── Validate ticket (Optional + orElseThrow)
                │
                ├── calculateFee() on ParkingTicket
                │
                ├── Payable.processWithReceipt(fee, ticketId)
                │   └── Lambda / method reference executes payment
                │
                ├── ticket.markPaid(exitTime)
                │
                ├── releaseSpotForVehicle()   [stream + Optional]
                │
                └── Notify observers
```

---

## Class Reference

### Enums

#### `VehicleType.java`
Enum representing every vehicle category the system handles.

| Constant | Display Name | Required Spot |
|---|---|---|
| `BIKE` | Bike | SMALL |
| `CAR` | Car | MEDIUM |
| `TRUCK` | Truck | LARGE |
| `RENTAL_CAR` | Rental Car | MEDIUM |
| `RENTAL_TRUCK` | Rental Truck | LARGE |

- Fields: `displayName`, `requiredSpotType`
- Key method: `isRental()` — returns `true` for `RENTAL_CAR` and `RENTAL_TRUCK`
- **Java concepts**: enum fields/constructors/methods, `this` reference inside enum

---

#### `SpotType.java`
Enum with **per-constant abstract method implementations** (each constant has its own `canFit()` body).

| Constant | Accepts |
|---|---|
| `SMALL` | Bikes only |
| `MEDIUM` | Cars and Rental Cars |
| `LARGE` | All vehicle types |

- **Java concepts**: abstract method in enum, per-constant class bodies (anonymous-class behavior inside enum)

---

### Interfaces

#### `Rentable.java`
Contract for vehicles that can be rented out to customers.

| Method | Type | Description |
|---|---|---|
| `startRental(customerId, startTime)` | abstract | Begin a rental |
| `endRental(endTime)` | abstract | End the rental |
| `isCurrentlyRented()` | abstract | Rental state query |
| `calculateRentalCost(dailyRate)` | **default** | Override for richer calculation |
| `isOverdue(rentalStart)` | **default** | Checks against MAX_RENTAL_DAYS |
| `isValidRentalPeriod(days)` | **static** | Utility — no instance needed |

- **Java concepts**: interface default methods, static interface methods, interface constants

---

#### `Payable.java`
`@FunctionalInterface` for processing payments. Its single abstract method `processPayment(double)` lets callers pass lambdas or method references.

- Static factories: `Payable.alwaysSucceeds()`, `Payable.alwaysFails()`
- Default method: `processWithReceipt(amount, ticketId)` wraps the functional method with logging
- **Java concepts**: `@FunctionalInterface`, lambda target, default methods on functional interfaces

---

#### `ParkingObserver.java`
Observer pattern callback interface. Contains a nested `Event` enum.

| Event | Meaning |
|---|---|
| `VEHICLE_PARKED` | A vehicle just took a spot |
| `VEHICLE_EXITED` | A vehicle freed a spot |
| `LOT_FULL` | 100% occupancy |
| `LOT_AVAILABLE` | Dropped below 100% |
| `CAPACITY_WARNING` | Crossed configurable threshold |

- **Java concepts**: Observer pattern, enum nested inside an interface

---

### Abstract Classes

#### `Vehicle.java`
Root of the vehicle hierarchy. Cannot be instantiated directly.

| Member | Description |
|---|---|
| `licensePlate`, `vehicleType`, `ownerName`, `registeredAt` | Encapsulated final fields |
| `getHourlyRate()` | Abstract — each subclass defines its own rate |
| `getDescription()` | Abstract — human-readable description |
| `fitsInSpot(SpotType)` | Concrete — delegates to `SpotType.canFit()` |
| `compareTo(Vehicle)` | Orders by registration time (ascending) |
| `equals` / `hashCode` | Business-key identity: license plate |

- **Java concepts**: abstract class, encapsulation, `Comparable<T>`, `equals`/`hashCode` contract, `this`/`super`, `protected` constructor

---

#### `ParkingSpot.java`
Abstract representation of one physical parking space. Uses the **Template Method** pattern.

| Member | Description |
|---|---|
| `SpotId` (static nested class) | Immutable floor/row/number identifier, e.g. `F1-R2-S04` |
| `park(vehicle)` | Template method — calls `canAccept()` hook (synchronized) |
| `release()` | Frees the spot, returns the vehicle wrapped in `Optional` |
| `canAccept(vehicle)` | Abstract hook — subclasses define acceptance logic |
| `getParkedVehicle()` | Returns `Optional<Vehicle>` — never null |

- **Java concepts**: abstract class + Template Method, `Optional<T>`, `synchronized`, static nested class

---

### Concrete Vehicle Classes

#### `Bike.java`
Two-wheelers. Electric bikes receive a 10% parking rate discount.

- Extra field: `isElectric`
- `final` method `requiredSpotType()` — cannot be overridden by any further subclass
- **Java concepts**: `extends`, `super()`, `@Override`, `final` method, constructor delegation (`this(...)`)

---

#### `Car.java`
Standard passenger cars. SUVs incur a 25% rate premium.

- Extra fields: `category` (Standard / Compact / SUV)
- Static constants: `CATEGORY_STANDARD`, `CATEGORY_COMPACT`, `CATEGORY_SUV`
- **Java concepts**: static constants, overloaded constructors, method overriding

---

#### `Truck.java`
Heavy goods vehicles. Rate scales with cargo weight; hazardous cargo adds a 50% surcharge.

- Extra fields: `cargoWeightTonnes`, `isHazardousCargo`
- Three overloaded constructors (full, no-hazardous, empty)
- **Java concepts**: multiple overloaded constructors, input validation, `IllegalArgumentException`

---

#### `RentalVehicle.java`
Fleet vehicles owned by a rental company, implementing both `Vehicle` and `Rentable`.

- `synchronized` methods on rental start/end — thread-safe for concurrent bookings
- `volatile` fields `currentCustomerId` and `rentalStartTime` — visibility across threads
- Overrides `calculateRentalCost()` using `ChronoUnit.DAYS.between()`
- **Java concepts**: multiple interface implementation, `synchronized`, `volatile`, Java Time API (`ChronoUnit`), interface default method override

---

### Concrete Parking Spot Classes

#### `SmallSpot.java`
Accepts only `Bike` instances. Declared `final` — no further subclassing.

#### `MediumSpot.java`
Accepts `Car` and `RentalVehicle` with `RENTAL_CAR` type. Uses `instanceof` for both checks.

#### `LargeSpot.java`
Accepts `Truck` and `RENTAL_TRUCK`. Configurable **overflow mode** allows smaller vehicles when no trucks need the space.

- **Java concepts (all three)**: `instanceof`, `final` class, Template Method hook implementation

---

### Core System Classes

#### `ParkingTicket.java`
Immutable value object issued when a vehicle parks.

- Built with `ParkingTicket.Builder` (inner static class)
- Inner enum `TicketStatus`: `ACTIVE` → `PAID` / `EXPIRED`
- Fee calculation: minimum 30-minute block, rounds up to next 30-minute boundary
- UUID-based ticket IDs
- **Java concepts**: Builder pattern, inner static class, inner enum, immutable class, `UUID`, Java Time API

---

#### `ParkingFloor.java`
Manages all spots on one level of the parking structure.

| Method | Description |
|---|---|
| `addSpots(ParkingSpot...)` | **Varargs** — add one or many spots at once |
| `findAvailableSpot(Class<T>)` | **Generic method** — find a spot of a specific class |
| `findAvailableSpotForVehicle(Vehicle)` | Stream pipeline to locate a suitable free spot |
| `countAvailableSpots()` | `stream().filter().count()` |
| `availableSpotsByType()` | `Collectors.groupingBy` + `Collectors.counting()` |
| `printOccupiedSpots()` | **Iterator** — explicit `Iterator<ParkingSpot>` loop |

- **Java concepts**: Generics (`<T extends ParkingSpot>`), Streams, `Collectors`, varargs, `Iterator`, `EnumMap`, `Collections.unmodifiableList`

---

#### `ParkingRate.java`
Computes hourly rates per vehicle type with optional peak-hour surcharges.

- Inner `@FunctionalInterface` `RateStrategy` — rate computation is a swappable lambda
- Static factories: `standardPlan()` (peak surcharges) and `flatRatePlan()` (no surcharges)
- Peak hours: 08:00–10:00 and 17:00–20:00
- **Java concepts**: Strategy pattern via functional interface, `HashMap`, `LocalTime`, static factory methods, lambda assignment to functional interface

---

#### `ParkingLot.java`
Central coordinator. **Singleton** — only one instance exists per JVM.

| Responsibility | Implementation |
|---|---|
| Singleton access | Double-checked locking with `volatile` |
| Thread-safe ticket storage | `ConcurrentHashMap` |
| Finding a free spot | `Stream.flatMap` across all floors |
| Payment processing | `Payable` functional interface |
| Event broadcasting | Observer list → `notifyObservers()` |
| Statistics | `mapToInt().sum()`, `count()`, occupancy % |

- **Java concepts**: Singleton (double-checked locking), `volatile`, `ConcurrentHashMap`, `flatMap`, `Optional.orElseThrow`, method references, autoboxing

---

#### `EntryGate.java`
Physical entry point. Queues vehicles when the lot is full; drains the queue when space frees.

- `LinkedList` used as a **Queue** (FIFO)
- **Anonymous class** inline implementation of `ParkingObserver` that opens/closes the gate
- `Queue.offer()` to enqueue, `Queue.poll()` to dequeue
- **Java concepts**: `Queue`, `LinkedList`, anonymous class, checked exception handling

---

#### `ExitGate.java`
Physical exit point. Supports cash and card payment modes.

- Cash: lambda `amount -> true` passed as `Payable`
- Card: **method reference** `processor::processCardPayment` passed as `Payable`
- Static nested class `PaymentProcessor` — has no implicit reference to `ExitGate`
- **Java concepts**: lambda as functional interface argument, method reference, static nested class

---

#### `CapacityMonitor.java`
Concrete `ParkingObserver` that tracks occupancy history and alerts on thresholds.

- Records every event as a `CapacitySnapshot` (static nested class)
- Uses `Math.max` to track peak occupancy
- **Switch expression** (Java 14+) to map event → log level
- **Java concepts**: interface implementation, static nested class, enhanced for-loop, switch expression, `List.copyOf` (Java 10+)

---

#### `ParkingLotFactory.java`
Factory class that builds a fully configured `ParkingLot` in one call. Declared `final` with a private constructor to prevent instantiation.

- Creates 2 floors with a mix of Small, Medium, and Large spots
- Attaches a `CapacityMonitor` to the lot
- **Java concepts**: Factory Method pattern, `final` class, utility class idiom

---

#### `ParkingSystemMain.java`
Integration entry point. Runs a complete scenario covering all 30 Java concepts listed in the file header.

Scenarios demonstrated:
1. Lot built via Factory
2. 9 vehicles (bikes, cars, trucks, rentals) parked through two entry gates
3. Rental started on a fleet vehicle
4. Exits processed via cash and card
5. Invalid ticket error path
6. Duplicate-park error path
7. Revenue report grouped by vehicle type (Streams + Collectors)
8. Top-fees report using `PriorityQueue` with a custom `Comparator`
9. Vehicles sorted by registration time (natural ordering via `Comparable`)
10. Occupied spots printed via `Iterator`
11. Static interface method demo (`Rentable.isValidRentalPeriod`)
12. Enum iteration with `VehicleType.values()`

---

## Java Concepts Index

| # | Concept | Primary File(s) |
|---|---|---|
| 1 | Enums with fields & methods | `VehicleType.java` |
| 2 | Enum with abstract methods | `SpotType.java` |
| 3 | Abstract class | `Vehicle.java`, `ParkingSpot.java` |
| 4 | Interfaces | `Rentable.java`, `Payable.java`, `ParkingObserver.java` |
| 5 | Default interface methods | `Rentable.java`, `Payable.java` |
| 6 | Static interface methods | `Rentable.java`, `Payable.java` |
| 7 | Functional interface (`@FunctionalInterface`) | `Payable.java`, `ParkingRate.RateStrategy` |
| 8 | Inheritance (`extends`) | `Bike`, `Car`, `Truck`, `RentalVehicle` |
| 9 | Polymorphism | `ParkingSystemMain.java` — `List<Vehicle>` |
| 10 | Encapsulation | `Vehicle.java` (private fields + getters) |
| 11 | `super()` constructor call | All concrete vehicle classes |
| 12 | `final` class | `SmallSpot`, `MediumSpot`, `LargeSpot`, `ParkingLotFactory` |
| 13 | `final` method | `Bike.requiredSpotType()` |
| 14 | `instanceof` | `SmallSpot`, `MediumSpot`, `LargeSpot` |
| 15 | Generics (generic method) | `ParkingFloor.findAvailableSpot(Class<T>)` |
| 16 | Generics (bounded type) | `<T extends ParkingSpot>` |
| 17 | `Comparable<T>` | `Vehicle.compareTo()` |
| 18 | `Comparator` | `ParkingSystemMain` — PriorityQueue |
| 19 | Collections — `List`, `ArrayList` | `ParkingFloor`, `ParkingLot` |
| 20 | Collections — `Map`, `HashMap`, `EnumMap` | `ParkingFloor`, `ParkingRate` |
| 21 | Collections — `Queue` / `LinkedList` | `EntryGate.java` |
| 22 | Collections — `PriorityQueue` | `ParkingSystemMain.java` |
| 23 | Collections — `ConcurrentHashMap` | `ParkingLot.java` |
| 24 | Streams — `filter`, `map`, `flatMap`, `count`, `findFirst` | `ParkingLot`, `ParkingFloor` |
| 25 | Streams — `Collectors.groupingBy`, `summingDouble` | `ParkingFloor`, `ParkingSystemMain` |
| 26 | `Optional<T>` | `ParkingSpot`, `ParkingLot`, exit flow |
| 27 | Lambda expressions | `EntryGate`, `ExitGate`, `ParkingRate`, `ParkingLot` |
| 28 | Method references | `ParkingLot`, `ExitGate`, `ParkingSystemMain` |
| 29 | Anonymous class | `EntryGate` — inline `ParkingObserver` |
| 30 | Static nested class | `ParkingTicket.Builder`, `ParkingSpot.SpotId`, `ExitGate.PaymentProcessor` |
| 31 | Inner enum | `ParkingTicket.TicketStatus`, `ParkingObserver.Event` |
| 32 | Singleton pattern | `ParkingLot.getInstance()` |
| 33 | Builder pattern | `ParkingTicket.Builder` |
| 34 | Observer pattern | `ParkingLot` → `ParkingObserver` → `CapacityMonitor` |
| 35 | Template Method pattern | `ParkingSpot.park()` + `canAccept()` hook |
| 36 | Strategy pattern | `ParkingRate.RateStrategy` functional interface |
| 37 | Factory Method pattern | `ParkingLotFactory`, `ParkingException` static factories |
| 38 | `synchronized` methods | `ParkingSpot.park/release`, `RentalVehicle.startRental` |
| 39 | `volatile` keyword | `ParkingLot.instance`, `RentalVehicle` rental fields |
| 40 | Custom exceptions | `ParkingException`, `ParkingSystemException` |
| 41 | `try-catch-finally` | `ParkingLot.processExit()`, `EntryGate.admitVehicle()` |
| 42 | Varargs | `ParkingFloor.addSpots(ParkingSpot...)` |
| 43 | Autoboxing / Unboxing | `ParkingLot.getOverallOccupancyPercentage()` |
| 44 | Java Time API (`LocalDateTime`, `LocalTime`, `ChronoUnit`) | `RentalVehicle`, `ParkingRate`, `ParkingTicket` |
| 45 | `UUID` | `ParkingTicket.Builder` |
| 46 | Switch expression (Java 14+) | `CapacityMonitor.onParkingEvent()` |
| 47 | `Iterator` | `ParkingFloor.printOccupiedSpots()` |
| 48 | `Collections.unmodifiableList` | `ParkingFloor.getAllSpots()` |
| 49 | `Objects.requireNonNull`, `Objects.hash` | `ParkingLot`, `Vehicle` |
| 50 | `Math.max`, `Math.ceil` | `ParkingTicket`, `CapacityMonitor` |

---

## How to Run

### Prerequisites

- **Java 17+** (switch expressions require Java 14+; `List.copyOf` requires Java 10+)
- No external dependencies — pure Java standard library

Verify your Java version:
```bash
java -version
```

### Compile

From the **parent directory** of `JavaConcepts/` (i.e., `InterviewPrep/`):

```bash
# Compile all files, output class files into an 'out/' directory
javac -d out JavaConcepts/*.java
```

### Run

```bash
java -cp out JavaConcepts.ParkingSystemMain
```

### One-liner (compile + run)

```bash
javac -d out JavaConcepts/*.java && java -cp out JavaConcepts.ParkingSystemMain
```

### Expected Output (abridged)

```
========================================
  CITY PARKING SYSTEM — DEMO
========================================

  PARKING LOT STATUS: CITY-LOT-01
  Floor 1 [15 total spots, 15 available, 0% full]
  Floor 2 [9 total spots, 9 available, 0% full]
  Total  : 24 spots

--- Parking vehicles ---
[LOT] MH12-EB-0001 parked at spot F1-R1-S01 | Ticket: XXXXXXXX
[LOT] MH02-CS-5678 parked at spot F1-R2-S02 | Ticket: XXXXXXXX
...

--- Processing exits ---
[PAYMENT] Processing $0.75 for ticket XXXXXXXX
[PAYMENT] SUCCESS — ticket XXXXXXXX
...

--- Revenue by vehicle type (paid tickets) ---
  Bike             $0.75
  Car              $1.50
  Truck            $3.00

--- VehicleType enum capabilities ---
  Bike            | Requires: Small    | Rental: false
  Car             | Requires: Medium   | Rental: false
  Truck           | Requires: Large    | Rental: false
  Rental Car      | Requires: Medium   | Rental: true
  Rental Truck    | Requires: Large    | Rental: true

========================================
  DEMO COMPLETE
========================================
```

### Experimenting with the Code

| Goal | Change |
|---|---|
| Try flat-rate pricing | In `ParkingLotFactory`, replace `standardPlan()` with `ParkingRate.flatRatePlan()` |
| Fill the lot to trigger `LOT_FULL` | Add more vehicles in `ParkingSystemMain` until all 24 spots are occupied |
| Test overflow mode | Set `allowOverflow = true` on a `LargeSpot`; park a `Car` in it |
| Add a payment failure | Use `Payable.alwaysFails()` in `processExit()` |
| Register your own observer | Implement `ParkingObserver` and call `lot.addObserver(yourObserver)` |

---

## File Summary

```
JavaConcepts/
├── VehicleType.java          # Enum — vehicle categories
├── SpotType.java             # Enum — spot size categories with abstract canFit()
├── ParkingException.java     # Custom checked + unchecked exceptions
├── Rentable.java             # Interface — rental contract (abstract + default + static)
├── Payable.java              # @FunctionalInterface — payment processing
├── ParkingObserver.java      # Observer interface with nested Event enum
├── Vehicle.java              # Abstract base class for all vehicles
├── Bike.java                 # Concrete vehicle — two-wheelers
├── Car.java                  # Concrete vehicle — passenger cars
├── Truck.java                # Concrete vehicle — heavy goods vehicles
├── RentalVehicle.java        # Concrete vehicle — implements Rentable
├── ParkingSpot.java          # Abstract spot — Template Method, Optional, SpotId
├── SmallSpot.java            # Concrete spot — bikes only
├── MediumSpot.java           # Concrete spot — cars and rental cars
├── LargeSpot.java            # Concrete spot — trucks, with overflow mode
├── ParkingTicket.java        # Immutable ticket — Builder pattern, inner enum
├── ParkingFloor.java         # Floor management — Generics, Streams, Iterator
├── ParkingRate.java          # Rate calculation — Strategy, HashMap, LocalTime
├── ParkingLot.java           # Central coordinator — Singleton, ConcurrentHashMap
├── EntryGate.java            # Entry management — Queue, anonymous Observer
├── ExitGate.java             # Exit management — lambdas, method refs, nested class
├── CapacityMonitor.java      # Observer impl — history, switch expression
├── ParkingLotFactory.java    # Factory — builds configured ParkingLot
└── ParkingSystemMain.java    # Entry point — runs the full demo
```

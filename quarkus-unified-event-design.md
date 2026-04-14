# Unified Event Bus — Design Document

> **Status:** Draft / Exploration
> **Branch:** `unifiedEventing`
> **Related:** [GH Discussion #53202](https://github.com/quarkusio/quarkus/discussions/53202), [Issue #50583](https://github.com/quarkusio/quarkus/issues/50583), [Vert.x 5 Epic #51959](https://github.com/quarkusio/quarkus/issues/51959)
> **Points of contact:** @mkouba, @ozangunalp, @cescoffier

---

## 1. Goal

Design a new Quarkus-native event API that:
- Uses **type-safe routing** (the event type is the address)
- Supports **publish/subscribe**, **point-to-point**, and **request-response** patterns
- Automatically **propagates context** (security, tracing, MDC — but *not* the CDI request context)
- Works **without Vert.x HTTP** (Vert.x core may be required)
- Runs on top of the **existing Vert.x event bus** as its transport layer

---

## 2. Type-to-Address Mapping

### 2.1 Address Scheme

Vert.x addresses are **opaque consumer identifiers**, not type-derived. Each consumer gets a unique address:

| Consumer kind | Address pattern |
|---|---|
| Declarative (`@OnEvent`) | `__qx_event__/consumer/<sequential-id>` |
| Programmatic (`consumer()`) | `__qx_event__/programmatic/<sequential-id>` |

Type-based routing is handled by the `EventDispatcher`, not by Vert.x address matching. The `__qx_event__/` prefix prevents collisions with user-chosen Vert.x addresses.

### 2.3 Address Resolution at Send Time

Vert.x addresses are opaque consumer identifiers (`__qx_event__/consumer/0`, `__qx_event__/programmatic/1`). Type-based routing is handled by the `EventDispatcher` using CDI's `BeanContainer.isMatchingEvent()`.

The event type used for matching comes from the `QuarkusEvent<T>` injection point (preserving parameterized type info), not from `event.getClass()` — this is what enables `Envelope<Order>` vs `Envelope<Payment>` discrimination despite type erasure.

---

## 3. Subtype Matching

### 3.1 CDI's Approach (Reference)

CDI resolves observers at fire time by:
1. Computing the **type closure** of the fired event's runtime type via `HierarchyDiscovery.getTypeClosure()` — all supertypes, interfaces, and parameterized types
2. Checking each observer's observed type against this closure using `EventTypeAssignabilityRules`
3. Observers whose observed type appears in the closure receive the event

Key code in ArC (see `ArcContainerImpl.resolveObserverMethods()`):
```java
Set<Type> eventTypes = new HierarchyDiscovery(eventType).getTypeClosure();
for (InjectableObserverMethod<?> observer : observers) {
    if (EventTypeAssignabilityRules.instance().matches(observer.getObservedType(), eventTypes)) {
        // observer matches
    }
}
```

### 3.2 Implemented Approach: Central Dispatcher (Send-Time Type Resolution)

~~The initial design proposed consumer-side fan-out (registering each consumer on multiple Vert.x addresses). This was replaced by a central dispatcher — see §15 for the rationale.~~

Each consumer registers on a **single unique Vert.x address** (e.g., `__qx_event__/consumer/0`). A central `EventDispatcher` maintains a registry of consumers keyed by their observed type + qualifiers.

At **send time**, the dispatcher:
1. Iterates all registered consumers and calls `BeanContainer.isMatchingEvent()` for type + qualifier matching (results are cached)
2. For `request()`, additionally filters by response type assignability — only consumers whose return type is assignable to the requested type are candidates
3. Applies delivery semantics: publish → all matching, send → round-robin one, request → round-robin one (response-type-filtered) with reply

#### Example

```java
interface DomainEvent {}
class OrderCreated implements DomainEvent {}
class OrderCancelled implements DomainEvent {}

void auditAll(@OnEvent DomainEvent e) { ... }      // consumer A
void onOrder(@OnEvent OrderCreated e) { ... }       // consumer B
```

Dispatcher registry:
| Consumer | Observed type | Vert.x address |
|---|---|---|
| A | `DomainEvent` | `__qx_event__/consumer/0` |
| B | `OrderCreated` | `__qx_event__/consumer/1` |

Sending `new OrderCreated()`:
- Type closure: `{OrderCreated, DomainEvent}`
- Matching consumers: A (observes `DomainEvent`) + B (observes `OrderCreated`)
- **Publish:** Both A and B receive it ✓
- **Send (P2P):** Round-robin between A and B ✓
- **Request:** One of A or B replies ✓

Sending `new OrderCancelled()`:
- Type closure: `{OrderCancelled, DomainEvent}`
- Matching consumers: A only ✓

### 3.3 Advantages of This Approach

- **Full subtype matching for both declarative and programmatic consumers** — uses CDI's `isMatchingEvent()` which handles class hierarchy, parameterized types, and qualifier member values
- **No fan-out explosion** — each consumer registers exactly once, regardless of how many subtypes exist
- **Vert.x still handles async delivery** — the dispatcher routes to the right address, Vert.x delivers the message on the appropriate event loop context
- **Resolution caching** — matching results are cached and invalidated on consumer registration/unregistration

### 3.4 Limitations

| Limitation | Mitigation |
|---|---|
| Consumer of `Object` would match every event | Build-time validation: **error** if someone observes `Object` or `java.io.Serializable` directly |
| Round-robin implementation is simplified | See note in `EventDispatcher` — a production implementation should use an immutable cyclic sequence like Vert.x does |

---

## 4. Parameterized Type Handling

### 4.1 Implementation

Parameterized type matching is fully implemented via CDI's `BeanContainer.isMatchingEvent()`:

```java
void onOrderEnvelope(@OnEvent Envelope<Order> e) { ... }
void onStringEnvelope(@OnEvent Envelope<String> e) { ... }

quarkusEvent.publish(new Envelope<>(new Order()));  // only reaches onOrderEnvelope
```

**How it works:**
1. At build time, the full Jandex `Type` (including type arguments) is captured from the `@OnEvent` parameter
2. A Gizmo2-generated metadata class uses `RuntimeTypeCreator` to produce `java.lang.reflect.ParameterizedType` objects at runtime
3. On the sender side, `QuarkusEvent<Envelope<Order>>` captures the full parameterized type from the injection point
4. At send time, `isMatchingEvent` compares the parameterized types correctly

### 4.2 Matching Rules

CDI's matching rules apply (via `isMatchingEvent`):

- `Envelope<Order>` matches consumer `Envelope<Order>` ✓ (exact match)
- `Envelope<Order>` matches consumer `Envelope<? extends DomainEvent>` ✓ (wildcard bound)
- `Envelope<Order>` does **not** match consumer `Envelope<String>` ✗
- `Envelope<Order>` matches consumer `Envelope` (raw type) ✓

---

## 5. Delivery Semantics

### 5.1 Delivery Modes

Modeled after the Vert.x event bus, the delivery mode is sender-side — consumers are mode-agnostic.

```java
@Inject QuarkusEvent<OrderCreated> events;

events.publish(new OrderCreated(...));                // fire-and-forget, all consumers
events.send(new OrderCreated(...));                   // fire-and-forget, one consumer (round-robin)
Uni<Confirmation> r = events.request(new OrderCreated(...), Confirmation.class);  // one consumer replies
```

A consumer declares that it can handle a given type. If the sender used `request()`, the consumer's return value becomes the reply; for `send()` and `publish()` (fire-and-forget), the return value is discarded.

### 5.2 Consumer Declaration

```java
@ApplicationScoped
public class OrderHandlers {

    void onOrderCreated(@OnEvent OrderCreated event) {
        // fire-and-forget consumer (works with publish, send, or request)
    }

    Uni<OrderConfirmation> processOrder(@OnEvent OrderCreated event) {
        // reply-capable consumer (return value becomes the reply for request())
        // for publish/send, return value is ignored
        return Uni.createFrom().item(new OrderConfirmation(...));
    }
}
```

### 5.3 Point-to-Point with Subtype Consumers

When `send(new Dog())` is called, the dispatcher computes the type closure `{Dog, Animal}` and finds both the `Dog` consumer and the `Animal` consumer. It round-robins between them.

**Is this correct?** Yes — the `Animal` consumer declared it can handle any `Animal`, a `Dog` is an `Animal` (Liskov substitution), so it should participate in point-to-point delivery. If a consumer only wants `Dog` and not other animals, it should observe `Dog` specifically.

### 5.4 Request-Response Ambiguity

If multiple consumers are registered for the same event type and a `request()` is sent, only one consumer replies (round-robin). This may produce inconsistent reply types or behavior.

**Options:**
1. **Build-time warning** when multiple reply-capable consumers exist for the same type (including via subtype registration)
2. **Priority annotation** (`@Priority`) to deterministically select the "primary" responder
3. **Documentation** — make it clear that request-response works best with a single consumer per type

**Recommendation:** Build-time warning (option 1) + priority support (option 2).

### 5.5 Qualifier-Based Routing

In the current Vert.x model, string-based addresses serve as an implicit qualifier mechanism — the same payload type can be sent to different addresses to reach different consumers. The new API replaces this with type-safe qualifiers.

**Consumer side** (qualifiers are placed on the parameter, not the method, to avoid ArC misinterpreting the method as a CDI producer):
```java
void handlePremiumOrder(@OnEvent @Premium Order order) { ... }

void handleAnyOrder(@OnEvent Order order) { ... }
```

**Sender side:**
```java
@Inject QuarkusEvent<Order> orderEvent;

orderEvent.select(new PremiumLiteral()).send(new Order(...));  // only @Premium consumer
orderEvent.send(new Order(...));                               // only unqualified consumer
```

**Dispatcher registry key:** The dispatcher keys consumers by observed type + qualifier names. Qualifier names are sorted lexicographically to ensure deterministic matching regardless of declaration order.

**Matching rules** (following CDI conventions, via `BeanContainer.isMatchingEvent()`):
- A consumer with no qualifiers matches ALL events of matching type (CDI catch-all behavior)
- A consumer with qualifiers only matches events sent with a superset of those qualifiers
- Qualifier member values are compared (except `@Nonbinding` members)
- Subtype matching applies independently of qualifiers — a `@Premium DomainEvent` consumer receives `@Premium Order` events if `Order extends DomainEvent`

---

## 6. Programmatic Consumer Registration

### 6.1 API

```java
@Inject QuarkusEvent<Animal> animalEvent;

// Fire-and-forget consumer — receives Animal and all subtypes (Dog, Cat, etc.)
EventConsumerRegistration reg = animalEvent.consumer(Animal.class, animal -> {
    // handle animal
});

// Sync request-reply consumer (implies blocking execution)
EventConsumerRegistration reg2 = animalEvent.replyingConsumer(Animal.class,
    animal -> "processed:" + animal.name(), String.class);

// Async request-reply consumer (implies non-blocking execution)
EventConsumerRegistration reg3 = animalEvent.replyingConsumerAsync(Animal.class,
    animal -> Uni.createFrom().item("async:" + animal.name()), String.class);

// Unregister
reg.unregister();
```

The `Class<T>` parameter is required because Java type erasure prevents determining the event type from the generic parameter at runtime. The `Class<R>` response type parameter enables response type filtering in `request()`.

### 6.2 Subtype Matching for Programmatic Consumers

With the central dispatcher approach, programmatic consumers get **full subtype matching** — the same behavior as declarative `@OnEvent` consumers. The dispatcher resolves matching consumers at send time by walking up the event's type hierarchy, so a consumer registered for `Animal` will receive `Dog` events.

This works because `isMatchingEvent()` handles type hierarchy matching — the dispatcher doesn't need to walk the class hierarchy manually. No classpath scanning or build-time index is needed.

---

## 7. Context Propagation

### 7.1 What to Propagate

Per @mkouba's feedback on the discussion:

| Context | Propagated? | Rationale |
|---|---|---|
| Security (identity, roles) | ✅ Yes | Consumer should see who triggered the event |
| Tracing (OpenTelemetry spans) | ✅ Yes | Events should appear in the same trace |
| MDC (logging context) | ✅ Yes | Log correlation across event boundaries |
| CDI Request Context | ❌ No | Event bus is used to *escape* context boundaries; a new request context is created per delivery |
| CDI Application Context | ✅ Yes (always active) | Not really "propagated" — it's just always there |

### 7.2 Implementation Strategy

The current `EventConsumerInvoker` already manages request context activation (lines 36-72). For the new API:

1. **Capture** security identity, OTel span context, and MDC at send time
2. **Store** in a custom Vert.x message header or local context
3. **Restore** in the consumer invoker before calling the handler
4. **Activate** a new, empty request context for the consumer (same as today)

Quarkus already has infrastructure for this in `SmallRyeContextPropagation` and the Mutiny context propagation integration. The `VertxCurrentContextFactory` (`VertxEventBusConsumerRecorder:81`) provides the hook for attaching context to the Vert.x duplicated context.

### 7.3 Blocking / Virtual Thread Support

> **TODO:** The current `@OnEvent(blocking = true)` should be replaced with return type analysis
> and `@Blocking`/`@NonBlocking` annotations, consistent with other Quarkus extensions.
> Also add `@RunOnVirtualThread` support. The `ordered` flag has been removed as it was
> a Vert.x EventBus implementation detail.

```java
void handleBlocking(@OnEvent OrderCreated e) { ... }  // void → inferred as blocking

Uni<Void> handleNonBlocking(@OnEvent OrderCreated e) { ... }  // Uni → inferred as non-blocking

@RunOnVirtualThread
void handleVirtualThread(@OnEvent OrderCreated e) { ... }  // explicit virtual thread
```

---

## 8. Build-Time Processor Design

### 8.1 Overview

The `EventsProcessor` (deployment module) performs:

1. **Scan** — iterate over CDI beans, find methods with a parameter annotated `@OnEvent` via `AnnotationStore`
2. **Extract** — observed type from the `@OnEvent`-annotated parameter (any position), qualifier annotations from the same parameter
3. **Validate** — error if observed type is `Object`, `Serializable`, or similar overly broad types; error if method has wrong parameter count
4. **Generate invokers** — create ArC invokers with `withArgumentLookup()` for CDI-injected parameters and return value transformer for `Uni` → `CompletionStage`
5. **Generate metadata class** — a single Gizmo2 class containing `Type`, `Set<Annotation>`, and response `Type` for all consumers via `RuntimeTypeCreator` and `AnnotationLiteralProcessor`
6. **Produce build items** — `EventConsumerBuildItem` records consumed by the runtime recorder
7. **Register synthetic bean** — `QuarkusEvent<T>` as a `@Dependent` synthetic bean with all discovered qualifiers

At runtime, the recorder creates the `EventDispatcher` (with `BeanContainer` for `isMatchingEvent`), instantiates the generated metadata class, registers each consumer in the dispatcher (keyed by `Type` + `Set<Annotation>` + response `Type`), and sets up Vert.x consumers on unique addresses. The `EventEnvelope` codec is registered at init time.

### 8.3 Relationship with Existing VertxProcessor

The new processor should be **separate from** `VertxProcessor` to maintain clean separation. It can reuse:
- `EventBusCodecProcessor` for codec registration
- `VertxEventBusConsumerRecorder` patterns for runtime consumer registration
- `EventConsumerInvoker` pattern for the invocation wrapper

---

## 9. Extension Integration

### 9.1 Build Item for Extensions

Extensions that define new event types should be able to participate in the fan-out:

```java
// Extension's processor
@BuildStep
void registerEventTypes(BuildProducer<UnifiedEventTypeBuildItem> eventTypes) {
    eventTypes.produce(new UnifiedEventTypeBuildItem(MyExtensionEvent.class));
}
```

This ensures that if a user observes a supertype of `MyExtensionEvent`, the fan-out includes it.

### 9.2 Extension-Provided Consumers

Extensions can also provide consumers via build items, allowing framework-level integrations without requiring user code.

---

## 10. Migration from `@ConsumeEvent`

### 10.1 Annotation Mapping

| `@ConsumeEvent` | `@OnEvent` equivalent |
|---|---|
| `@ConsumeEvent("address")` | `@OnEvent` (address derived from type) |
| `@ConsumeEvent(blocking = true)` | `@OnEvent(blocking = true)` (TODO: replace with `@Blocking`) |
| `@ConsumeEvent(ordered = true)` | No equivalent — removed |
| `@ConsumeEvent(codec = MyCodec.class)` | `@OnEvent(codec = MyCodec.class)` or auto-codec |
| `@ConsumeEvent(local = false)` | Out of scope (no clustering) |

### 10.2 API Migration

| Old | New |
|---|---|
| `eventBus.send("address", payload)` | `quarkusEvent.send(typedEvent)` |
| `eventBus.publish("address", payload)` | `quarkusEvent.publish(typedEvent)` |
| `eventBus.request("address", payload)` | `quarkusEvent.request(typedEvent, ReplyType.class)` |
| `eventBus.consumer("address")` | `quarkusEvent.consumer(EventType.class, handler)` |

### 10.3 Coexistence Period

Both `@ConsumeEvent` and `@OnEvent` should work simultaneously during the migration period. They use different address namespaces (`@ConsumeEvent` uses raw string addresses, `@OnEvent` uses `__qx_event__/` prefixed addresses), so there's no interference.

---

## 11. API Surface (Draft)

### 11.1 Core API — `QuarkusEvent<T>`

Modeled after CDI's `Event<T>`, the producer-side API is an injectable, type-safe event emitter. This is a deliberate parallel to CDI events — developers familiar with `@Inject Event<T>` will find `@Inject QuarkusEvent<T>` natural.

```java
/**
 * Type-safe event emitter for in-process messaging.
 * Supports publish/subscribe, point-to-point, and request-response patterns.
 */
public interface QuarkusEvent<T> {

    /** Publish to all matching consumers. Fire-and-forget. */
    void publish(T event);

    /** Send to one matching consumer (round-robin). Fire-and-forget. */
    void send(T event);

    /** Send and wait for a reply. The Class<R> parameter is needed for generic type inference. */
    <R> Uni<R> request(T event, Class<R> replyType);

    /** Narrow the event type (returns a new QuarkusEvent for the subtype). */
    <U extends T> QuarkusEvent<U> select(Class<U> subtype, Annotation... qualifiers);

    /** Narrow by qualifiers only (same event type). */
    QuarkusEvent<T> select(Annotation... qualifiers);
}
```

Mirroring the Vert.x model, `send()` and `publish()` are fire-and-forget (void) — the message is dispatched to the event bus and there is nothing to wait for. Only `request()` returns `Uni<R>` because a reply is expected.

**Usage patterns:**

```java
// Typed injection — fire a specific event type
@Inject QuarkusEvent<OrderCreated> orderEvent;

void placeOrder(Order order) {
    orderEvent.publish(new OrderCreated(order));
}

// Qualifiers at injection point — pre-selects routing, no need for select() calls
@Inject @Premium QuarkusEvent<Order> premiumOrderEvent;

void handlePremiumOrder(Order order) {
    premiumOrderEvent.send(order);  // routes to @OnEvent @Premium consumers
}

// Broad injection with select() — fire many different event types from one class
@Inject QuarkusEvent<Object> events;

void processOrder(Order order) {
    events.select(OrderCreated.class).publish(new OrderCreated(order));
    events.select(AuditEvent.class).send(new AuditEvent("order placed"));
}

// Programmatic qualifier narrowing via select()
@Inject QuarkusEvent<Order> orderEvent;

void handleOrder(Order order, boolean isPremium) {
    if (isPremium) {
        orderEvent.select(new PremiumLiteral()).send(order);
    } else {
        orderEvent.send(order);
    }
}

// Request-response
@Inject QuarkusEvent<OrderCreated> orderEvent;

Uni<OrderConfirmation> confirm(Order order) {
    return orderEvent.request(new OrderCreated(order), OrderConfirmation.class);
}
```

### 11.2 Programmatic Consumer Registration

For dynamic consumers not known at build time:

```java
@Inject QuarkusEvent<Order> orderEvent;

// Fire-and-forget consumer with full subtype matching (see §6)
EventConsumerRegistration reg = orderEvent.consumer(Order.class, order -> {
    // handle order
});

// Sync request-reply consumer
EventConsumerRegistration reg2 = orderEvent.replyingConsumer(Order.class,
    order -> new Confirmation(order.id()), Confirmation.class);

// Async request-reply consumer
EventConsumerRegistration reg3 = orderEvent.replyingConsumerAsync(Order.class,
    order -> Uni.createFrom().item(new Confirmation(order.id())), Confirmation.class);

// Unregister when done
reg.unregister();
```

```java
public interface EventConsumerRegistration {
    Uni<Void> unregister();
}
```

### 11.3 Consumer Annotation

```java
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface OnEvent {

    /** If true, run on a worker thread. */
    boolean blocking() default false;
    // TODO: replace with @Blocking/@NonBlocking + return type inference
}
```

The annotation is placed on the event parameter, identifying both the event type and the consumer method. **No `value()` / address field** — the address is always derived from the annotated parameter's type. The name `@OnEvent` is a working name subject to discussion.

---

## 12. Identified Problems & Open Questions

### 12.1 Problems

| # | Problem | Severity | Proposed Solution |
|---|---|---|---|
| P1 | **Observing `Object`** would register on every event address, creating a performance disaster | High | Build-time error; require specific type (see §12.2.5) |
| P2 | **Parameterized types not visible to Jandex** — if `Envelope<Order>` is never used as a concrete type in a field/parameter, Jandex can't discover it | Medium | Require event types to be Jandex-discoverable; provide `@EventType` escape hatch |
| P3 | **Multiple reply-capable consumers** for the same type produce ambiguous request-response behavior | Medium | Build-time warning + `@Priority` ordering |
| P4 | ~~**Programmatic consumers can't do subtype matching**~~ | ~~Low~~ | **RESOLVED** — central dispatcher with `isMatchingEvent` handles subtype matching for all consumers |
| P5 | **Interface-based events with multiple implementations** create many registrations (N interfaces × M impls) | Low | Monitor at build time; warn if >100 registrations per consumer |
| P6 | **Sealed class hierarchies** — Java sealed classes restrict subtypes, could be leveraged to limit fan-out | Low (Nice-to-have) | Detect `sealed` modifier in Jandex; only fan-out to permitted subtypes |

### 12.2 Open Questions

1. ~~**Extension module structure**~~ **DECIDED:** New standalone extension named `quarkus-events`. Depends on `quarkus-vertx-core` (not `quarkus-vertx-http`), keeping the API usable in non-HTTP scenarios like CLI applications.

2. ~~**Delivery mode: sender-side or consumer-side?**~~ **DECIDED:** Sender-side only, matching the current Vert.x model. Consumers are delivery-mode-agnostic. See §5.1.

3. ~~**Qualifier-like filtering**~~ **DECIDED:** Yes, qualifiers are supported. In the current Vert.x model, the same event type can be sent over different string-based addresses, effectively acting as a qualifier mechanism. The new API should provide a type-safe equivalent. Qualifiers act as additional routing discriminators appended to the address (e.g., `__qx_event__/com.example.Order#Premium`). See §5.5 for details.

3. **Delivery ordering within subtype hierarchy** — When both `Dog` and `Animal` consumers receive a `Dog` event, does order matter? Should "more specific" consumers fire first? CDI doesn't guarantee order except via `@Priority`.

4. ~~**Error handling semantics**~~ **DECIDED:** Follow the Vert.x model — each consumer is independent. A failure in one consumer does not stop delivery to others. All events are async; there is no synchronous observer chain to interrupt.

5. ~~**Observing overly broad types (`Object`, `Serializable`)**~~ **DECIDED:** Build-time error. A consumer of `Object` would be registered on every event address in the application, causing unbounded fan-out and performance degradation. The cost is not obvious to the user and grows silently as event types are added. Disallowing it keeps the fan-out set bounded and predictable. Users who need cross-cutting behavior (e.g., audit logging) should use a specific marker interface like `AuditableEvent` rather than `Object`.

6. **Transaction observer equivalents** — CDI supports `@Observes(during = TransactionPhase.AFTER_SUCCESS)`. Should the new API support transaction-aware delivery? This is complex and may be out of scope for MVP.

6. **Dead letter / undelivered events** — What happens when `send()` targets a type with no consumers? Vert.x sends back a `NO_HANDLERS` error. Should we surface this differently?

7. **Testing support** — Should we provide a `MockUnifiedEventBus` or integration with ArC's `EventMock` facility for testing? Similar to how ArC has `MockableEventImpl`.

---

## 13. Relationship with Existing Mechanisms

| Mechanism | Role After Migration |
|---|---|
| **CDI Events** (`@Observes`) | Still available for CDI spec interop; not deprecated |
| **Vert.x Event Bus** (`@ConsumeEvent`) | Deprecated in favor of `@OnEvent`; migration guide provided |
| **Reactive Messaging** (in-memory channels) | Remains for stream processing / external messaging; clearly distinct from event bus |
| **Unified Event Bus** (`@OnEvent`) | Primary API for in-process decoupled component interaction |

---

## 14. Implementation Details from Codebase Research

The following details were gathered from deep research into the existing Quarkus codebase and inform how the implementation should be structured.

### 14.1 Build-Time Processor: Following VertxProcessor Patterns

The existing `VertxProcessor.collectEventConsumers()` provides the template:

1. **Bean-centric discovery** — it works with already-discovered beans from ArC via `BeanRegistrationPhaseBuildItem`, not raw Jandex scanning. The new processor should do the same.
2. **Invoker generation** — ArC's `InvokerFactory` creates type-safe invokers at build time with parameter/return value transformers (e.g., unwrapping `Message<T>` → `T`, `Uni` → `CompletionStage`). The new API should reuse this.
3. **Auto-scope** — `VertxProcessor.autoAddScope()` adds `@Singleton` to unannotated beans with `@ConsumeEvent`. The new processor should add the same for `@OnEvent`.
4. **Annotation store** — always use `AnnotationStore` (not raw Jandex annotations) to respect build-time annotation transformers.

### 14.2 Runtime Registration: Following VertxEventBusConsumerRecorder Patterns

The recorder (`VertxEventBusConsumerRecorder.registerMessageConsumers()`) reveals important patterns:

- **Per-consumer event loop context** — each consumer gets its own `ContextInternal` via `vi.createEventLoopContext()` to avoid serialization of published messages. The new API must replicate this.
- **Context safety marking** — `setCurrentContextSafe(true)` marks the duplicated context as safe. Required for proper ArC integration.
- **Blocking/virtual thread dispatch** — the recorder already handles `executeBlocking()` with ordering and `VirtualThreadsRecorder` dispatch. Reuse directly.
- **Codec registration** — `registerCodecs()` handles both explicit codecs and the `LocalEventBusCodec` with a `codecSelector` function for interface/abstract types. The new API's subtype fan-out may simplify codec selection since we know concrete types at build time.

### 14.3 Parameterized Type Handling: Leveraging ArC's Infrastructure

ArC's processor already has the infrastructure we need:

- **`ObserverInfo` type resolution** — resolves type variables in observed types at build time using `Types.resolvedTypeVariables()` and `Types.resolveTypeParam()`. We need identical logic for `@OnEvent` parameter types.
- **`BeanResolverImpl.matchTypeArguments()`** — implements CDI's parameterized type matching rules (actual types, wildcards, type variables). This is the build-time equivalent of `EventTypeAssignabilityRules` and can be reused/adapted.
- **`JandexTypeSystem`** — provides type variable substitution for walking parameterized type hierarchies. Essential for computing fan-out sets when generics are involved.
- **Key difference from runtime** — Jandex uses `org.jboss.jandex.Type` (with kinds like `PARAMETERIZED_TYPE`, `TYPE_VARIABLE`, `WILDCARD_TYPE`), not `java.lang.reflect.Type`. All type matching logic must use Jandex's type model.

### 14.4 Context Propagation: Existing Infrastructure

Quarkus already has context propagation infrastructure:

- **`ArcContextProvider`** implements `ThreadContextProvider` — captures/restores CDI request context state. We need to capture security and tracing contexts similarly but explicitly NOT propagate the request context (create a new one per delivery instead).
- **Mutiny integration** — `Infrastructure.getDefaultExecutor()` is context-aware. When consumers return `Uni`, context propagation through the Mutiny pipeline is automatic via this executor.
- **`SmallRyeContextPropagation`** — the MicroProfile Context Propagation integration. The new event bus can use `ThreadContext` with selective propagation: capture `Security` and `OpenTelemetry` contexts, clear `CDI` request context.

### 14.5 Key Files for Implementation Reference

| Component | Reference File |
|---|---|
| Build-time consumer scanning | `extensions/vertx/deployment/.../VertxProcessor.java` (lines 171-257) |
| Build-time codec registration | `extensions/vertx/deployment/.../EventBusCodecProcessor.java` |
| Runtime consumer registration | `extensions/vertx/runtime/.../VertxEventBusConsumerRecorder.java` (lines 93-203) |
| Runtime consumer invocation | `extensions/vertx/runtime/.../EventConsumerInvoker.java` |
| Observer type resolution | `independent-projects/arc/processor/.../ObserverInfo.java` (lines 58-65) |
| Type matching/assignability | `independent-projects/arc/processor/.../BeanResolverImpl.java` (lines 192-259) |
| Type hierarchy discovery | `independent-projects/arc/runtime/.../HierarchyDiscovery.java` |
| Event type assignability rules | `independent-projects/arc/runtime/.../EventTypeAssignabilityRules.java` |
| Context propagation provider | `extensions/arc/runtime/.../context/ArcContextProvider.java` |
| Mutiny context infrastructure | `extensions/mutiny/runtime/.../MutinyInfrastructure.java` |

---

## 15. Architectural Change: Central Dispatcher (branch `unifiedEventing2`)

The initial POC (branch `unifiedEventing`) used **consumer-side fan-out**: at build time, each consumer was registered on Vert.x addresses for its observed type AND all known subtypes. This leveraged Vert.x's native routing but had a fundamental flaw — **programmatic consumers registered at runtime cannot do subtype matching** because:

1. Discovering subtypes requires classpath scanning, unavailable at runtime (especially in native mode).
2. A build-time precomputed subtype registry only covers types with existing `@OnEvent` consumers, making behavior non-deterministic — users can't predict whether fan-out will work.
3. Precomputing subtypes for ALL types in the index is too expensive for large applications.

**The fix: move type matching from Vert.x's routing into our own dispatch layer.**

Instead of mapping event types to Vert.x addresses and relying on Vert.x for routing, we use a **central dispatcher** that delegates to CDI's `BeanContainer.isMatchingEvent()`:

- Each consumer registers on a **single unique Vert.x address** (for context isolation and async delivery).
- A central `EventDispatcher` maintains a registry of consumers with their `Type`, `Set<Annotation>` qualifiers, and response `Type`.
- When an event is sent, the dispatcher iterates all consumers and calls `isMatchingEvent()` to find matches (results are cached and invalidated on registration changes).
- For `request()`, the dispatcher additionally filters by response type assignability.

**What this enables:**
- Full CDI-compliant type matching including parameterized types, qualifier member values, and catch-all behavior.
- Programmatic consumers work with full subtype matching — they register in the same dispatcher.
- Consistent behavior for both declarative and programmatic consumers.

**What we give up:**
- Vert.x's built-in round-robin and broadcast — we implement these in the dispatcher.
- One extra in-process hop (dispatcher → consumer), negligible in practice.

---

## 16. Implemented Changes (branch `unifiedEventing2`)

The following enhancements have been implemented on top of the original POC:

### 16.1 CDI `isMatchingEvent` for type and qualifier resolution
Replaced manual type closure and string-based qualifier matching with `BeanContainer.isMatchingEvent()`. Supports parameterized types, qualifier member values, and CDI-standard catch-all behavior for unqualified consumers. Consumer metadata (Type + Annotations) is generated into a single Gizmo2 class at build time.

### 16.2 EventInfo metadata
Emitters attach metadata via `withMetadata(String, Object)`. Consumers access it through an optional `EventInfo` parameter (alongside the event and CDI-injected params). The Vert.x message body is now an `EventEnvelope` record.

### 16.3 Multi-parameter consumer methods and marker annotation
`@OnEvent` is a parameter-level annotation that identifies the event parameter (at any position). Other parameters are CDI-injected or `EventInfo`. Qualifiers are placed on the same parameter as `@OnEvent`.

### 16.4 Response type filtering for `request()`
`request()` filters consumers by return type assignability — void consumers and consumers with incompatible return types are excluded.

### 16.5 Programmatic request-reply consumers
Added `replyingConsumer()` (sync) and `replyingConsumerAsync()` (async) for programmatic consumers that can reply to `request()` calls.

### 16.6 Synthetic bean for QuarkusEvent
Replaced CDI producer with a synthetic bean, enabling `@Inject @SomeQualifier QuarkusEvent<T>`.

### 16.7 Removed `ordered` from `@OnEvent`
The `ordered` flag was a Vert.x EventBus implementation detail and has been removed.

---

## 17. Remaining TODOs

1. ~~**Marker annotation**~~ **DONE** — `@OnEvent` is now a parameter-level annotation identifying the event parameter at any position.
2. **Execution model** — Replace `@OnEvent(blocking=true)` with return type inference + `@Blocking`/`@NonBlocking` annotations, consistent with other Quarkus extensions. Add `@RunOnVirtualThread` support.
3. **Context propagation** — Implement security + tracing propagation as designed in §7.
4. **CLI application verification** — Test without Vert.x HTTP dependency.
5. **Documentation** — Quarkus guide + migration guide from `@ConsumeEvent`.

---

## 18. Next Steps

1. **Align on open questions** (§12.2) with the working group
2. **Prototype the build-time processor** — start with simple class-based types, no generics
3. **Prototype the runtime invoker** — adapt from `EventConsumerInvoker` with context propagation
4. **Add parameterized type support** — extend the processor using ArC's type resolution infrastructure
5. **Integration tests** — cover all three messaging patterns with context propagation verification
6. **Documentation** — Quarkus guide + migration guide from `@ConsumeEvent`

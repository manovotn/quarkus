package io.quarkus.events.runtime;

import static io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setCurrentContextSafe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.vertx.LocalEventBusCodec;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;

@Recorder
public class EventsRecorder {

    private static final Logger LOGGER = Logger.getLogger(EventsRecorder.class);

    static volatile Vertx vertx;
    static volatile EventDispatcher dispatcher;
    static volatile List<MessageConsumer<?>> messageConsumers;
    private static final Set<Class<?>> registeredCodecs = ConcurrentHashMap.newKeySet();

    public void init(Supplier<Vertx> vertxSupplier, List<EventConsumerInfo> consumers,
            ShutdownContext shutdown) {
        vertx = vertxSupplier.get();
        messageConsumers = new CopyOnWriteArrayList<>();

        io.vertx.mutiny.core.eventbus.EventBus mutinyEventBus = new io.vertx.mutiny.core.eventbus.EventBus(
                vertx.eventBus());
        dispatcher = new EventDispatcher(mutinyEventBus);

        registerConsumers(consumers);

        shutdown.addShutdownTask(() -> {
            if (messageConsumers != null) {
                CountDownLatch latch = new CountDownLatch(messageConsumers.size());
                for (MessageConsumer<?> mc : messageConsumers) {
                    mc.unregister(ar -> latch.countDown());
                }
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                messageConsumers.clear();
            }
            messageConsumers = null;
            dispatcher = null;
            vertx = null;
        });
    }

    /**
     * Register a default codec for an event type if not already registered.
     * Called by {@link EventDispatcher} at send time for on-demand codec registration.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void registerCodecForType(Class<?> eventType) {
        if (vertx == null || !registeredCodecs.add(eventType)) {
            return;
        }
        try {
            vertx.eventBus().registerDefaultCodec(eventType,
                    new LocalEventBusCodec("quarkus_events_codec_" + eventType.getName()));
        } catch (IllegalStateException e) {
            // Already registered externally (e.g., by quarkus-vertx)
        }
    }

    private void registerConsumers(List<EventConsumerInfo> consumers) {
        if (consumers.isEmpty()) {
            return;
        }

        EventBus eventBus = vertx.eventBus();
        VertxInternal vi = (VertxInternal) vertx;

        CountDownLatch latch = new CountDownLatch(consumers.size());
        List<Throwable> failures = new ArrayList<>();

        int consumerId = 0;
        for (EventConsumerInfo info : consumers) {
            EventsConsumerInvoker invoker = new EventsConsumerInvoker(info.getInvoker().getValue());
            boolean blocking = info.isBlocking();
            boolean ordered = info.isOrdered();

            // Each consumer gets a unique address
            String address = "__qx_event__/consumer/" + consumerId++;

            // Register in the dispatcher for type-based routing
            try {
                Class<?> observedType = Class.forName(info.getObservedType(),
                        false, Thread.currentThread().getContextClassLoader());
                dispatcher.registerConsumer(observedType, new HashSet<>(info.getQualifierNames()), address);
            } catch (ClassNotFoundException e) {
                LOGGER.warnf("Could not load observed type %s for dispatcher registration", info.getObservedType());
            }

            // Register a Vert.x consumer on the unique address
            ContextInternal context = vi.createEventLoopContext();
            context.runOnContext(new Handler<Void>() {
                @Override
                public void handle(Void x) {
                    MessageConsumer<Object> consumer = eventBus.localConsumer(address);
                    consumer.handler(new Handler<Message<Object>>() {
                        @Override
                        public void handle(Message<Object> m) {
                            setCurrentContextSafe(true);
                            if (blocking) {
                                Future<Void> future = io.vertx.core.Vertx.currentContext()
                                        .executeBlocking(new Callable<Void>() {
                                            @Override
                                            public Void call() {
                                                try {
                                                    invoker.invoke(m);
                                                } catch (Exception e) {
                                                    if (m.replyAddress() == null) {
                                                        throw EventsConsumerInvoker.wrapIfNecessary(e);
                                                    } else {
                                                        m.fail(500, e.toString());
                                                    }
                                                }
                                                return null;
                                            }
                                        }, ordered);
                                future.onFailure(context::reportException);
                            } else {
                                try {
                                    invoker.invoke(m);
                                } catch (Exception e) {
                                    if (m.replyAddress() == null) {
                                        throw EventsConsumerInvoker.wrapIfNecessary(e);
                                    } else {
                                        m.fail(500, e.toString());
                                    }
                                }
                            }
                        }
                    });
                    consumer.completionHandler(ar -> {
                        latch.countDown();
                        if (ar.failed()) {
                            failures.add(ar.cause());
                        }
                    });
                    messageConsumers.add(consumer);
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unable to register event consumers", e);
        }
        if (!failures.isEmpty()) {
            throw new RuntimeException("Event consumer registration failed", failures.get(0));
        }
    }
}

package io.quarkus.events.deployment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AutoAddScopeBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.InvokerFactoryBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.AnnotationStore;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.InvokerBuilder;
import io.quarkus.arc.processor.InvokerInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.events.OnEvent;
import io.quarkus.events.runtime.EventAddress;
import io.quarkus.events.runtime.EventConsumerInfo;
import io.quarkus.events.runtime.EventsRecorder;
import io.quarkus.vertx.core.deployment.CoreVertxBuildItem;
import io.smallrye.mutiny.Uni;

public class EventsProcessor {

    private static final Logger LOGGER = Logger.getLogger(EventsProcessor.class);

    private static final DotName ON_EVENT = DotName.createSimple(OnEvent.class);
    private static final DotName UNI = DotName.createSimple(Uni.class);
    private static final DotName QUALIFIER = DotName.createSimple("jakarta.inject.Qualifier");

    // Types that are too broad to observe — build-time error if used
    private static final Set<DotName> FORBIDDEN_OBSERVED_TYPES = Set.of(
            DotName.createSimple(Object.class),
            DotName.createSimple("java.io.Serializable"),
            DotName.createSimple("java.lang.Comparable"));

    private static final String FEATURE = "events";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AutoAddScopeBuildItem autoAddScope() {
        return AutoAddScopeBuildItem.builder()
                .containsAnnotations(ON_EVENT)
                .defaultScope(BuiltinScope.SINGLETON)
                .reason("Found @OnEvent consumer methods")
                .build();
    }

    @BuildStep
    UnremovableBeanBuildItem unremovableBeans() {
        return new UnremovableBeanBuildItem(
                new UnremovableBeanBuildItem.BeanClassAnnotationExclusion(ON_EVENT));
    }

    @BuildStep
    void collectEventConsumers(
            BeanRegistrationPhaseBuildItem beanRegistrationPhase,
            InvokerFactoryBuildItem invokerFactory,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<EventConsumerBuildItem> eventConsumers) {

        AnnotationStore annotationStore = beanRegistrationPhase.getContext()
                .get(BuildExtension.Key.ANNOTATION_STORE);

        for (BeanInfo bean : beanRegistrationPhase.getContext().beans().classBeans()) {
            for (MethodInfo method : bean.getTarget().get().asClass().methods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                AnnotationInstance onEvent = annotationStore.getAnnotation(method, ON_EVENT);
                if (onEvent == null) {
                    continue;
                }

                // Validate: exactly one parameter
                if (method.parametersCount() != 1) {
                    throw new IllegalStateException(String.format(
                            "@OnEvent method must accept exactly one parameter: %s [bean: %s]",
                            method, bean));
                }

                Type paramType = method.parameterType(0);
                DotName observedTypeName = paramType.name();

                // Validate: no overly broad types
                if (FORBIDDEN_OBSERVED_TYPES.contains(observedTypeName)) {
                    throw new IllegalStateException(String.format(
                            "@OnEvent method must not observe %s — use a specific event type or marker interface: %s [bean: %s]",
                            observedTypeName, method, bean));
                }

                // Extract qualifier annotations from the event parameter
                List<String> qualifierNames = extractQualifiers(method, annotationStore, combinedIndex);

                // Build invoker: transform Message<Object> param to the body
                InvokerBuilder builder = invokerFactory.createInvoker(bean, method)
                        .withInstanceLookup()
                        .withArgumentTransformer(0, io.vertx.core.eventbus.Message.class, "body");

                if (method.returnType().name().equals(UNI)) {
                    builder.withReturnValueTransformer(Uni.class, "subscribeAsCompletionStage");
                }

                InvokerInfo invoker = builder.build();

                boolean blocking = onEvent.value("blocking") != null && onEvent.value("blocking").asBoolean();
                boolean ordered = onEvent.value("ordered") != null && onEvent.value("ordered").asBoolean();

                eventConsumers.produce(new EventConsumerBuildItem(
                        observedTypeName.toString(), qualifierNames, invoker, blocking, ordered));
                LOGGER.debugf("Found @OnEvent consumer: %s on %s (type: %s, qualifiers: %s)",
                        method, bean, observedTypeName, qualifierNames);
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Produce(ServiceStartBuildItem.class)
    void registerConsumers(
            EventsRecorder recorder,
            CoreVertxBuildItem vertx,
            CombinedIndexBuildItem combinedIndex,
            List<EventConsumerBuildItem> eventConsumers,
            ShutdownContextBuildItem shutdown,
            RecorderContext recorderContext) {

        if (eventConsumers.isEmpty()) {
            return;
        }

        List<EventConsumerInfo> consumerInfos = new ArrayList<>();
        Set<String> allEventTypeNames = new HashSet<>();

        for (EventConsumerBuildItem consumer : eventConsumers) {
            // Compute the fan-out set: observed type + all known subtypes
            List<String> addresses = computeFanOutAddresses(
                    consumer.getObservedType(), consumer.getQualifierNames(), combinedIndex);

            // Collect all concrete event type names for codec registration
            // TODO refactor: this duplicates the subtype discovery in computeFanOutAddresses()
            allEventTypeNames.add(consumer.getObservedType());
            DotName observedDotName = DotName.createSimple(consumer.getObservedType());
            for (ClassInfo subclass : combinedIndex.getIndex().getAllKnownSubclasses(observedDotName)) {
                allEventTypeNames.add(subclass.name().toString());
            }
            ClassInfo classInfo = combinedIndex.getIndex().getClassByName(observedDotName);
            if (classInfo != null && classInfo.isInterface()) {
                for (ClassInfo implementor : combinedIndex.getIndex().getAllKnownImplementors(observedDotName)) {
                    allEventTypeNames.add(implementor.name().toString());
                }
            }

            consumerInfos.add(new EventConsumerInfo(
                    addresses,
                    recorderContext.newInstance(consumer.getInvoker().getClassName()),
                    consumer.isBlocking(),
                    consumer.isOrdered()));
        }

        // Load event type classes for codec registration
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        List<Class<?>> eventTypes = new ArrayList<>();
        for (String typeName : allEventTypeNames) {
            try {
                eventTypes.add(Class.forName(typeName, false, tccl));
            } catch (ClassNotFoundException e) {
                LOGGER.warnf("Could not load event type class %s for codec registration", typeName);
            }
        }

        recorder.init(vertx.getVertx(), consumerInfos, eventTypes, shutdown);
    }

    @BuildStep
    AdditionalBeanBuildItem registerQuarkusEventProducer() {
        return AdditionalBeanBuildItem.unremovableOf(
                io.quarkus.events.runtime.QuarkusEventProducer.class);
    }

    /**
     * Compute all Vert.x addresses a consumer should be registered on.
     * This includes the observed type itself and all known subtypes (fan-out).
     */
    private List<String> computeFanOutAddresses(String observedType, List<String> qualifierNames,
            CombinedIndexBuildItem combinedIndex) {
        Set<String> typeNames = new HashSet<>();
        typeNames.add(observedType);

        DotName observedDotName = DotName.createSimple(observedType);

        // Find all known subclasses
        for (ClassInfo subclass : combinedIndex.getIndex().getAllKnownSubclasses(observedDotName)) {
            typeNames.add(subclass.name().toString());
        }

        // Find all known implementors (if it's an interface)
        ClassInfo classInfo = combinedIndex.getIndex().getClassByName(observedDotName);
        if (classInfo != null && classInfo.isInterface()) {
            for (ClassInfo implementor : combinedIndex.getIndex().getAllKnownImplementors(observedDotName)) {
                typeNames.add(implementor.name().toString());
            }
        }

        // Generate addresses for each type in the fan-out set
        List<String> addresses = new ArrayList<>(typeNames.size());
        for (String typeName : typeNames) {
            addresses.add(EventAddress.of(typeName, qualifierNames));
        }

        LOGGER.debugf("Fan-out for %s: %s", observedType, addresses);
        return addresses;
    }

    /**
     * Extract qualifier annotation names from the event parameter.
     * A qualifier is any annotation on the method's first parameter that is itself
     * meta-annotated with @jakarta.inject.Qualifier.
     * <p>
     * Qualifiers are placed on the parameter (not the method) to avoid ArC
     * misinterpreting the method as a CDI producer method.
     */
    private List<String> extractQualifiers(MethodInfo method, AnnotationStore annotationStore,
            CombinedIndexBuildItem combinedIndex) {
        List<String> qualifiers = new ArrayList<>();
        for (AnnotationInstance annotation : annotationStore.getAnnotations(method)) {
            // Only look at annotations on the first parameter (the event payload)
            if (annotation.target() == null
                    || annotation.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.METHOD_PARAMETER) {
                continue;
            }
            if (annotation.target().asMethodParameter().position() != 0) {
                continue;
            }
            DotName annotationName = annotation.name();
            ClassInfo annotationClass = combinedIndex.getIndex().getClassByName(annotationName);
            if (annotationClass != null && annotationClass.hasAnnotation(QUALIFIER)) {
                qualifiers.add(annotationName.toString());
            }
        }
        return qualifiers;
    }
}

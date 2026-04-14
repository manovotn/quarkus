package io.quarkus.events.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AutoAddScopeBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.InvokerFactoryBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.AnnotationStore;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.InvokerBuilder;
import io.quarkus.arc.processor.InvokerInfo;
import io.quarkus.arc.processor.RuntimeTypeCreator;
import io.quarkus.deployment.GeneratedClassGizmo2Adaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.events.OnEvent;
import io.quarkus.events.runtime.ConsumerMetadata;
import io.quarkus.events.runtime.EventConsumerInfo;
import io.quarkus.events.runtime.EventsRecorder;
import io.quarkus.gizmo2.ClassOutput;
import io.quarkus.gizmo2.Const;
import io.quarkus.gizmo2.Expr;
import io.quarkus.gizmo2.Gizmo;
import io.quarkus.gizmo2.LocalVar;
import io.quarkus.gizmo2.desc.ConstructorDesc;
import io.quarkus.gizmo2.desc.MethodDesc;
import io.quarkus.runtime.util.HashUtil;
import io.quarkus.vertx.core.deployment.CoreVertxBuildItem;
import io.smallrye.mutiny.Uni;

public class EventsProcessor {

    private static final Logger LOGGER = Logger.getLogger(EventsProcessor.class);

    private static final DotName ON_EVENT = DotName.createSimple(OnEvent.class);
    private static final DotName UNI = DotName.createSimple(Uni.class);
    private static final DotName QUALIFIER = DotName.createSimple("jakarta.inject.Qualifier");
    private static final DotName EVENT_INFO = DotName.createSimple(io.quarkus.events.EventInfo.class);

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

                // Validate: at least one parameter
                if (method.parametersCount() < 1) {
                    throw new IllegalStateException(String.format(
                            "@OnEvent method must have at least one parameter (the event): %s [bean: %s]",
                            method, bean));
                }

                // TODO: Consider whether the event parameter should be identified by a marker
                // annotation (like CDI's @Observes) rather than by convention (always position 0).
                // A marker annotation would allow the event at any position and be more explicit.

                // Event parameter is always at position 0
                Type paramType = method.parameterType(0);
                DotName observedTypeName = paramType.name();

                // Validate: no overly broad types
                if (FORBIDDEN_OBSERVED_TYPES.contains(observedTypeName)) {
                    throw new IllegalStateException(String.format(
                            "@OnEvent method must not observe %s — use a specific event type or marker interface: %s [bean: %s]",
                            observedTypeName, method, bean));
                }

                // Extract qualifier AnnotationInstances from the event parameter
                List<AnnotationInstance> qualifiers = extractQualifiers(method, annotationStore, combinedIndex);

                // Detect EventInfo parameter and CDI-injected parameters
                int eventInfoPosition = -1;
                InvokerBuilder builder = invokerFactory.createInvoker(bean, method)
                        .withInstanceLookup();

                for (int i = 1; i < method.parametersCount(); i++) {
                    if (method.parameterType(i).name().equals(EVENT_INFO)) {
                        eventInfoPosition = i;
                        // EventInfo is provided by the invoker wrapper, not CDI lookup
                    } else {
                        // CDI-injected parameter
                        builder.withArgumentLookup(i);
                    }
                }

                // Determine the response type (unwrap Uni<T> to T, void to null)
                Type responseType = null;
                Type returnType = method.returnType();
                if (returnType.kind() == Type.Kind.VOID) {
                    responseType = null;
                } else if (returnType.name().equals(UNI)) {
                    // Uni<T> -> T
                    responseType = returnType.asParameterizedType().arguments().get(0);
                    builder.withReturnValueTransformer(Uni.class, "subscribeAsCompletionStage");
                } else {
                    responseType = returnType;
                }

                InvokerInfo invoker = builder.build();

                boolean blocking = onEvent.value("blocking") != null && onEvent.value("blocking").asBoolean();

                eventConsumers.produce(new EventConsumerBuildItem(
                        paramType, responseType, qualifiers, invoker, blocking,
                        method.parametersCount(), eventInfoPosition));
                LOGGER.debugf("Found @OnEvent consumer: %s on %s (type: %s, qualifiers: %s)",
                        method, bean, paramType, qualifiers);
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Produce(ServiceStartBuildItem.class)
    void registerConsumers(
            EventsRecorder recorder,
            BeanRegistrationPhaseBuildItem beanRegistration,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            CoreVertxBuildItem vertx,
            List<EventConsumerBuildItem> eventConsumers,
            ShutdownContextBuildItem shutdown,
            RecorderContext recorderContext,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {

        List<EventConsumerInfo> consumerInfos = new ArrayList<>();
        String metadataClassName = null;

        if (!eventConsumers.isEmpty()) {
            // Generate a single metadata registry class for all consumers
            metadataClassName = "io.quarkus.events.runtime.GeneratedConsumerMetadata_"
                    + HashUtil.sha256(eventConsumers.stream()
                            .map(c -> c.getObservedType().toString() + c.getQualifiers().toString())
                            .collect(Collectors.joining(",")));

            ClassOutput classOutput = new GeneratedClassGizmo2Adaptor(generatedClasses, generatedResources, true);
            Gizmo gizmo = Gizmo.create(classOutput)
                    .withDebugInfo(false)
                    .withParameters(false);

            gizmo.class_(metadataClassName, cc -> {
                cc.implements_(ConsumerMetadata.class);

                cc.constructor(con -> {
                    con.body(bc -> {
                        bc.invokeSpecial(ConstructorDesc.of(Object.class), cc.this_());
                        bc.return_();
                    });
                });

                cc.method("entries", mc -> {
                    mc.returning(List.class);
                    mc.body(bc -> {
                        LocalVar tccl = bc.localVar("tccl", bc.invokeVirtual(
                                MethodDesc.of(Thread.class, "getContextClassLoader", ClassLoader.class),
                                bc.currentThread()));
                        RuntimeTypeCreator rttc = RuntimeTypeCreator.of(bc).withTCCL(tccl);

                        // Build a List.of(...) with one Entry per consumer
                        Expr list = bc.listOf(eventConsumers, consumer -> {
                            // Create the observed Type
                            Expr observedType = rttc.create(consumer.getObservedType());

                            // Create the qualifier Set<Annotation>
                            Expr qualifiers;
                            if (consumer.getQualifiers().isEmpty()) {
                                qualifiers = bc.invokeStatic(MethodDesc.of(Set.class, "of", Set.class));
                            } else {
                                qualifiers = bc.setOf(consumer.getQualifiers(),
                                        qualifier -> beanRegistration.getBeanProcessor().getAnnotationLiteralProcessor()
                                                .create(bc,
                                                        beanArchiveIndex.getIndex().getClassByName(qualifier.name()),
                                                        qualifier));
                            }

                            // Create the response Type (null for void consumers)
                            Expr responseType;
                            if (consumer.getResponseType() != null) {
                                responseType = rttc.create(consumer.getResponseType());
                            } else {
                                responseType = Const.ofNull(java.lang.reflect.Type.class);
                            }

                            return bc.new_(ConsumerMetadata.Entry.class, observedType, qualifiers, responseType);
                        });

                        bc.return_(list);
                    });
                });
            });

            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(metadataClassName)
                    .publicConstructors()
                    .build());

            for (EventConsumerBuildItem consumer : eventConsumers) {
                consumerInfos.add(new EventConsumerInfo(
                        recorderContext.newInstance(consumer.getInvoker().getClassName()),
                        consumer.isBlocking(),
                        consumer.getParameterCount(),
                        consumer.getEventInfoPosition()));
            }
        }

        recorder.init(vertx.getVertx(), consumerInfos, metadataClassName, shutdown);
    }

    @BuildStep
    AdditionalBeanBuildItem registerQuarkusEventProducer() {
        return AdditionalBeanBuildItem.unremovableOf(
                io.quarkus.events.runtime.QuarkusEventProducer.class);
    }

    /**
     * Extract qualifier AnnotationInstances from the event parameter.
     */
    private List<AnnotationInstance> extractQualifiers(MethodInfo method, AnnotationStore annotationStore,
            CombinedIndexBuildItem combinedIndex) {
        List<AnnotationInstance> qualifiers = new ArrayList<>();
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
            org.jboss.jandex.ClassInfo annotationClass = combinedIndex.getIndex().getClassByName(annotationName);
            if (annotationClass != null && annotationClass.hasAnnotation(QUALIFIER)) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers;
    }
}

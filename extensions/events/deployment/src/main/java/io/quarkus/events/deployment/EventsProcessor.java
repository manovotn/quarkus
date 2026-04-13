package io.quarkus.events.deployment;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
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
import io.quarkus.gizmo2.Expr;
import io.quarkus.gizmo2.Gizmo;
import io.quarkus.gizmo2.LocalVar;
import io.quarkus.gizmo2.desc.ConstructorDesc;
import io.quarkus.gizmo2.desc.FieldDesc;
import io.quarkus.gizmo2.desc.MethodDesc;
import io.quarkus.runtime.util.HashUtil;
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

                // Preserve the full Jandex Type (including parameterized type info)
                org.jboss.jandex.Type paramType = method.parameterType(0);
                DotName observedTypeName = paramType.name();

                // Validate: no overly broad types
                if (FORBIDDEN_OBSERVED_TYPES.contains(observedTypeName)) {
                    throw new IllegalStateException(String.format(
                            "@OnEvent method must not observe %s — use a specific event type or marker interface: %s [bean: %s]",
                            observedTypeName, method, bean));
                }

                // Extract qualifier AnnotationInstances from the event parameter
                List<AnnotationInstance> qualifiers = extractQualifiers(method, annotationStore, combinedIndex);

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
                        paramType, qualifiers, invoker, blocking, ordered));
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

        ClassOutput classOutput = new GeneratedClassGizmo2Adaptor(generatedClasses, generatedResources,
                new Function<String, String>() {
                    @Override
                    public String apply(String generatedClassName) {
                        // Map generated class to the declaring class for class loading
                        return generatedClassName.substring(0, generatedClassName.indexOf("_EventMeta_"));
                    }
                });
        Gizmo gizmo = Gizmo.create(classOutput)
                .withDebugInfo(false)
                .withParameters(false);

        List<EventConsumerInfo> consumerInfos = new ArrayList<>();
        List<String> metadataClassNames = new ArrayList<>();

        for (EventConsumerBuildItem consumer : eventConsumers) {
            // Generate a unique metadata class name
            String metadataClassName = consumer.getObservedType().name().toString()
                    + "_EventMeta_"
                    + HashUtil.sha256(consumer.getObservedType().toString()
                            + consumer.getQualifiers().stream().map(Object::toString).collect(Collectors.joining(",")));

            gizmo.class_(metadataClassName, cc -> {
                cc.implements_(ConsumerMetadata.class);

                FieldDesc observedTypeField = cc.field("observedType", fc -> {
                    fc.private_();
                    fc.final_();
                    fc.setType(Type.class);
                });
                FieldDesc qualifiersField = cc.field("qualifiers", fc -> {
                    fc.private_();
                    fc.final_();
                    fc.setType(Set.class);
                });

                cc.constructor(con -> {
                    con.body(bc -> {
                        bc.invokeSpecial(ConstructorDesc.of(Object.class), cc.this_());

                        LocalVar tccl = bc.localVar("tccl", bc.invokeVirtual(
                                MethodDesc.of(Thread.class, "getContextClassLoader", ClassLoader.class),
                                bc.currentThread()));

                        // Create the observed Type
                        RuntimeTypeCreator rttc = RuntimeTypeCreator.of(bc).withTCCL(tccl);
                        bc.set(cc.this_().field(observedTypeField), rttc.create(consumer.getObservedType()));

                        // Create the qualifier Set<Annotation>
                        if (consumer.getQualifiers().isEmpty()) {
                            bc.set(cc.this_().field(qualifiersField),
                                    bc.invokeStatic(MethodDesc.of(Set.class, "of", Set.class)));
                        } else {
                            Expr qualifiersSet = bc.setOf(consumer.getQualifiers(),
                                    qualifier -> beanRegistration.getBeanProcessor().getAnnotationLiteralProcessor()
                                            .create(bc,
                                                    beanArchiveIndex.getIndex().getClassByName(qualifier.name()),
                                                    qualifier));
                            bc.set(cc.this_().field(qualifiersField), qualifiersSet);
                        }

                        bc.return_();
                    });
                });

                cc.method("observedType", mc -> {
                    mc.returning(Type.class);
                    mc.body(bc -> bc.return_(cc.this_().field(observedTypeField)));
                });

                cc.method("qualifiers", mc -> {
                    mc.returning(Set.class);
                    mc.body(bc -> bc.return_(cc.this_().field(qualifiersField)));
                });
            });

            metadataClassNames.add(metadataClassName);

            consumerInfos.add(new EventConsumerInfo(
                    metadataClassName,
                    recorderContext.newInstance(consumer.getInvoker().getClassName()),
                    consumer.isBlocking(),
                    consumer.isOrdered()));
        }

        // Register generated metadata classes for reflection
        if (!metadataClassNames.isEmpty()) {
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(metadataClassNames)
                    .publicConstructors()
                    .build());
        }

        recorder.init(vertx.getVertx(), consumerInfos, shutdown);
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

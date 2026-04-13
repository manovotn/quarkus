package io.quarkus.events.deployment;

import java.util.List;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.Type;

import io.quarkus.arc.processor.InvokerInfo;
import io.quarkus.builder.item.MultiBuildItem;

/**
 * Build item representing a discovered {@code @OnEvent} consumer method.
 */
public final class EventConsumerBuildItem extends MultiBuildItem {

    private final Type observedType;
    private final List<AnnotationInstance> qualifiers;
    private final InvokerInfo invoker;
    private final boolean blocking;
    private final boolean ordered;

    public EventConsumerBuildItem(Type observedType, List<AnnotationInstance> qualifiers, InvokerInfo invoker,
            boolean blocking, boolean ordered) {
        this.observedType = observedType;
        this.qualifiers = qualifiers;
        this.invoker = invoker;
        this.blocking = blocking;
        this.ordered = ordered;
    }

    public Type getObservedType() {
        return observedType;
    }

    public List<AnnotationInstance> getQualifiers() {
        return qualifiers;
    }

    public InvokerInfo getInvoker() {
        return invoker;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public boolean isOrdered() {
        return ordered;
    }
}

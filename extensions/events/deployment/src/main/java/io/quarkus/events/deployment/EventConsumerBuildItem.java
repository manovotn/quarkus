package io.quarkus.events.deployment;

import java.util.List;

import io.quarkus.arc.processor.InvokerInfo;
import io.quarkus.builder.item.MultiBuildItem;

/**
 * Build item representing a discovered {@code @OnEvent} consumer method.
 */
public final class EventConsumerBuildItem extends MultiBuildItem {

    private final String observedType;
    private final List<String> qualifierNames;
    private final InvokerInfo invoker;
    private final boolean blocking;
    private final boolean ordered;

    public EventConsumerBuildItem(String observedType, List<String> qualifierNames, InvokerInfo invoker,
            boolean blocking, boolean ordered) {
        this.observedType = observedType;
        this.qualifierNames = qualifierNames;
        this.invoker = invoker;
        this.blocking = blocking;
        this.ordered = ordered;
    }

    public String getObservedType() {
        return observedType;
    }

    public List<String> getQualifierNames() {
        return qualifierNames;
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

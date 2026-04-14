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
    private final Type responseType;
    private final List<AnnotationInstance> qualifiers;
    private final InvokerInfo invoker;
    private final boolean blocking;
    private final int parameterCount;
    private final int eventParamPosition;
    private final int eventInfoPosition;

    public EventConsumerBuildItem(Type observedType, Type responseType, List<AnnotationInstance> qualifiers,
            InvokerInfo invoker, boolean blocking, int parameterCount, int eventParamPosition, int eventInfoPosition) {
        this.observedType = observedType;
        this.responseType = responseType;
        this.qualifiers = qualifiers;
        this.invoker = invoker;
        this.blocking = blocking;
        this.parameterCount = parameterCount;
        this.eventParamPosition = eventParamPosition;
        this.eventInfoPosition = eventInfoPosition;
    }

    public Type getObservedType() {
        return observedType;
    }

    /**
     * @return the consumer's return type, or {@code null} for void methods
     */
    public Type getResponseType() {
        return responseType;
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

    public int getParameterCount() {
        return parameterCount;
    }

    public int getEventParamPosition() {
        return eventParamPosition;
    }

    public int getEventInfoPosition() {
        return eventInfoPosition;
    }
}

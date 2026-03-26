package io.quarkus.events.runtime;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.invoke.Invoker;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext.ContextState;
import io.quarkus.arc.ManagedContext;
import io.vertx.core.eventbus.Message;

/**
 * Invokes a business method annotated with {@link io.quarkus.events.OnEvent}.
 * Manages CDI request context activation per delivery.
 */
public class EventsConsumerInvoker {

    private final Invoker<Object, Object> invoker;

    public EventsConsumerInvoker(Invoker<Object, Object> invoker) {
        this.invoker = invoker;
    }

    public void invoke(Message<Object> message) throws Exception {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            Object ret = invoker.invoke(null, new Object[] { message });
            handleReturn(ret, message, null);
        } else {
            requestContext.activate();
            Object ret;
            try {
                ret = invoker.invoke(null, new Object[] { message });
            } catch (Exception e) {
                requestContext.terminate();
                throw e;
            }
            if (ret == null) {
                requestContext.terminate();
            } else if (ret instanceof CompletionStage) {
                ContextState endState = requestContext.getState();
                requestContext.deactivate();
                handleReturn(ret, message, () -> requestContext.destroy(endState));
            } else {
                requestContext.terminate();
                replyIfNeeded(ret, message);
            }
        }
    }

    private void handleReturn(Object ret, Message<Object> message, Runnable onComplete) {
        if (ret == null) {
            return;
        }
        if (ret instanceof CompletionStage) {
            ((CompletionStage<?>) ret).whenComplete((result, failure) -> {
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Exception e) {
                        throw wrapIfNecessary(e);
                    }
                }
                if (failure != null) {
                    if (message.replyAddress() != null) {
                        message.fail(500, failure.getMessage());
                    } else {
                        throw wrapIfNecessary(failure);
                    }
                } else {
                    replyIfNeeded(result, message);
                }
            });
        } else {
            replyIfNeeded(ret, message);
        }
    }

    private void replyIfNeeded(Object result, Message<Object> message) {
        if (result != null && message.replyAddress() != null) {
            message.reply(result);
        }
    }

    static RuntimeException wrapIfNecessary(Throwable e) {
        if (e instanceof Error) {
            throw (Error) e;
        } else if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        } else {
            return new RuntimeException(e);
        }
    }
}

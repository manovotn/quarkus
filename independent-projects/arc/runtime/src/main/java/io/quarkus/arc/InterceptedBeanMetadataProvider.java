package io.quarkus.arc;

import static io.quarkus.arc.CreationalContextImpl.unwrap;

import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Intercepted;
import javax.enterprise.inject.spi.Bean;

/**
 * {@link Intercepted} {@link Bean} metadata provider.
 */
public class InterceptedBeanMetadataProvider implements InjectableReferenceProvider<Contextual<?>> {

    @Override
    public Contextual<?> get(CreationalContext<Contextual<?>> creationalContext) {
        CreationalContextImpl<?> parent = unwrap(creationalContext).getParent();
        // TODO in some cases, first level CC does the trick but for FT interceptor we need second, why?
        while (parent != null) {
            CreationalContextImpl<?> newParent = unwrap(parent).getParent();
            if (newParent != null) {
                parent = newParent;
            } else {
                return parent.getContextual();
            }
        }
        return null;
    }

}

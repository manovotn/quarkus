package io.quarkus.arc.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * TODO doc this, something along the lines of BBD.addInterceptorBinding()
 */
public final class InterceptorBindingBuildItem extends MultiBuildItem {

    private DotName name;

    public InterceptorBindingBuildItem(DotName name) {
        this.name = name;
    }

    public DotName getName() {
        return this.name;
    }
}

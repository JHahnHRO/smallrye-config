package io.smallrye.config.inject;

import javax.enterprise.util.AnnotationLiteral;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The missing literal class for {@link ConfigProperty}.
 */
class ConfigPropertyLiteral extends AnnotationLiteral<ConfigProperty> implements ConfigProperty {

    private final String name;
    private final String defaultValue;

    public ConfigPropertyLiteral() {
        this("", "");
    }

    public ConfigPropertyLiteral(String name, String defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String defaultValue() {
        return defaultValue;
    }
}

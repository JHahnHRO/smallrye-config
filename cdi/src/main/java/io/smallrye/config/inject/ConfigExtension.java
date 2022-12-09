/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.config.inject;

import static io.smallrye.config.ConfigMappings.registerConfigMappings;
import static io.smallrye.config.ConfigMappings.ConfigClassWithPrefix.configClassWithPrefix;
import static io.smallrye.config.inject.ConfigProducer.isClassHandledByConfigProducer;
import static io.smallrye.config.inject.InjectionMessages.formatInjectionPoint;
import static io.smallrye.config.inject.SecuritySupport.getContextClassLoader;
import static java.util.stream.Collectors.toSet;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.InjectionException;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.literal.InjectLiteral;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBean;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.ProcessInjectionTarget;
import javax.enterprise.inject.spi.WithAnnotations;
import javax.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import javax.inject.Inject;
import javax.inject.Provider;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.ConfigMappings.ConfigClassWithPrefix;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.SmallRyeConfig;

/**
 * CDI Extension to produces Config bean.
 *
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class ConfigExtension implements Extension {
    private final Set<InjectionPoint> configPropertyInjectionPoints = new HashSet<>();
    /**
     * All @ConfigProperties annotations discovered during container initialization. Will be used during
     * {@link AfterDeploymentValidation} phase to validate existence of the necessary properties.
     */
    private final Set<ConfigClassWithPrefix> configProperties = new HashSet<>();
    /**
     * ConfigMappings for SmallRye Config
     */
    private final Set<ConfigClassWithPrefix> configMappings = new HashSet<>();
    /**
     * ConfigMappings for CDI
     */
    private final Set<ConfigClassWithPrefix> configMappingBeans = new HashSet<>();
    /**
     * Holds a {@link DynamicInjectionTarget} for each class annotated with {@link ConfigProperties}.
     */
    private final Map<Class<?>, DynamicInjectionTarget<?>> injectionTargetMap = new HashMap<>();

    public ConfigExtension() {
    }

    protected void beforeBeanDiscovery(@Observes BeforeBeanDiscovery bbd, BeanManager bm) {
        AnnotatedType<ConfigProducer> configBean = bm.createAnnotatedType(ConfigProducer.class);
        bbd.addAnnotatedType(configBean, ConfigProducer.class.getName());

        /*
         * The following may fix some behaviour with OWB, but causes another bug with programmatic lookup where
         * prefixes that are not discoverable during container initialization will fail to resolve to the
         *
         * @ConfigProperties bean.
         * See https://github.com/smallrye/smallrye-config/issues/860
         *
         * // Remove NonBinding annotation. OWB is not able to look up CDI beans programmatically with NonBinding in the
         * // case the look-up changed the non-binding parameters (in this case the prefix)
         * AnnotatedTypeConfigurator<ConfigProperties> configPropertiesConfigurator = bbd
         * .configureQualifier(ConfigProperties.class);
         * configPropertiesConfigurator.methods().forEach(methodConfigurator -> methodConfigurator
         * .remove(annotation -> annotation.annotationType().equals(Nonbinding.class)));
         *
         */
    }

    protected void processConfigProperties(
            @Observes @WithAnnotations(ConfigProperties.class) ProcessAnnotatedType<?> processAnnotatedType) {

        // Even if we filter in the CDI event, beans containing injection points of ConfigProperties are also fired.
        final ConfigProperties configProperties = processAnnotatedType.getAnnotatedType()
                .getAnnotation(ConfigProperties.class);
        if (configProperties != null) {
            processAnnotatedType.configureAnnotatedType()
                    .fields()
                    .forEach(f -> configureField(f, configProperties.prefix()));
        }
    }

    private <Y> void configureField(AnnotatedFieldConfigurator<Y> f, String prefix) {
        if (!f.getAnnotated().isAnnotationPresent(Inject.class)) {
            f.add(InjectLiteral.INSTANCE);
        }

        if (ConfigProperties.UNCONFIGURED_PREFIX.equals(prefix)) {
            prefix = "";
        } else {
            prefix = prefix + ".";
        }

        final ConfigProperty configProperty = f.getAnnotated().getAnnotation(ConfigProperty.class);
        final String name;
        final String defaultValue;
        if (configProperty == null) {
            // if there is no qualifier, use the field name as key and no default value.
            name = f.getAnnotated().getJavaMember().getName();
            defaultValue = ConfigProperty.UNCONFIGURED_VALUE;
        } else if (configProperty.name().isEmpty()) {
            // if there is a qualifier for the default value, but it specifies no name, we use the field name instead.
            name = f.getAnnotated().getJavaMember().getName();
            defaultValue = configProperty.defaultValue();
        } else {
            // if there is already a qualifier, change its name, but keep the default value
            name = configProperty.name();
            defaultValue = configProperty.defaultValue();
        }

        // replace (possibly) existing qualifier with new qualifier whose name has the right prefix.
        f.remove(q -> q.annotationType() == ConfigProperty.class)
                .add(new ConfigPropertyLiteral(prefix + name, defaultValue));
    }

    protected void processConfigMappings(
            @Observes @WithAnnotations(ConfigMapping.class) ProcessAnnotatedType<?> processAnnotatedType) {
        // Even if we filter in the CDI event, beans containing injection points of ConfigMapping are also fired.
        if (processAnnotatedType.getAnnotatedType().isAnnotationPresent(ConfigMapping.class)) {
            // We are going to veto, because it may be a managed bean, and we will use a configurator bean
            processAnnotatedType.veto();

            // Each config class is both in SmallRyeConfig and managed by a configurator bean.
            // CDI requires a single configurator bean per class due to non-binding prefix.
            ConfigClassWithPrefix mapping = configClassWithPrefix(
                    processAnnotatedType.getAnnotatedType().getJavaClass(),
                    processAnnotatedType.getAnnotatedType().getAnnotation(ConfigMapping.class).prefix());
            configMappings.add(mapping);
            configMappingBeans.add(mapping);
        }
    }

    protected void processConfigInjectionPoints(@Observes ProcessInjectionPoint<?, ?> pip) {
        if (pip.getInjectionPoint().getAnnotated().isAnnotationPresent(ConfigProperty.class)) {
            configPropertyInjectionPoints.add(pip.getInjectionPoint());
        }

        if (pip.getInjectionPoint().getAnnotated().isAnnotationPresent(ConfigProperties.class)) {
            ConfigClassWithPrefix properties = configClassWithPrefix((Class<?>) pip.getInjectionPoint().getType(),
                    pip.getInjectionPoint().getAnnotated().getAnnotation(ConfigProperties.class).prefix());

            // If the prefix is empty at the injection point, fallbacks to the class prefix (already registered)
            if (!properties.getPrefix().equals(ConfigProperties.UNCONFIGURED_PREFIX)) {
                configProperties.add(properties);
            }
        }

        // Add to SmallRyeConfig config classes with a different prefix by injection point
        if (pip.getInjectionPoint().getAnnotated().isAnnotationPresent(ConfigMapping.class)) {
            ConfigClassWithPrefix mapping = configClassWithPrefix((Class<?>) pip.getInjectionPoint().getType(),
                    pip.getInjectionPoint().getAnnotated().getAnnotation(ConfigMapping.class).prefix());
            // If the prefix is empty at the injection point, fallbacks to the class prefix (already registered)
            if (!mapping.getPrefix().isEmpty()) {
                configMappings.add(mapping);
            }
        }
    }

    protected <T> void onProcessInjectionTarget(@Observes ProcessInjectionTarget<T> pit, BeanManager beanManager) {
        AnnotatedType<T> annotatedType = pit.getAnnotatedType();
        if (!annotatedType.isAnnotationPresent(ConfigProperties.class)) {
            // bean not relevant
            return;
        }

        DynamicInjectionTarget<T> injectionTarget = new DynamicInjectionTarget<>(beanManager, annotatedType,
                pit.getInjectionTarget());
        injectionTargetMap.put(annotatedType.getJavaClass(), injectionTarget);
        pit.setInjectionTarget(injectionTarget);
    }

    protected <T> void onProcessBean(@Observes ProcessBean<T> pb) {
        Annotated annotated = pb.getAnnotated();
        if (annotated instanceof AnnotatedType) {
            Class<T> cls = ((AnnotatedType<T>) annotated).getJavaClass();
            DynamicInjectionTarget<T> dynamicInjectionTarget = (DynamicInjectionTarget<T>) injectionTargetMap.get(cls);
            if (dynamicInjectionTarget != null) {
                dynamicInjectionTarget.setBean(pb.getBean());
            }
        }
    }

    protected void registerCustomBeans(@Observes AfterBeanDiscovery abd, BeanManager bm) {
        Set<Class<?>> customTypes = new HashSet<>();
        for (InjectionPoint ip : configPropertyInjectionPoints) {
            Type requiredType = ip.getType();
            if (requiredType instanceof ParameterizedType) {
                ParameterizedType type = (ParameterizedType) requiredType;
                // TODO We should probably handle all parameterized types correctly
                if (type.getRawType().equals(Provider.class) || type.getRawType().equals(Instance.class)) {
                    // These injection points are satisfied by the built-in Instance bean 
                    Type typeArgument = type.getActualTypeArguments()[0];
                    if (typeArgument instanceof Class && !isClassHandledByConfigProducer(typeArgument)) {
                        customTypes.add((Class<?>) typeArgument);
                    }
                }
            } else if (requiredType instanceof Class && !isClassHandledByConfigProducer(requiredType)) {
                // type is not produced by ConfigProducer
                customTypes.add((Class<?>) requiredType);
            }
        }

        customTypes.forEach(customType -> abd.addBean(new ConfigInjectionBean<>(bm, customType)));
        configMappingBeans.forEach(mapping -> abd.addBean(new ConfigMappingInjectionBean<>(mapping, bm)));
    }

    protected void validate(@Observes AfterDeploymentValidation adv, BeanManager beanManager) {
        SmallRyeConfig config = ConfigProvider.getConfig(getContextClassLoader()).unwrap(SmallRyeConfig.class);
        Set<String> configNames = StreamSupport.stream(config.getPropertyNames().spliterator(), false).collect(toSet());
        for (InjectionPoint injectionPoint : getConfigPropertyInjectionPoints()) {
            Type type = injectionPoint.getType();

            // We don't validate the Optional / Provider / Supplier / ConfigValue for defaultValue.
            if (type instanceof Class && org.eclipse.microprofile.config.ConfigValue.class.isAssignableFrom(
                    (Class<?>) type) || type instanceof Class && OptionalInt.class.isAssignableFrom((Class<?>) type)
                    || type instanceof Class && OptionalLong.class.isAssignableFrom((Class<?>) type)
                    || type instanceof Class && OptionalDouble.class.isAssignableFrom((Class<?>) type)
                    || type instanceof ParameterizedType
                            && (Optional.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType())
                                    || Provider.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType())
                                    || Supplier.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType()))) {
                continue;
            }

            ConfigProperty configProperty = injectionPoint.getAnnotated().getAnnotation(ConfigProperty.class);
            String name;
            try {
                name = ConfigProducerUtil.getConfigKey(injectionPoint, configProperty);
            } catch (IllegalStateException e) {
                adv.addDeploymentProblem(
                        InjectionMessages.msg.retrieveConfigFailure(null, formatInjectionPoint(injectionPoint),
                                e.getLocalizedMessage(), e));
                continue;
            }

            // Check if the name is part of the properties first.
            // Since properties can be a subset, then search for the actual property for a value.
            // Check if it is a map
            // Finally also check if the property is indexed (might be a Collection with indexed properties).
            if ((!configNames.contains(name) && ConfigProducerUtil.getConfigValue(name, config).getValue() == null)
                    && !isMap(type) && !isIndexed(type, name, config)) {
                if (configProperty.defaultValue().equals(ConfigProperty.UNCONFIGURED_VALUE)) {
                    adv.addDeploymentProblem(
                            InjectionMessages.msg.noConfigValue(name, formatInjectionPoint(injectionPoint)));
                    continue;
                }
            }

            try {
                // Check if the value can be injected. This may cause duplicated config reads (to validate and to inject).
                ConfigProducerUtil.getValue(injectionPoint, config);
            } catch (Exception e) {
                adv.addDeploymentProblem(
                        InjectionMessages.msg.retrieveConfigFailure(name, formatInjectionPoint(injectionPoint),
                                e.getLocalizedMessage(), e));
            }
        }

        try {
            registerConfigMappings(config, configMappings);
        } catch (ConfigValidationException e) {
            adv.addDeploymentProblem(e);
        }

        validateConfigProperties(adv, beanManager);
    }

    private void validateConfigProperties(AfterDeploymentValidation adv, BeanManager beanManager) {
        for (ConfigClassWithPrefix classWithPrefix : configProperties) {
            Class<?> beanClass = classWithPrefix.getKlass();
            String prefix = classWithPrefix.getPrefix();

            // not null, because this is called during AfterBeanValidation and therefore all injectionPoints that we
            // used to fill the configProperties set were already validated.
            DynamicInjectionTarget<?> dynamicInjectionTarget = injectionTargetMap.get(beanClass);
            // has the side effect of creating all InjectionTarget instances that are needed for static
            // injection points. Only programmatic injection can construct new instances now.
            InjectionTarget<?> injectionTargetForPrefix = dynamicInjectionTarget.getInjectionTargetForPrefix(prefix);

            for (InjectionPoint injectionPoint : injectionTargetForPrefix.getInjectionPoints()) {
                try {
                    beanManager.validate(injectionPoint);
                } catch (InjectionException ex) {
                    adv.addDeploymentProblem(ex);
                }
            }
        }
    }

    protected Set<InjectionPoint> getConfigPropertyInjectionPoints() {
        return configPropertyInjectionPoints;
    }

    private static boolean isIndexed(Type type, String name, SmallRyeConfig config) {
        return type instanceof ParameterizedType &&
                (List.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType()) ||
                        Set.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType()))
                && !config.getIndexedPropertiesIndexes(name).isEmpty();
    }

    /**
     * Indicates whether the given type is a type of Map.
     *
     * @param type the type to check
     * @return {@code true} if the given type is a type of Map, {@code false} otherwise.
     */
    private static boolean isMap(final Type type) {
        return type instanceof ParameterizedType && Map.class.isAssignableFrom(
                (Class<?>) ((ParameterizedType) type).getRawType());
    }
}

package io.smallrye.config.inject;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InjectionTargetFactory;
import javax.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import javax.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * An {@link InjectionTarget} that computes and delegates to other {@link InjectionTarget}s, all representing the same
 * class, but each configured differently to that the {@link ConfigProperty} qualified field injection points have their
 * names prefixed dynamically.
 *
 * @param <T> the bean class type
 */
public class DynamicInjectionTarget<T> implements InjectionTarget<T> {
    private final BeanManager beanManager;
    private final AnnotatedType<T> annotatedType;
    private final InjectionTarget<T> originalInjectionTarget;
    private final InjectionPoint syntheticInjectionPoint; // the synthetic injection point of type InjectionPoint
    private Bean<T> bean;
    /**
     * Maps the value of {@link ConfigProperties#prefix()} at the injection point to the appropriate
     * {@link InjectionTarget} whose injection points are all configured to that particular prefix.
     */
    private final Map<String, InjectionTarget<T>> injectionTargets;

    public DynamicInjectionTarget(BeanManager beanManager, AnnotatedType<T> annotatedType,
            InjectionTarget<T> originalInjectionTarget) {
        this.beanManager = beanManager;
        this.annotatedType = annotatedType;
        this.originalInjectionTarget = originalInjectionTarget;

        this.injectionTargets = new ConcurrentHashMap<>();
        this.injectionTargets.put(ConfigProperties.UNCONFIGURED_PREFIX, originalInjectionTarget);

        this.syntheticInjectionPoint = new MetadataInjectionPoint();
    }

    void setBean(Bean<T> bean) {
        this.bean = bean;
    }

    @Override
    public T produce(CreationalContext<T> ctx) {
        final String prefix = getPrefix(ctx);
        final InjectionTarget<T> injectionTarget = getInjectionTargetForPrefix(prefix);
        return injectionTarget.produce(ctx);
    }

    @Override
    public void inject(T instance, CreationalContext<T> ctx) {
        final String prefix = getPrefix(ctx);
        final InjectionTarget<T> injectionTarget = getInjectionTargetForPrefix(prefix);
        injectionTarget.inject(instance, ctx);
    }

    @Override
    public void postConstruct(T instance) {
        originalInjectionTarget.postConstruct(instance);
    }

    @Override
    public void preDestroy(T instance) {
        originalInjectionTarget.preDestroy(instance);
    }

    @Override
    public void dispose(T instance) {
        originalInjectionTarget.dispose(instance);
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return originalInjectionTarget.getInjectionPoints();
    }

    /**
     * @param prefix the desired prefix
     * @return an {@link InjectionTarget} for the same underlying type, but all field injection points configured to
     *         have the given prefix instead of the prefix defined at class-level.
     */

    InjectionTarget<T> getInjectionTargetForPrefix(String prefix) {
        return injectionTargets.computeIfAbsent(prefix, this::createInjectionTarget);
    }

    private InjectionTarget<T> createInjectionTarget(String prefix) {
        InjectionTargetFactory<T> injectionTargetFactory = beanManager.getInjectionTargetFactory(annotatedType);
        AnnotatedTypeConfigurator<T> typeConfigurator = injectionTargetFactory.configure();

        String classLevelPrefix = annotatedType.getAnnotation(ConfigProperties.class).prefix();
        typeConfigurator.fields().forEach(f -> configureWithPrefix(f, classLevelPrefix, prefix));

        return injectionTargetFactory.createInjectionTarget(bean);
    }

    private <Y> void configureWithPrefix(AnnotatedFieldConfigurator<Y> f, String oldPrefix, String newPrefix) {
        final ConfigProperty configProperty = f.getAnnotated().getAnnotation(ConfigProperty.class);
        if (!f.getAnnotated().isAnnotationPresent(Inject.class) || configProperty == null) {
            // nothing to do
            return;
        }

        String newName = configProperty.name().replaceFirst("^" + oldPrefix, newPrefix);
        f.remove(q -> q.annotationType() == ConfigProperty.class)
                .add(new ConfigPropertyLiteral(newName, configProperty.defaultValue()));
    }

    private String getPrefix(CreationalContext<T> ctx) {
        final InjectionPoint injectionPoint = (InjectionPoint) beanManager.getInjectableReference(
                syntheticInjectionPoint, ctx);
        return getConfigPropertiesQualifier(injectionPoint.getQualifiers()).map(ConfigProperties::prefix)
                .orElse(ConfigProperties.UNCONFIGURED_PREFIX);
    }

    private Optional<ConfigProperties> getConfigPropertiesQualifier(final Set<Annotation> qualifiers) {
        return qualifiers.stream()
                .filter(ConfigProperties.class::isInstance)
                .map(ConfigProperties.class::cast)
                .findAny();
    }
}

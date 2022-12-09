package io.smallrye.config.inject;

import static io.smallrye.config.inject.KeyValuesConfigSource.config;
import static org.assertj.core.api.Assertions.assertThat;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

@ExtendWith(WeldJunit5Extension.class)
class ConfigPropertiesUnusualBeansTest {

    Weld weld = WeldInitiator.createWeld()
            .addExtension(new ConfigExtension())
            .addBeanClasses(FooBean.class, BazBean.class);

    @SuppressWarnings("unused")
    @WeldSetup
    WeldInitiator w = WeldInitiator.of(weld);

    @BeforeAll
    static void beforeAll() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withSources(config("foo.bar", "foobar"))
                .withSources(config("bar.bar", "barbar"))
                .withSources(config("baz.fizz", "fizz", "baz.buzz", "buzz"))
                .build();
        ConfigProviderResolver.instance().registerConfig(config, Thread.currentThread().getContextClassLoader());
    }

    @AfterAll
    static void afterAll() {
        ConfigProviderResolver.instance().releaseConfig(ConfigProvider.getConfig());
    }

    @Test
    void testClassWithoutNoArgsConstructor(@ConfigProperties FooBean fooWithoutPrefix) {
        assertThat(fooWithoutPrefix.bar).isEqualTo("foobar");
    }

    @Test
    void testClassWithoutNoArgsConstructorAndPrefix(@ConfigProperties(prefix = "bar") FooBean fooWithPrefix) {
        assertThat(fooWithPrefix.bar).isEqualTo("barbar");
    }

    @Test
    void testClassWithProducerMethod(@Default PairOfStrings pairOfStrings) {
        assertThat(pairOfStrings.left).isEqualTo("fizz");
        assertThat(pairOfStrings.right).isEqualTo("buzz");
    }

    @Dependent
    @ConfigProperties(prefix = "foo")
    static class FooBean {

        private String bar;

        @Inject
        public FooBean(BeanManager ignored) {
            // argument irrelevant, just needs to be present and a resolvable dependency
        }
    }

    @Dependent
    @ConfigProperties(prefix = "baz")
    static class BazBean {

        private String fizz;
        private String buzz;

        @Produces
        @Dependent
        PairOfStrings fizzBuzz() {
            return new PairOfStrings(fizz, buzz);
        }
    }

    static class PairOfStrings {

        final String left;
        final String right;

        PairOfStrings(String left, String right) {
            this.left = left;
            this.right = right;
        }
    }
}

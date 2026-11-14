package dev.liangwen.relay;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    static final String PEPPER="test-only-pepper-abcdefghijklmnopqrstuvwxyz",ADMIN="test-only-admin-abcdefghijklmnopqrstuvwxyz";
    static final RelaySettings.Price PRICE=new RelaySettings.Price("pinned-text-2026-01-01","price-v1",BigDecimal.ONE,new BigDecimal("0.5"),new BigDecimal("2"),100,65536);
    RelaySettings settings(String mode,String upstream,String provider,String pepper,String admin,List<RelaySettings.Price> prices){return new RelaySettings(mode,upstream,provider,pepper,admin,32768,1048576,2,prices,List.of());}
    @Test void productionOnlyAllowsExactOpenAiOriginAndExplicitPrices(){
        for(String uri:List.of("http://api.openai.com/v1","https://evil.test/v1","https://api.openai.com.evil.test/v1","https://api.openai.com/v1?target=x","https://api.openai.com:443/v1","http://127.0.0.1:19092/v1"))
            assertThatThrownBy(()->settings("production",uri,"fixture-provider",PEPPER,ADMIN,List.of(PRICE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings("production","https://api.openai.com/v1","fixture-provider",PEPPER,ADMIN,List.of(PRICE)).toString()).doesNotContain(PEPPER,ADMIN,"fixture-provider");
        assertThatThrownBy(()->settings("production","https://api.openai.com/v1","fixture-provider",PEPPER,ADMIN,List.of(new RelaySettings.Price("mock-text","mock-v1",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,100,65536)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mockOnlyAcceptsLiteralLoopbackAndIndependentSecrets(){
        for(String uri:List.of("https://api.openai.com/v1","http://localhost:19092/v1","http://127.0.0.1:19092/v1/","http://127.0.0.1:19092/v1#x"))
            assertThatThrownBy(()->settings("mock",uri,"fixture-provider",PEPPER,ADMIN,List.of(PRICE))).isInstanceOf(IllegalArgumentException.class);
        for(String bad:List.of("","short",ADMIN))assertThatThrownBy(()->settings("mock","http://127.0.0.1:19092/v1","fixture-provider",bad,ADMIN,List.of(PRICE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings("production","https://api.openai.com/v1","",PEPPER,ADMIN,List.of(PRICE))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownOrNegativeOrUnpricedModelsFailClosed(){
        assertThatThrownBy(()->settings("production","https://api.openai.com/v1","fixture-provider",PEPPER,ADMIN,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings("production","https://api.openai.com/v1","fixture-provider",PEPPER,ADMIN,List.of(new RelaySettings.Price("x","v1",BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("-1"),100,65536)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings("mock","http://127.0.0.1:19092/v1","fixture-provider",PEPPER,ADMIN,List.of(PRICE)).price("unpriced")).hasMessage("model_not_allowed");
    }
    MockEnvironment environment(){return new MockEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.forward-headers-strategy","none");}
    @Test void bindingForwardingAndDatabaseGuardPreventUnsafeProductionStartup(){
        var s=settings("production","https://api.openai.com/v1","fixture-provider",PEPPER,ADMIN,List.of(PRICE));
        assertThatThrownBy(()->new RuntimeGuard(environment().withProperty("server.address","0.0.0.0"),s)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RuntimeGuard(environment().withProperty("server.forward-headers-strategy","framework"),s)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RuntimeGuard(environment().withProperty("spring.datasource.url","jdbc:h2:mem:test"),s)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RuntimeGuard(environment().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1/auth_gateway"),s)).isInstanceOf(IllegalArgumentException.class);
        var env=environment().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1/api_relay").withProperty("spring.datasource.username","api_relay").withProperty("spring.datasource.password","test-only-password");
        assertThatCode(()->new RuntimeGuard(env,s)).doesNotThrowAnyException();
        env.setActiveProfiles("production");assertThatThrownBy(()->new RuntimeGuard(env,settings("mock","http://127.0.0.1:19092/v1","fixture-provider",PEPPER,ADMIN,List.of(PRICE)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsafeRuntimeFailsBeforeDatabaseMigrationConnects(){
        var connections=new java.util.concurrent.atomic.AtomicInteger();
        var datasource=new org.springframework.jdbc.datasource.AbstractDataSource(){
            public java.sql.Connection getConnection(){connections.incrementAndGet();throw new AssertionError("Unsafe configuration reached the database");}
            public java.sql.Connection getConnection(String username,String password){return getConnection();}
        };
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RelayApplication.class).withBean(javax.sql.DataSource.class,()->datasource)
            .withPropertyValues("server.address=0.0.0.0","relay.pepper="+PEPPER,"relay.admin-token="+ADMIN)
            .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Relay must bind loopback");assertThat(connections.get()).isZero();});
    }
}

package dev.liangwen.relay;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(RelaySettings.class)
public class RelayApplication {
    public static void main(String[] args) { SpringApplication.run(RelayApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean tools.jackson.databind.json.JsonMapper relayJson() {
        var factory=tools.jackson.core.json.JsonFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(16).maxNumberLength(64).maxStringLength(1048576).build()).build();
        return tools.jackson.databind.json.JsonMapper.builder(factory)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }
    @Bean @org.springframework.context.annotation.DependsOn("runtimeGuard") Flyway relayMigrations(DataSource ds) {
        Flyway f = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        f.migrate();
        return f;
    }
}

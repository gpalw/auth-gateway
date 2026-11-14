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
    @Bean Flyway relayMigrations(DataSource ds) {
        Flyway f = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        f.migrate();
        return f;
    }
}

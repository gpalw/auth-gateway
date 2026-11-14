package dev.liangwen.relay;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class RuntimeGuard {
    RuntimeGuard(Environment env,RelaySettings settings) {
        if (!"127.0.0.1".equals(env.getProperty("server.address"))
                || !"none".equals(env.getProperty("server.forward-headers-strategy")))
            throw new IllegalArgumentException("Relay must bind loopback with original transport addresses");
        boolean production=java.util.Arrays.asList(env.getActiveProfiles()).contains("production");
        if(production && !settings.mode().equals("production")) throw new IllegalArgumentException("Production profile requires production mode");
        if(settings.mode().equals("production")) {
            String url=env.getProperty("spring.datasource.url","");
            String user=env.getProperty("spring.datasource.username","");
            if(!url.matches("jdbc:postgresql://[^/]+/api_relay(?:\\?.*)?") || user.isBlank() || user.equals("sa") || user.equals("auth_gateway")
                    || env.getProperty("spring.datasource.password","").isBlank())
                throw new IllegalArgumentException("Production requires a dedicated api_relay PostgreSQL database and service account");
        }
    }
}

package dev.liangwen.authgateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionSafetyProperties.class)
class ProductionSafetyConfiguration {
}

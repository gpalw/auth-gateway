package dev.liangwen.authgateway.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeSummaryProperties.class)
class RuntimeSummaryConfiguration {
}

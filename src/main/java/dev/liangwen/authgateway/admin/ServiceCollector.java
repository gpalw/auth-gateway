package dev.liangwen.authgateway.admin;

import java.util.List;

@FunctionalInterface
public interface ServiceCollector {

    List<CollectedService> collect();
}

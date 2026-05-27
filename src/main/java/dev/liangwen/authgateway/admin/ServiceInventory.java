package dev.liangwen.authgateway.admin;

import java.time.Instant;
import java.util.List;

public record ServiceInventory(Instant checkedAt, List<ServiceInventoryItem> items, List<String> errors) {

    public ServiceInventory {
        items = List.copyOf(items);
        errors = List.copyOf(errors);
    }
}

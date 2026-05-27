package dev.liangwen.authgateway.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "admin.enabled", havingValue = "true")
public class AdminServicesController {

    private final ServiceInventoryService inventory;

    public AdminServicesController(ServiceInventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/admin")
    String adminRoot() {
        return "redirect:/admin/services";
    }

    @GetMapping("/admin/services")
    String services(Model model) {
        model.addAttribute("inventory", inventory.inventory());
        return "admin/services";
    }
}

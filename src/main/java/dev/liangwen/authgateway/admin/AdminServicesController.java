package dev.liangwen.authgateway.admin;

import dev.liangwen.authgateway.platform.PlatformRegistrationForm;
import dev.liangwen.authgateway.platform.PlatformRegistrationService;
import dev.liangwen.authgateway.ops.RuntimeSummaryService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@ConditionalOnProperty(name = "admin.enabled", havingValue = "true")
public class AdminServicesController {

    private final ServiceInventoryService inventory;
    private final PlatformRegistrationService platforms;
    private final RuntimeSummaryService runtimeSummary;

    public AdminServicesController(
            ServiceInventoryService inventory,
            PlatformRegistrationService platforms,
            RuntimeSummaryService runtimeSummary) {
        this.inventory = inventory;
        this.platforms = platforms;
        this.runtimeSummary = runtimeSummary;
    }

    @GetMapping("/admin")
    String adminRoot(Model model) {
        model.addAttribute("dashboard", new AdminDashboard(
                runtimeSummary.summary(),
                inventory.inventory(),
                platforms.allPlatforms()));
        return "admin/dashboard";
    }

    @GetMapping("/admin/services")
    String services(Model model) {
        model.addAttribute("inventory", inventory.inventory());
        return "admin/services";
    }

    @GetMapping("/admin/platforms")
    String platforms(Model model) {
        preparePlatformModel(model, PlatformRegistrationForm.empty(), "", "");
        return "admin/platforms";
    }

    @GetMapping("/admin/platforms/{id}/edit")
    String editPlatform(@PathVariable UUID id, Model model) {
        preparePlatformModel(model, PlatformRegistrationForm.from(platforms.get(id)), id.toString(), "");
        return "admin/platforms";
    }

    @PostMapping("/admin/platforms")
    String createPlatform(@ModelAttribute PlatformRegistrationForm form, Model model) {
        try {
            platforms.create(form);
        } catch (IllegalArgumentException ex) {
            form.clearClientSecret();
            preparePlatformModel(model, form, "", ex.getMessage());
            return "admin/platforms";
        }
        return "redirect:/admin/platforms";
    }

    @PostMapping("/admin/platforms/{id}")
    String updatePlatform(
            @PathVariable UUID id,
            @ModelAttribute PlatformRegistrationForm form,
            Model model) {
        try {
            platforms.update(id, form);
        } catch (IllegalArgumentException ex) {
            form.clearClientSecret();
            preparePlatformModel(model, form, id.toString(), ex.getMessage());
            return "admin/platforms";
        }
        return "redirect:/admin/platforms";
    }

    private void preparePlatformModel(
            Model model,
            PlatformRegistrationForm form,
            String editingId,
            String error) {
        model.addAttribute("platforms", platforms.allPlatforms());
        model.addAttribute("form", form);
        model.addAttribute("editingId", editingId);
        model.addAttribute("error", error);
    }
}

package dev.liangwen.authgateway.admin;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "admin.enabled=true",
        "admin.inventory.docker-enabled=false",
        "admin.inventory.systemd-enabled=false",
        "admin.inventory.ports-enabled=false",
        "admin.inventory.services[0].id=cv-home",
        "admin.inventory.services[0].name=CV Home",
        "admin.inventory.services[0].url=https://liangwendev.com",
        "admin.inventory.services[0].port=443",
        "admin.inventory.services[0].notes=public homepage",
        "identity.issuer=https://auth.example.com",
        "identity.apps[0].id=job-crm-local",
        "identity.apps[0].name=Job CRM",
        "identity.apps[0].description=Jobs",
        "identity.apps[0].url=https://jobs.example.com",
        "identity.apps[0].enabled=true",
        "identity.clients[0].client-id=job-crm-local",
        "identity.clients[0].client-secret=secret",
        "identity.clients[0].redirect-uris[0]=https://jobs.example.com/login/oauth2/code/auth-gateway",
        "identity.clients[0].post-logout-redirect-uris[0]=https://jobs.example.com"
})
@AutoConfigureMockMvc
class AdminServicesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersServiceInventoryWithoutGoogleLogin() throws Exception {
        mockMvc.perform(get("/admin/services"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Service Inventory")))
                .andExpect(content().string(containsString("Job CRM")))
                .andExpect(content().string(containsString("SSO")))
                .andExpect(content().string(containsString("CV Home")))
                .andExpect(content().string(containsString("public homepage")));
    }
}

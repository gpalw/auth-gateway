package dev.liangwen.authgateway.platform;

public class PlatformRegistrationForm {

    private String clientId;
    private String name;
    private String description;
    private String homeUrl;
    private String clientSecret;
    private String redirectUrisText;
    private String postLogoutRedirectUrisText;
    private boolean enabled = true;

    public PlatformRegistrationForm() {
    }

    public PlatformRegistrationForm(
            String clientId,
            String name,
            String description,
            String homeUrl,
            String clientSecret,
            String redirectUrisText,
            String postLogoutRedirectUrisText,
            boolean enabled) {
        this.clientId = clientId;
        this.name = name;
        this.description = description;
        this.homeUrl = homeUrl;
        this.clientSecret = clientSecret;
        this.redirectUrisText = redirectUrisText;
        this.postLogoutRedirectUrisText = postLogoutRedirectUrisText;
        this.enabled = enabled;
    }

    public static PlatformRegistrationForm empty() {
        return new PlatformRegistrationForm();
    }

    public static PlatformRegistrationForm from(PlatformRegistration registration) {
        return new PlatformRegistrationForm(
                registration.clientId(),
                registration.name(),
                registration.description(),
                registration.homeUrl(),
                "",
                String.join("\n", registration.redirectUris()),
                String.join("\n", registration.postLogoutRedirectUris()),
                registration.enabled());
    }

    public void clearClientSecret() {
        this.clientSecret = "";
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getHomeUrl() {
        return homeUrl;
    }

    public void setHomeUrl(String homeUrl) {
        this.homeUrl = homeUrl;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUrisText() {
        return redirectUrisText;
    }

    public void setRedirectUrisText(String redirectUrisText) {
        this.redirectUrisText = redirectUrisText;
    }

    public String getPostLogoutRedirectUrisText() {
        return postLogoutRedirectUrisText;
    }

    public void setPostLogoutRedirectUrisText(String postLogoutRedirectUrisText) {
        this.postLogoutRedirectUrisText = postLogoutRedirectUrisText;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

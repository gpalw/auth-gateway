package dev.liangwen.authgateway.ops;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
class AuthGatewayInfoContributor implements InfoContributor {

    private final RuntimeSummaryService runtimeSummary;

    AuthGatewayInfoContributor(RuntimeSummaryService runtimeSummary) {
        this.runtimeSummary = runtimeSummary;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("authGateway", runtimeSummary.summary());
    }
}

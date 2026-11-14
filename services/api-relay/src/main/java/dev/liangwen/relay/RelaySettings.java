package dev.liangwen.relay;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("relay")
public record RelaySettings(String mode, String upstream, String providerKey, String pepper, String adminToken,
                            int maxBodyBytes, int maxResponseBytes, int timeoutSeconds, List<Price> prices, List<String> trustedProxyIps) {
    public record Price(String model, String version, BigDecimal inputPerMillion, BigDecimal cachedPerMillion,
                        BigDecimal outputPerMillion, int maxOutput, int maxInput) {
        public BigDecimal cost(long input, long output, long cached) {
            return inputPerMillion.multiply(BigDecimal.valueOf(input-cached))
                .add(cachedPerMillion.multiply(BigDecimal.valueOf(cached)))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(output))).movePointLeft(6)
                .setScale(10, java.math.RoundingMode.CEILING);
        }
    }
    public RelaySettings {
        // Never include supplied configuration values in startup exceptions.
        if (!Set.of("mock", "production").contains(mode) || pepper == null || pepper.length() < 32
                || adminToken == null || adminToken.length() < 32 || Objects.equals(pepper, adminToken)
                || providerKey == null || providerKey.isBlank() || providerKey.equals(adminToken)
                || providerKey.equals(pepper)) throw new IllegalArgumentException("Relay secrets/mode must be configured separately");
        URI u;
        try { u=URI.create(upstream); } catch(Exception e) { throw new IllegalArgumentException("Invalid fixed upstream"); }
        boolean local = "http".equals(u.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(u.getHost())
                && "/v1".equals(u.getPath()) && u.getPort()>0;
        if (u.getRawQuery()!=null || u.getRawFragment()!=null || u.getRawUserInfo()!=null
                || ("production".equals(mode) ? !"https://api.openai.com/v1".equals(upstream) : !local))
            throw new IllegalArgumentException("Only fixed OpenAI production or literal loopback mock upstream is allowed");
        if (maxBodyBytes<1024 || maxBodyBytes>1048576 || maxResponseBytes<1024 || maxResponseBytes>8388608
                || timeoutSeconds<1 || timeoutSeconds>120 || prices==null || prices.isEmpty())
            throw new IllegalArgumentException("Invalid relay limits/prices");
        Set<String> names=new HashSet<>();
        for (Price p:prices) {
            if (p.model()==null || !p.model().matches("[a-zA-Z0-9._-]{1,100}") || !names.add(p.model())
                    || p.version()==null || !p.version().matches("[a-zA-Z0-9._-]{1,80}")
                    || p.inputPerMillion()==null || p.cachedPerMillion()==null || p.outputPerMillion()==null
                    || p.inputPerMillion().signum()<=0 || p.cachedPerMillion().signum()<0 || p.outputPerMillion().signum()<=0
                    || p.cachedPerMillion().compareTo(p.inputPerMillion())>0
                    || p.inputPerMillion().compareTo(new BigDecimal("10000"))>0 || p.outputPerMillion().compareTo(new BigDecimal("10000"))>0
                    || p.maxOutput()<1 || p.maxOutput()>100000 || p.maxInput()<1 || p.maxInput()>2000000
                    || ("production".equals(mode) && (p.model().startsWith("mock") || p.version().startsWith("mock"))))
                throw new IllegalArgumentException("Every model needs an explicit bounded, versioned price schedule");
        }
        prices=List.copyOf(prices);
        trustedProxyIps=trustedProxyIps==null?List.of():List.copyOf(trustedProxyIps);
        trustedProxyIps.forEach(SourcePolicy::address);
    }
    public Price price(String model) {
        return prices.stream().filter(p->p.model().equals(model)).findFirst().orElseThrow(()->new RelayError(403,"model_not_allowed"));
    }
}

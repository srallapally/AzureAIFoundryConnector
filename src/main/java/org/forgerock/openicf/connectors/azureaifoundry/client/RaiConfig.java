package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RaiConfig {
    public String rai_policy_name;

    public String getRaiPolicyName() {
        return rai_policy_name;
    }
}

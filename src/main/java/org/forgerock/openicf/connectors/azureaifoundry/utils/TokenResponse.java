package org.forgerock.openicf.connectors.azureaifoundry.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenResponse {
    public String token_type;
    public String access_token;
    public Long expires_in;
}

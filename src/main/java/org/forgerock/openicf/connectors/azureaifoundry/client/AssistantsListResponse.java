package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantsListResponse {

    public List<AgentListItem> data;
    public String first_id;
    public String last_id;
    public Boolean has_more;
    public String object;

    public List<AzureAgentDescriptor> toDescriptors() {
        if (data == null) {
            return Collections.emptyList();
        }
        List<AzureAgentDescriptor> res = new ArrayList<>(data.size());
        for (AgentListItem item : data) {
            AzureAgentDescriptor d = item.toDescriptor();
            if (d != null) {
                res.add(d);
            }
        }
        return res;
    }
}

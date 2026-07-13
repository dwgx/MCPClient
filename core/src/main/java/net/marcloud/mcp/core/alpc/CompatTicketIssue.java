package net.marcloud.mcp.core.alpc;

import java.util.List;
import java.util.Map;

/** Authority result for {@code compatTicket}: tickets issued NOW + optional reasons. */
public record CompatTicketIssue(
        List<CompatTicket> tickets,
        Map<String, String> reasons) {

    public static CompatTicketIssue empty() {
        return new CompatTicketIssue(List.of(), Map.of());
    }
}

package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import java.util.List;

/** {@code ComboListResponse} of the contract: the page and the pagination meta. */
record ComboListResponse(List<ComboResponse> data, Meta meta) {

    record Meta(int page, int limit, long total, long totalPages) {
    }

    static ComboListResponse of(ComboPage page, int number, int limit) {
        long totalPages = (page.total() + limit - 1) / limit;
        return new ComboListResponse(page.items().stream().map(ComboResponse::of).toList(),
                new Meta(number, limit, page.total(), totalPages));
    }
}

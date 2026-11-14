package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import java.util.List;

/** {@code ProductListResponse} of the contract: the page and the pagination meta. */
record ProductListResponse(List<ProductResponse> data, Meta meta) {

    record Meta(int page, int limit, long total, long totalPages) {
    }

    static ProductListResponse of(ProductPage page, int number, int limit) {
        long totalPages = (page.total() + limit - 1) / limit;
        return new ProductListResponse(page.items().stream().map(ProductResponse::of).toList(),
                new Meta(number, limit, page.total(), totalPages));
    }
}

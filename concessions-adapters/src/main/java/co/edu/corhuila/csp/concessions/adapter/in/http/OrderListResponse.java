package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import java.util.List;

/** {@code OrderListResponse} of the contract: the page and the pagination meta. */
record OrderListResponse(List<OrderResponse> data, Meta meta) {

    record Meta(int page, int limit, long total, long totalPages) {
    }

    static OrderListResponse of(OrderPage page, int number, int limit) {
        long totalPages = (page.total() + limit - 1) / limit;
        return new OrderListResponse(page.items().stream().map(OrderResponse::of).toList(),
                new Meta(number, limit, page.total(), totalPages));
    }
}

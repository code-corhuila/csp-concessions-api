package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.CreateProductInput;
import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.domain.model.InvalidProductException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The three product routes of {@code concessions-service.yaml}, with their contract answers. */
class ProductControllerTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String KEY = "0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BODY = "{\"name\":\"Popcorn\",\"description\":\"Large\",\"category\":\"SNACK\","
            + "\"price\":1500,\"availableStock\":10}";
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private ProductUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(ProductUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(useCases))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CallerArgumentResolver())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static Product product(ProductStatus status) {
        return new Product(UUID.fromString("22222222-2222-4222-8222-222222222222"), "Popcorn", "Large", "SNACK",
                1500, 10, status, NOW, NOW);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of("concessions:product:write"))
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
        return request.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of())
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder create(MockHttpServletRequestBuilder base, String body) {
        return base.header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void anAdministratorCreatesADraftProductAndGets201() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenReturn(new CreateProductResult(product(ProductStatus.DRAFT), false));

        mockMvc.perform(create(asAdmin(post("/products")), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("22222222-2222-4222-8222-222222222222"))
                .andExpect(jsonPath("$.name").value("Popcorn"))
                .andExpect(jsonPath("$.price").value(1500))
                .andExpect(jsonPath("$.availableStock").value(10))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        ArgumentCaptor<CreateProductInput> input = ArgumentCaptor.forClass(CreateProductInput.class);
        verify(useCases).create(input.capture(), eq(KEY));
        assertEquals(new CreateProductInput("Popcorn", "Large", "SNACK", 1500, 10), input.getValue());
    }

    @Test
    void aKeyThatAlreadyCreatedTheProductAnswers200WithTheOriginal() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenReturn(new CreateProductResult(product(ProductStatus.DRAFT), true));

        mockMvc.perform(create(asAdmin(post("/products")), BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("22222222-2222-4222-8222-222222222222"));
    }

    @Test
    void aMissingCategoryTakesTheDefaultOfTheService() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenReturn(new CreateProductResult(product(ProductStatus.DRAFT), false));

        mockMvc.perform(create(asAdmin(post("/products")), "{\"name\":\"Water\",\"price\":0}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateProductInput> input = ArgumentCaptor.forClass(CreateProductInput.class);
        verify(useCases).create(input.capture(), eq(KEY));
        assertEquals("UNCATEGORIZED", input.getValue().category());
        assertEquals(0, input.getValue().availableStock());
    }

    @Test
    void aClientCannotCreateAProduct() throws Exception {
        mockMvc.perform(create(asClient(post("/products")), BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aBodyWithoutNameOrPriceIsA400ThatNamesTheFields() throws Exception {
        mockMvc.perform(create(asAdmin(post("/products")), "{\"category\":\"SNACK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(CORRELATION));

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aNegativePriceIsA400AndNoProductIsCreated() throws Exception {
        mockMvc.perform(create(asAdmin(post("/products")),
                        "{\"name\":\"Soda\",\"category\":\"DRINK\",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("price"));

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aRuleOfTheDomainThatTheDtoDoesNotCoverIsAlsoA400() throws Exception {
        when(useCases.create(any(), any())).thenThrow(new InvalidProductException("the name has at most 160 characters"));

        mockMvc.perform(create(asAdmin(post("/products")), "{\"name\":\"Popcorn\",\"price\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("the name has at most 160 characters"));
    }

    @Test
    void aKeyUsedWithAnotherBodyIsA409() throws Exception {
        when(useCases.create(any(), any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(create(asAdmin(post("/products")), BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void aRequestWithoutTheKeyIsA400() throws Exception {
        mockMvc.perform(asAdmin(post("/products")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void anAdministratorListsEveryStatusWithThePaginationMeta() throws Exception {
        when(useCases.list(1, 20, false)).thenReturn(
                new ProductPage(List.of(product(ProductStatus.DRAFT), product(ProductStatus.PUBLISHED)), 41));

        mockMvc.perform(asAdmin(get("/products")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20))
                .andExpect(jsonPath("$.meta.total").value(41))
                .andExpect(jsonPath("$.meta.totalPages").value(3));
    }

    @Test
    void aClientListsOnlyThePublishedProducts() throws Exception {
        when(useCases.list(2, 5, true)).thenReturn(new ProductPage(List.of(product(ProductStatus.PUBLISHED)), 6));

        mockMvc.perform(asClient(get("/products")).param("page", "2").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

        verify(useCases, never()).list(anyInt(), anyInt(), eq(false));
    }

    @Test
    void aLimitOutsideTheBoundsOfTheContractIsA400() throws Exception {
        when(useCases.list(anyInt(), eq(101), anyBoolean())).thenThrow(new IllegalArgumentException("limit"));

        mockMvc.perform(asAdmin(get("/products")).param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void anAdministratorPublishesAProduct() throws Exception {
        UUID id = product(ProductStatus.DRAFT).id();
        when(useCases.changeStatus(id, ProductStatus.PUBLISHED)).thenReturn(product(ProductStatus.PUBLISHED));

        mockMvc.perform(asAdmin(patch("/products/" + id)).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void aClientCannotChangeTheStatus() throws Exception {
        mockMvc.perform(asClient(patch("/products/" + UUID.randomUUID())).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());

        verify(useCases, never()).changeStatus(any(), any());
    }

    @Test
    void aTransitionOutsideTheLifecycleIsA409() throws Exception {
        UUID id = UUID.randomUUID();
        when(useCases.changeStatus(id, ProductStatus.INACTIVE))
                .thenThrow(new InvalidStatusTransitionException("only a published product can be deactivated"));

        mockMvc.perform(asAdmin(patch("/products/" + id)).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void anUnknownProductIsA404() throws Exception {
        UUID id = UUID.randomUUID();
        when(useCases.changeStatus(id, ProductStatus.PUBLISHED))
                .thenThrow(new ProductNotFoundException("the product does not exist"));

        mockMvc.perform(asAdmin(patch("/products/" + id)).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aStatusThatDoesNotExistIsA400() throws Exception {
        mockMvc.perform(asAdmin(patch("/products/" + UUID.randomUUID())).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(useCases, never()).changeStatus(any(), any());
    }
}

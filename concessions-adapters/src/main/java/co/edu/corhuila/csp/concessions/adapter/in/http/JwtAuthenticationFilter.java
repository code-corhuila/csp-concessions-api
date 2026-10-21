package co.edu.corhuila.csp.concessions.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token of every protected request by itself, as the norm demands
 * (5.3.7): RS256 verified with the public key of the identity service, any other algorithm
 * rejected, {@code exp} and {@code sub} mandatory, and the user of the request is the {@code sub}.
 * A rejected token answers the envelope of the contract and never reaches a controller.
 * <p>The health routes are the only public ones, as {@code concessions-service.yaml} declares.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION_HEADER = "Authorization";
    /** Request attribute with the authenticated user: the {@code sub} of the token. */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String BEARER = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/health/ready");

    private static final String INTERNAL_PREFIX = "/internal/";

    private final RSAPublicKey publicKey;
    private final ObjectMapper objectMapper;
    private final Set<String> serviceSubjects;

    /**
     * @param serviceSubjects the {@code sub} of the service tokens that may call the maintenance
     *     operations under {@code /internal/} (ADR-020, {@code SERVICE_SUBJECTS}); a client token never may
     */
    public JwtAuthenticationFilter(RSAPublicKey jwtPublicKey, ObjectMapper objectMapper,
            @Value("${SERVICE_SUBJECTS:csp-worker}") Set<String> serviceSubjects) {
        this.publicKey = jwtPublicKey;
        this.objectMapper = objectMapper;
        this.serviceSubjects = Set.copyOf(serviceSubjects);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathAfterContextPath(request);
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication token required");
            return;
        }
        Optional<String> userId = userIdOf(header.substring(BEARER.length()).trim());
        if (userId.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "the token is invalid or expired");
            return;
        }
        // An internal operation takes only a service token, and a service token takes only internal
        // operations: a valid token of the wrong kind is a 403, not a 401 (ADR-020).
        boolean internal = path != null && path.startsWith(INTERNAL_PREFIX);
        if (internal ? !serviceSubjects.contains(userId.get()) : serviceSubjects.contains(userId.get())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "the token is not allowed to call this operation");
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        chain.doFilter(request, response);
    }

    /** The {@code sub} of an acceptable token; empty when any rule of 5.3.7 is broken. */
    Optional<String> userIdOf(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException exception) {
            logger.debug("rejected token: it is not a signed JWT");
            return Optional.empty();
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            logger.debug("rejected token: the algorithm is not RS256");
            return Optional.empty();
        }
        try {
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                logger.debug("rejected token: the signature does not match the key of this service");
                return Optional.empty();
            }
        } catch (JOSEException exception) {
            logger.debug("rejected token: the signature cannot be verified");
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException exception) {
            logger.debug("rejected token: its claims cannot be read");
            return Optional.empty();
        }
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null) {
            logger.debug("rejected token: it has no exp claim");
            return Optional.empty();
        }
        if (!expiresAt.after(new Date())) {
            logger.debug("rejected token: it has expired");
            return Optional.empty();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            logger.debug("rejected token: it has no sub claim");
            return Optional.empty();
        }
        if (!isServiceSubject(subject) && !isId(subject)) {
            logger.debug("rejected token: its sub is not the id of a user of this platform");
            return Optional.empty();
        }
        return Optional.of(subject);
    }

    private boolean isServiceSubject(String subject) {
        return serviceSubjects.contains(subject);
    }

    /**
     * The {@code sub} is the id of the user in every table of this platform (the gateway also
     * forwards it as {@code X-User-Id}), so a token whose subject is not one is not acceptable
     * here: the controllers can then read the attribute as an id without a second check.
     */
    private static boolean isId(String subject) {
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** The route without the context path, so the check works behind and without the gateway. */
    private String pathAfterContextPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null || context == null || context.isEmpty()) {
            return uri;
        }
        return uri.substring(context.length());
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
    }
}

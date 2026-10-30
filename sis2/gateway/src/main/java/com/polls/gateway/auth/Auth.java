package com.polls.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.Headers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies HS256 JWTs at the edge. Services never see the token, only the user_id
 * taken from it: a client can't vote as someone else by editing the body.
 */
@Component
public class Auth {
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final boolean testHeaderEnabled;
    private final ObjectMapper json;

    public Auth(@Value("${auth.jwt-secret}") String secret,
                @Value("${auth.test-header-enabled}") boolean testHeaderEnabled,
                ObjectMapper json) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.testHeaderEnabled = testHeaderEnabled;
        this.json = json;
    }

    public long userId(HttpServletRequest request) {
        if (testHeaderEnabled && request.getHeader(Headers.USER_ID) != null) {
            try {
                return Long.parseLong(request.getHeader(Headers.USER_ID));
            } catch (NumberFormatException e) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "bad X-User-Id");
            }
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "missing Bearer token");
        }
        return verify(header.substring(7).trim());
    }

    long verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new ApiException(ErrorCode.UNAUTHENTICATED, "malformed token");
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = B64.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "malformed signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "invalid signature");
        }
        try {
            JsonNode head = json.readTree(B64.decode(parts[0]));
            if (!"HS256".equals(head.path("alg").asText())) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "unsupported alg");
            }
            JsonNode claims = json.readTree(B64.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (exp != 0 && exp < System.currentTimeMillis() / 1000) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "token expired");
            }
            long sub = Long.parseLong(claims.path("sub").asText());
            if (sub <= 0) throw new NumberFormatException();
            return sub;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "invalid token claims");
        }
    }

    public String issue(long userId, long ttlSeconds) {
        String head = B64E.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String body = B64E.encodeToString(("{\"sub\":\"" + userId + "\",\"exp\":" + exp + "}")
                .getBytes(StandardCharsets.UTF_8));
        return head + "." + body + "." + B64E.encodeToString(hmac(head + "." + body));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

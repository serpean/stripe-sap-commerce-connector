package com.stripe.stripe;

import com.stripe.core.stripe.StripeClient;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.StripeException;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class StripeClientTest {
    private TestableStripeClient stripeClient;

    @Before
    public void setUp() {
        stripeClient = new TestableStripeClient();
    }

    @Test
    public void shouldSanitizeMetadataAndTrimValues() {
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("key", "value");
        metadata.put("empty", null);
        metadata.put("", "missingKey");

        final Map<String, String> sanitized = stripeClient.publicSanitizeMetadata(metadata);

        assertEquals("value", sanitized.get("key"));
        assertFalse(sanitized.containsKey("empty"));
        assertFalse(sanitized.containsKey(""));
    }

    @Test
    public void shouldReturnFalseWhenStatusCodeNullOnRetry() {
        final StripeException exception = new AuthenticationException("error", null, null, null, null);

        assertFalse(stripeClient.publicShouldRetry(exception));
    }

    @Test
    public void shouldReturnTrueFor429OnRetry() {
        final StripeException exception = new AuthenticationException("rate limit", null, null, 429, null);

        assertTrue(stripeClient.publicShouldRetry(exception));
    }

    @Test
    public void shouldGenerateIdempotencyKeyWithReference() {
        final String key = stripeClient.publicGenerateIdempotencyKey("order123", "intent-create");
        assertTrue(key.startsWith("order123-intent-create"));
        assertTrue(key.length() <= 255);
    }

    private static class TestableStripeClient extends StripeClient {
        public Map<String, String> publicSanitizeMetadata(final Map<String, String> metadata) {
            return sanitizeMetadata(metadata);
        }

        public boolean publicShouldRetry(final StripeException exception) {
            return shouldRetry(exception);
        }

        public String publicGenerateIdempotencyKey(final String reference, final String operation) {
            return generateIdempotencyKey(reference, operation);
        }
    }
}

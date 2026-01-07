package com.stripe.provider;

import static org.junit.Assert.assertEquals;

import com.stripe.core.stripe.StripePaymentProvider;
import de.hybris.platform.payment.dto.TransactionStatus;
import org.junit.Before;
import org.junit.Test;

public class StripePaymentProviderTest
{
    private TestableStripePaymentProvider provider;

    @Before
    public void setUp()
    {
        provider = new TestableStripePaymentProvider();
    }

    @Test
    public void shouldMapIntentStatusToAccepted()
    {
        assertEquals(TransactionStatus.ACCEPTED, provider.publicMapIntentStatus("succeeded"));
        assertEquals(TransactionStatus.ACCEPTED, provider.publicMapIntentStatus("requires_capture"));
    }

    @Test
    public void shouldMapRefundStatusToRejected()
    {
        assertEquals(TransactionStatus.REJECTED, provider.publicMapRefundStatus("failed"));
    }

    private static class TestableStripePaymentProvider extends StripePaymentProvider
    {
        public TransactionStatus publicMapIntentStatus(final String status)
        {
            return mapIntentStatus(status);
        }

        public TransactionStatus publicMapRefundStatus(final String status)
        {
            return mapRefundStatus(status);
        }
    }
}

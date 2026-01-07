package com.stripe.controllers;

import com.stripe.dto.StripeIntentWsDTO;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.facades.data.StripeIntentData;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

public class StripePaymentControllerTest {
    private StripePaymentController controller;
    private StripePaymentFacade facade;

    @Before
    public void setUp() {
        controller = new StripePaymentController();
        facade = Mockito.mock(StripePaymentFacade.class);
        controller.setStripePaymentFacade(facade);
    }

    @Test
    public void shouldReturnAcceptedOnIntent() throws Exception {
        final StripeIntentData data = new StripeIntentData();
        data.setIntentId("pi_123");
        data.setClientSecret("secret");
        data.setStatus("requires_action");
        Mockito.when(facade.getIntentForSession()).thenReturn(data);

        final StripeIntentWsDTO response = controller.createOrUpdateIntent();

        assertEquals("pi_123", response.getIntentId());
        assertEquals("secret", response.getClientSecret());
        assertEquals("requires_action", response.getStatus());
    }
}

package com.stripe.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripeIntentData;
import com.stripe.core.payment.StripePaymentInProgressException;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.dto.StripeConfigWsDTO;
import com.stripe.dto.StripeIntentWsDTO;
import com.stripe.errors.StripeOccExceptionHandler;
import com.stripe.facades.checkout.StripeCheckoutException;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercewebservicescommons.errors.exceptions.CartException;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.CartService;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

@UnitTest
public class StripePaymentControllerTest
{
	private StripePaymentIntentService paymentIntentService;
	private StripeConfigurationService configurationService;
	private CartService cartService;
	private CartModel cart;
	private StripePaymentController controller;

	@Before
	public void setUp()
	{
		paymentIntentService = mock(StripePaymentIntentService.class);
		configurationService = mock(StripeConfigurationService.class);
		cartService = mock(CartService.class);
		cart = mock(CartModel.class);
		when(cartService.hasSessionCart()).thenReturn(true);
		when(cartService.getSessionCart()).thenReturn(cart);

		controller = new StripePaymentController();
		controller.setStripePaymentIntentService(paymentIntentService);
		controller.setStripeConfigurationService(configurationService);
		controller.setCartService(cartService);
	}

	@Test
	public void intentEndpointReturnsClientSecretWithoutStoringIt()
	{
		when(paymentIntentService.prepareForCart(cart))
				.thenReturn(new StripeIntentData("pi_1", "pi_1_secret_x", "requires_payment_method"));

		final StripeIntentWsDTO response = controller.prepareIntent();

		assertEquals("pi_1", response.getPaymentIntentId());
		assertEquals("pi_1_secret_x", response.getClientSecret());
		assertEquals("requires_payment_method", response.getStatus());
		verify(paymentIntentService).prepareForCart(cart);
		verifyNoInteractions(cart);
	}

	@Test(expected = CartException.class)
	public void intentEndpointNeedsSessionCart()
	{
		when(cartService.hasSessionCart()).thenReturn(false);

		controller.prepareIntent();
	}

	@Test
	public void configEndpointReturnsPublishableKeyForSite()
	{
		when(configurationService.getPublishableKey()).thenReturn("pk_test_site");

		final StripeConfigWsDTO response = controller.getConfig();

		assertEquals("pk_test_site", response.getPublishableKey());
	}

	@Test
	public void checkoutExceptionMapsToStableErrorCode() throws Exception
	{
		final MockHttpServletResponse response = resolveOnForeignController(
				new StripeCheckoutException(StripeCheckoutException.Reason.AMOUNT_MISMATCH, "cart 1 total 10.00 != pi_1 9.00"));

		assertEquals(400, response.getStatus());
		final String body = response.getContentAsString();
		assertTrue(body, body.contains("\"type\":\"StripePaymentError\""));
		assertTrue(body, body.contains("\"reason\":\"STRIPE_AMOUNT_MISMATCH\""));
		assertFalse(body, body.contains("pi_1"));
	}

	@Test
	public void handlerLeavesOtherExceptionsToSap() throws Exception
	{
		assertNull(adviceResolver().resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(),
				foreignHandler(), new IllegalStateException("other")));
	}

	@Test
	public void paymentNotFoundMapsToPaymentFailed() throws Exception
	{
		final MockHttpServletResponse response = resolveOnForeignController(
				new StripeCheckoutException(StripeCheckoutException.Reason.PAYMENT_NOT_FOUND, "no pi"));

		assertEquals(400, response.getStatus());
		assertTrue(response.getContentAsString().contains("\"reason\":\"STRIPE_PAYMENT_FAILED\""));
	}

	@Test
	public void alreadyPlacedMapsToAlreadyPlaced() throws Exception
	{
		final MockHttpServletResponse response = resolveOnForeignController(
				new StripeCheckoutException(StripeCheckoutException.Reason.ALREADY_PLACED, "locked"));

		assertEquals(400, response.getStatus());
		assertTrue(response.getContentAsString().contains("\"reason\":\"STRIPE_ALREADY_PLACED\""));
	}

	@Test
	public void gatewayErrorMapsToUnavailable() throws Exception
	{
		final MockHttpServletResponse response = resolveOnForeignController(
				new StripeGatewayException("Stripe said: secret detail", "api_error", true, false, null));

		assertEquals(503, response.getStatus());
		final String body = response.getContentAsString();
		assertTrue(body, body.contains("\"reason\":\"STRIPE_UNAVAILABLE\""));
		assertFalse(body, body.contains("secret detail"));
	}

	@Test
	public void paymentInProgressMapsToPaymentInProgress() throws Exception
	{
		final MockHttpServletResponse response = resolveOnForeignController(new StripePaymentInProgressException("pi_1"));

		assertEquals(400, response.getStatus());
		assertTrue(response.getContentAsString().contains("\"reason\":\"STRIPE_PAYMENT_IN_PROGRESS\""));
	}

	/**
	 * Resolves the exception the way SAP's OCC WebConfig does: first an {@link ExceptionHandlerExceptionResolver} that
	 * knows the advice beans of the context, then SAP's REST resolver. The handler is a controller of another package,
	 * like SAP's OrdersController.
	 */
	private MockHttpServletResponse resolveOnForeignController(final Exception exception) throws Exception
	{
		final HandlerExceptionResolver sapRestResolver = mock(HandlerExceptionResolver.class);
		final HandlerExceptionResolverComposite resolvers = new HandlerExceptionResolverComposite();
		resolvers.setExceptionResolvers(List.of(adviceResolver(), sapRestResolver));

		final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/electronics/users/current/orders");
		request.addHeader("Accept", "application/json");
		final MockHttpServletResponse response = new MockHttpServletResponse();

		assertNotNull(resolvers.resolveException(request, response, foreignHandler(), exception));
		verifyNoInteractions(sapRestResolver);
		return response;
	}

	private static ExceptionHandlerExceptionResolver adviceResolver()
	{
		final StaticWebApplicationContext context = new StaticWebApplicationContext();
		context.registerSingleton("stripeOccExceptionHandler", StripeOccExceptionHandler.class);
		context.refresh();
		final ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
		resolver.setApplicationContext(context);
		resolver.setMessageConverters(List.of(new MappingJackson2HttpMessageConverter()));
		resolver.afterPropertiesSet();
		return resolver;
	}

	private static HandlerMethod foreignHandler() throws NoSuchMethodException
	{
		return new HandlerMethod(new ForeignOrdersController(), ForeignOrdersController.class.getMethod("placeOrder"));
	}

	public static class ForeignOrdersController
	{
		public Object placeOrder()
		{
			return null;
		}
	}
}

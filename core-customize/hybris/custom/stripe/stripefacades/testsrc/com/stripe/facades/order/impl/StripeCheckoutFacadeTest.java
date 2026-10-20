package com.stripe.facades.order.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercefacades.order.CartFacade;
import de.hybris.platform.commercefacades.order.impl.DefaultCheckoutFacade;
import de.hybris.platform.commerceservices.strategies.CheckoutCustomerStrategy;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.CartService;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeCheckoutFacadeTest
{
	private StripeCheckoutAuthorizer authorizer;
	private CheckoutCustomerStrategy checkoutCustomerStrategy;
	private CartModel cart;
	private CustomerModel customer;
	private StripeCheckoutFacade checkoutFacade;
	private StripeAcceleratorCheckoutFacade acceleratorCheckoutFacade;

	@Before
	public void setUp()
	{
		authorizer = mock(StripeCheckoutAuthorizer.class);
		checkoutCustomerStrategy = mock(CheckoutCustomerStrategy.class);
		customer = new CustomerModel();
		cart = new CartModel();
		cart.setUser(customer);
		when(checkoutCustomerStrategy.getCurrentUserForCheckout()).thenReturn(customer);
		when(authorizer.authorize(cart)).thenReturn(true);

		checkoutFacade = new StripeCheckoutFacade();
		checkoutFacade.setStripeCheckoutAuthorizer(authorizer);
		acceleratorCheckoutFacade = new StripeAcceleratorCheckoutFacade();
		acceleratorCheckoutFacade.setStripeCheckoutAuthorizer(authorizer);
		withSessionCart(checkoutFacade);
		withSessionCart(acceleratorCheckoutFacade);
	}

	@Test
	public void stripeCartIsAuthorizedByStripeAuthorizer()
	{
		cart.setPaymentInfo(new StripePaymentInfoModel());

		assertTrue(checkoutFacade.authorizePayment(null));

		verify(authorizer).authorize(cart);
	}

	@Test
	public void stripeCartOfAnotherUserIsNotAuthorized()
	{
		cart.setPaymentInfo(new StripePaymentInfoModel());
		when(checkoutCustomerStrategy.getCurrentUserForCheckout()).thenReturn(new CustomerModel());

		assertFalse(checkoutFacade.authorizePayment(null));

		verify(authorizer, never()).authorize(any(CartModel.class));
	}

	@Test
	public void creditCardCartUsesDefaultAuthorization()
	{
		cart.setPaymentInfo(new CreditCardPaymentInfoModel());

		assertFalse(checkoutFacade.authorizePayment("123"));

		verify(authorizer, never()).authorize(any(CartModel.class));
	}

	@Test
	public void acceleratorStripeCartIsAuthorizedByStripeAuthorizer()
	{
		cart.setPaymentInfo(new StripePaymentInfoModel());

		assertTrue(acceleratorCheckoutFacade.authorizePayment(null));

		verify(authorizer).authorize(cart);
	}

	@Test
	public void acceleratorCreditCardCartUsesDefaultAuthorization()
	{
		cart.setPaymentInfo(new CreditCardPaymentInfoModel());

		assertFalse(acceleratorCheckoutFacade.authorizePayment("123"));

		verify(authorizer, never()).authorize(any(CartModel.class));
	}

	private void withSessionCart(final DefaultCheckoutFacade facade)
	{
		final CartFacade cartFacade = mock(CartFacade.class);
		final CartService cartService = mock(CartService.class);
		when(cartFacade.hasSessionCart()).thenReturn(true);
		when(cartService.getSessionCart()).thenReturn(cart);
		facade.setCartFacade(cartFacade);
		facade.setCartService(cartService);
		facade.setCheckoutCustomerStrategy(checkoutCustomerStrategy);
	}
}

package com.stripe.facades.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import com.stripe.facades.checkout.StripeCheckoutException;
import com.stripe.facades.order.StripePlaceOrderGuard;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.acceleratorservices.uiexperience.UiExperienceService;
import de.hybris.platform.commercefacades.order.CartFacade;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.commercefacades.order.impl.DefaultCheckoutFacade;
import de.hybris.platform.commerceservices.enums.UiExperienceLevel;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.service.data.CommerceOrderResult;
import de.hybris.platform.commerceservices.strategies.CheckoutCustomerStrategy;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.order.InvalidCartException;
import de.hybris.platform.servicelayer.dto.converter.Converter;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeCheckoutFacadeTest
{
	private StripeCheckoutAuthorizer authorizer;
	private StripeOrderPlacementLockService lockService;
	private StripePaymentIntentRecordService recordService;
	private CommerceCheckoutService commerceCheckoutService;
	private CheckoutCustomerStrategy checkoutCustomerStrategy;
	private CartModel cart;
	private CustomerModel customer;
	private StripeCheckoutFacade checkoutFacade;
	private StripeAcceleratorCheckoutFacade acceleratorCheckoutFacade;
	@SuppressWarnings("unchecked")
	private final Converter<OrderModel, OrderData> orderConverter = mock(Converter.class);

	@Before
	public void setUp()
	{
		authorizer = mock(StripeCheckoutAuthorizer.class);
		lockService = mock(StripeOrderPlacementLockService.class);
		recordService = mock(StripePaymentIntentRecordService.class);
		final StripePlaceOrderGuard guard = new StripePlaceOrderGuard();
		guard.setStripeOrderPlacementLockService(lockService);
		guard.setStripePaymentIntentRecordService(recordService);
		final StripeGateway gateway = mock(StripeGateway.class);
		final PaymentIntent paymentIntent = new PaymentIntent();
		paymentIntent.setAmount(1999L);
		paymentIntent.setCurrency("eur");
		when(gateway.retrievePaymentIntent(anyString())).thenReturn(paymentIntent);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		when(converter.chargeAmountMinor(any(OrderModel.class))).thenReturn(1999L);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(converter);
		guard.setStripeGateway(gateway);
		guard.setStripePaymentIntentChecks(checks);
		guard.setStripeAmountConverter(converter);
		guard.setStripeOrderCancellationService(mock(StripeOrderCancellationService.class));
		commerceCheckoutService = mock(CommerceCheckoutService.class);
		checkoutCustomerStrategy = mock(CheckoutCustomerStrategy.class);
		customer = new CustomerModel();
		cart = new CartModel();
		cart.setUser(customer);
		when(checkoutCustomerStrategy.getCurrentUserForCheckout()).thenReturn(customer);
		when(authorizer.authorize(cart)).thenReturn(true);

		checkoutFacade = new StripeCheckoutFacade();
		checkoutFacade.setStripeCheckoutAuthorizer(authorizer);
		checkoutFacade.setStripePlaceOrderGuard(guard);
		acceleratorCheckoutFacade = new StripeAcceleratorCheckoutFacade();
		acceleratorCheckoutFacade.setStripeCheckoutAuthorizer(authorizer);
		acceleratorCheckoutFacade.setStripePlaceOrderGuard(guard);
		final UiExperienceService uiExperienceService = mock(UiExperienceService.class);
		when(uiExperienceService.getUiExperienceLevel()).thenReturn(UiExperienceLevel.DESKTOP);
		acceleratorCheckoutFacade.setUiExperienceService(uiExperienceService);
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
	public void failedStripePaymentGivesStableReason()
	{
		cart.setPaymentInfo(new StripePaymentInfoModel());
		when(authorizer.authorize(cart))
				.thenThrow(new StripeCheckoutException(StripeCheckoutException.Reason.PAYMENT_FAILED, "failed"));

		final StripeCheckoutException fromFacade = assertThrows(StripeCheckoutException.class,
				() -> checkoutFacade.authorizePayment(null));
		final StripeCheckoutException fromAccelerator = assertThrows(StripeCheckoutException.class,
				() -> acceleratorCheckoutFacade.authorizePayment(null));

		assertEquals(StripeCheckoutException.Reason.PAYMENT_FAILED, fromFacade.getReason());
		assertEquals(StripeCheckoutException.Reason.PAYMENT_FAILED, fromAccelerator.getReason());
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

	@Test
	public void placeOrderFailureWithoutOrderReleasesLock() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new InvalidCartException("invalid"));

		assertThrows(InvalidCartException.class, () -> checkoutFacade.placeOrder());

		verify(lockService).release("pi_place");
		verify(recordService, never()).linkOrder(anyString(), any(OrderModel.class));
	}

	@Test
	public void placeOrderFailureAfterOrderSavedKeepsLock() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		final OrderModel order = new OrderModel();
		when(recordService.findLiveOrder("pi_place")).thenReturn(Optional.of(order));
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new IllegalStateException("submit failed"));

		assertThrows(IllegalStateException.class, () -> checkoutFacade.placeOrder());

		verify(lockService, never()).release(anyString());
		verify(recordService).linkOrder("pi_place", order);
	}

	@Test
	public void placeOrderLookupFailureKeepsLock() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		when(recordService.findLiveOrder("pi_place")).thenThrow(new IllegalStateException("database down"));
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new InvalidCartException("invalid"));

		assertThrows(InvalidCartException.class, () -> checkoutFacade.placeOrder());

		verify(lockService, never()).release(anyString());
	}

	@Test
	public void placeOrderWithoutOrderReleasesLock() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		when(checkoutCustomerStrategy.getCurrentUserForCheckout()).thenReturn(new CustomerModel());

		assertNull(checkoutFacade.placeOrder());

		verify(lockService).release("pi_place");
	}

	@Test
	public void placeOrderSuccessLinksRecordToOrder() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		final OrderModel order = new OrderModel();
		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		order.setCurrency(currency);
		final CommerceOrderResult result = new CommerceOrderResult();
		result.setOrder(order);
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class))).thenReturn(result);
		when(recordService.findLiveOrder("pi_place")).thenReturn(Optional.of(order));
		final OrderData orderData = new OrderData();
		when(orderConverter.convert(order)).thenReturn(orderData);

		assertSame(orderData, checkoutFacade.placeOrder());

		verify(recordService).linkOrder("pi_place", order);
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void acceleratorPlaceOrderFailureReleasesLock() throws InvalidCartException
	{
		givenStripeCart("pi_place");
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new IllegalStateException("failed"));

		assertThrows(IllegalStateException.class, () -> acceleratorCheckoutFacade.placeOrder());

		verify(lockService).release("pi_place");
	}

	@Test
	public void creditCardPlaceOrderDoesNotTouchLock() throws InvalidCartException
	{
		cart.setPaymentInfo(new CreditCardPaymentInfoModel());
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new InvalidCartException("invalid"));

		assertThrows(InvalidCartException.class, () -> checkoutFacade.placeOrder());

		verify(lockService, never()).release(anyString());
	}

	private void givenStripeCart(final String paymentIntentId)
	{
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(paymentIntentId);
		cart.setPaymentInfo(paymentInfo);
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
		facade.setCommerceCheckoutService(commerceCheckoutService);
		facade.setModelService(mock(ModelService.class));
		facade.setOrderConverter(orderConverter);
	}
}

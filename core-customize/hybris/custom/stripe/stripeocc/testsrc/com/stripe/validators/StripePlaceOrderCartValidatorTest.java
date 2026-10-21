package com.stripe.validators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.order.CartService;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

@UnitTest
public class StripePlaceOrderCartValidatorTest
{
	private CartService cartService;
	private CartModel cart;
	private SapLikePlaceOrderCartValidator validator;

	@Before
	public void setUp()
	{
		cartService = mock(CartService.class);
		cart = new CartModel();
		when(cartService.hasSessionCart()).thenReturn(true);
		when(cartService.getSessionCart()).thenReturn(cart);

		final StripePlaceOrderCartValidator interceptor = new StripePlaceOrderCartValidator();
		interceptor.setCartService(cartService);
		final ProxyFactory proxyFactory = new ProxyFactory(new SapLikePlaceOrderCartValidator());
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvice(interceptor);
		validator = (SapLikePlaceOrderCartValidator) proxyFactory.getProxy();
	}

	@Test
	public void validatorAcceptsStripeCartWithoutCardData()
	{
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId("pi_1");
		cart.setPaymentInfo(paymentInfo);

		final Errors errors = validate(new CartData());

		assertEquals(List.of("cart.deliveryModeNotSet"), codes(errors));
	}

	@Test
	public void validatorDelegatesForOtherCarts()
	{
		cart.setPaymentInfo(new CreditCardPaymentInfoModel());

		final Errors errors = validate(new CartData());

		assertEquals(List.of("cart.deliveryModeNotSet", "cart.paymentInfoNotSet"), codes(errors));
	}

	@Test
	public void validatorDelegatesForStripeCartWithoutPaymentIntent()
	{
		cart.setPaymentInfo(new StripePaymentInfoModel());

		final Errors errors = validate(new CartData());

		assertEquals(List.of("cart.deliveryModeNotSet", "cart.paymentInfoNotSet"), codes(errors));
	}

	@Test
	public void validatorDelegatesWithoutSessionCart()
	{
		when(cartService.hasSessionCart()).thenReturn(false);

		final Errors errors = validate(new CartData());

		assertEquals(List.of("cart.deliveryModeNotSet", "cart.paymentInfoNotSet"), codes(errors));
	}

	@Test
	public void proxyKeepsTheTypeAndSupportsOfTheParentValidator()
	{
		assertTrue(validator.supports(CartData.class));
		assertFalse(validator.supports(String.class));
	}

	private Errors validate(final CartData cartData)
	{
		final Errors errors = new BeanPropertyBindingResult(cartData, "sessionCart");
		validator.validate(cartData, errors);
		return errors;
	}

	private static List<String> codes(final Errors errors)
	{
		return errors.getAllErrors().stream().map(ObjectError::getCode).collect(Collectors.toList());
	}

	/**
	 * Stands in for SAP's PlaceOrderCartValidator, which lives in the OCC web application and is not on this classpath.
	 */
	public static class SapLikePlaceOrderCartValidator implements Validator
	{
		@Override
		public boolean supports(final Class<?> clazz)
		{
			return CartData.class.equals(clazz);
		}

		@Override
		public void validate(final Object target, final Errors errors)
		{
			final CartData cartData = (CartData) target;
			if (cartData.getDeliveryMode() == null)
			{
				errors.reject("cart.deliveryModeNotSet");
			}
			if (cartData.getPaymentInfo() == null && cartData.getSapGenericPaymentInfo() == null)
			{
				errors.reject("cart.paymentInfoNotSet");
			}
		}
	}
}

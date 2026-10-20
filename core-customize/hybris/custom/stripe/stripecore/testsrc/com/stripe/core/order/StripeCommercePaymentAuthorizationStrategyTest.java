package com.stripe.core.order;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.order.hook.AuthorizePaymentMethodHook;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.i18n.I18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.commerceservices.strategies.GenerateMerchantTransactionCodeStrategy;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@UnitTest
public class StripeCommercePaymentAuthorizationStrategyTest
{
	private static final String PI_ID = "pi_123";
	private static final String MERCHANT_CODE = "merchant-1";
	private static final BigDecimal CHARGE = new BigDecimal("121.00");

	private final PaymentService paymentService = mock(PaymentService.class);
	private final ModelService modelService = mock(ModelService.class);
	private final I18NService i18nService = mock(I18NService.class);
	private final StripeAmountConverter converter = mock(StripeAmountConverter.class);
	private final GenerateMerchantTransactionCodeStrategy codeStrategy = mock(GenerateMerchantTransactionCodeStrategy.class);
	private final ConfigurationService configurationService = mock(ConfigurationService.class);
	private final AuthorizePaymentMethodHook hook = mock(AuthorizePaymentMethodHook.class);
	private final CartModel cart = mock(CartModel.class);
	private final AddressModel address = mock(AddressModel.class);
	private final StripePaymentInfoModel stripeInfo = mock(StripePaymentInfoModel.class);
	private final PaymentTransactionModel transaction = mock(PaymentTransactionModel.class);
	private final PaymentTransactionEntryModel entry = mock(PaymentTransactionEntryModel.class);
	private final Currency eur = Currency.getInstance("EUR");

	private StripeCommercePaymentAuthorizationStrategy strategy;

	@Before
	public void setUp()
	{
		strategy = new StripeCommercePaymentAuthorizationStrategy();
		strategy.setStripeAmountConverter(converter);
		strategy.setPaymentService(paymentService);
		strategy.setModelService(modelService);
		strategy.setI18nService(i18nService);
		strategy.setGenerateMerchantTransactionCodeStrategy(codeStrategy);
		strategy.setConfigurationService(configurationService);
		strategy.setAuthorizePaymentHooks(Collections.singletonList(hook));

		final Configuration configuration = mock(Configuration.class);
		when(configuration.getBoolean(anyString(), eq(true))).thenReturn(true);
		when(configurationService.getConfiguration()).thenReturn(configuration);

		final CurrencyModel currency = mock(CurrencyModel.class);
		when(currency.getIsocode()).thenReturn("EUR");
		when(cart.getCurrency()).thenReturn(currency);
		when(cart.getDeliveryAddress()).thenReturn(address);
		when(cart.getPaymentInfo()).thenReturn(stripeInfo);
		when(stripeInfo.getStripePaymentIntentId()).thenReturn(PI_ID);
		when(i18nService.getBestMatchingJavaCurrency("EUR")).thenReturn(eur);
		when(codeStrategy.generateCode(cart)).thenReturn(MERCHANT_CODE);
		when(converter.chargeAmount(cart)).thenReturn(CHARGE);
		when(entry.getPaymentTransaction()).thenReturn(transaction);
	}

	private CommerceCheckoutParameter parameter(final BigDecimal authorizationAmount)
	{
		final CommerceCheckoutParameter parameter = new CommerceCheckoutParameter();
		parameter.setCart(cart);
		parameter.setAuthorizationAmount(authorizationAmount);
		parameter.setEnableHooks(true);
		return parameter;
	}

	private void paymentServiceReturns(final TransactionStatus status)
	{
		when(entry.getTransactionStatus()).thenReturn(status.name());
		when(paymentService.authorize(eq(MERCHANT_CODE), any(), eq(eur), eq(address), eq(PI_ID), any(), eq("Stripe")))
				.thenReturn(entry);
	}

	@Test
	public void stripeCartUsesPaymentIntentIdAsSubscriptionId()
	{
		paymentServiceReturns(TransactionStatus.ACCEPTED);

		strategy.authorizePaymentAmount(parameter(CHARGE));

		verify(paymentService).authorize(MERCHANT_CODE, CHARGE, eur, address, PI_ID, null, "Stripe");
	}

	@Test
	public void stripeCartAuthorizesChargeAmount()
	{
		paymentServiceReturns(TransactionStatus.ACCEPTED);

		strategy.authorizePaymentAmount(parameter(new BigDecimal("100.00")));

		verify(paymentService).authorize(MERCHANT_CODE, CHARGE, eur, address, PI_ID, null, "Stripe");
	}

	@Test
	public void acceptedEntryIsLinkedToCart()
	{
		paymentServiceReturns(TransactionStatus.ACCEPTED);

		final PaymentTransactionEntryModel result = strategy.authorizePaymentAmount(parameter(CHARGE));

		assertSame(entry, result);
		verify(transaction).setOrder(cart);
		verify(transaction).setInfo(stripeInfo);
		verify(modelService).saveAll(cart, transaction);
		verify(modelService, never()).removeAll(any(java.util.Collection.class));
	}

	@Test
	public void rejectedEntryIsRemoved()
	{
		paymentServiceReturns(TransactionStatus.REJECTED);

		strategy.authorizePaymentAmount(parameter(CHARGE));

		verify(modelService).removeAll(Arrays.asList(transaction, entry));
		verify(transaction, never()).setOrder(any());
		verify(modelService, never()).saveAll(any(), any());
	}

	@Test
	public void reviewEntryIsRemoved()
	{
		paymentServiceReturns(TransactionStatus.REVIEW);

		strategy.authorizePaymentAmount(parameter(CHARGE));

		verify(modelService).removeAll(Arrays.asList(transaction, entry));
		verify(transaction, never()).setOrder(any());
	}

	@Test
	public void hooksRunAroundAuthorization()
	{
		paymentServiceReturns(TransactionStatus.ACCEPTED);
		final CommerceCheckoutParameter parameter = parameter(CHARGE);

		strategy.authorizePaymentAmount(parameter);

		final InOrder order = inOrder(hook, paymentService);
		order.verify(hook).beforeAuthorizePaymentAmount(parameter);
		order.verify(paymentService).authorize(any(), any(), any(), any(), any(), any(), any());
		order.verify(hook).afterAuthorizePaymentAmount(parameter, entry);
	}

	@Test
	public void blankPaymentIntentIdDoesNotAuthorize()
	{
		when(stripeInfo.getStripePaymentIntentId()).thenReturn(" ");

		assertNull(strategy.authorizePaymentAmount(parameter(CHARGE)));

		verify(paymentService, never()).authorize(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	public void nonStripeCartDelegatesToDefault()
	{
		final CreditCardPaymentInfoModel card = mock(CreditCardPaymentInfoModel.class);
		when(card.getSubscriptionId()).thenReturn("sub-1");
		when(cart.getPaymentInfo()).thenReturn(card);
		when(paymentService.authorize(eq(MERCHANT_CODE), any(), eq(eur), eq(address), eq("sub-1"), any(), any()))
				.thenReturn(entry);
		when(entry.getTransactionStatus()).thenReturn(TransactionStatus.ACCEPTED.name());
		final CommerceCheckoutParameter parameter = parameter(new BigDecimal("100.00"));
		parameter.setSecurityCode("123");
		parameter.setPaymentProvider("Other");

		strategy.authorizePaymentAmount(parameter);

		verify(paymentService).authorize(MERCHANT_CODE, new BigDecimal("100.00"), eur, address, "sub-1", "123", "Other");
		verify(converter, never()).chargeAmount(any());
	}
}

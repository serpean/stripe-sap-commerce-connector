package com.stripe.core.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.i18n.I18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeOrderAuthorizationServiceTest
{
	private static final String PI_ID = "pi_auth";
	private static final BigDecimal AMOUNT = new BigDecimal("19.99");
	private static final Currency EUR = Currency.getInstance("EUR");

	private PaymentService paymentService;
	private ModelService modelService;
	private DefaultStripeOrderAuthorizationService service;
	private OrderModel order;
	private PaymentIntent paymentIntent;

	@Before
	public void setUp()
	{
		paymentService = mock(PaymentService.class);
		modelService = mock(ModelService.class);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		final I18NService i18nService = mock(I18NService.class);
		when(i18nService.getBestMatchingJavaCurrency("EUR")).thenReturn(EUR);

		service = new DefaultStripeOrderAuthorizationService();
		service.setPaymentService(paymentService);
		service.setModelService(modelService);
		service.setStripeAmountConverter(converter);
		service.setI18nService(i18nService);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		order = new OrderModel();
		order.setCode("order-9");
		order.setCurrency(currency);
		order.setDeliveryAddress(new AddressModel());
		order.setPaymentInfo(new StripePaymentInfoModel());
		order.setPaymentTransactions(List.of());
		when(converter.chargeAmount(order)).thenReturn(AMOUNT);

		paymentIntent = new PaymentIntent();
		paymentIntent.setId(PI_ID);
	}

	@Test
	public void authorizesPendingOrderAndLinksTransaction()
	{
		final PaymentTransactionEntryModel entry = givenAuthorization(TransactionStatus.ACCEPTED);

		assertTrue(service.authorizePendingOrder(order, paymentIntent));

		verify(paymentService).authorize("order-9-" + PI_ID, AMOUNT, EUR, order.getDeliveryAddress(), PI_ID, null,
				StripeCoreConstants.STRIPE_PROVIDER);
		final PaymentTransactionModel transaction = entry.getPaymentTransaction();
		assertSame(order, transaction.getOrder());
		assertSame(order.getPaymentInfo(), transaction.getInfo());
		verify(modelService).save(transaction);
	}

	@Test
	public void alreadyAuthorizedOrderIsNotAuthorizedAgain()
	{
		final PaymentTransactionEntryModel accepted = new PaymentTransactionEntryModel();
		accepted.setType(PaymentTransactionType.AUTHORIZATION);
		accepted.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		accepted.setRequestId(PI_ID);
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setEntries(List.of(accepted));
		order.setPaymentTransactions(List.of(transaction));

		assertTrue(service.hasAcceptedAuthorization(order, PI_ID));
		assertTrue(service.authorizePendingOrder(order, paymentIntent));

		verifyNoInteractions(paymentService);
	}

	@Test
	public void authorizationOfOtherPaymentIntentDoesNotCount()
	{
		final PaymentTransactionEntryModel accepted = new PaymentTransactionEntryModel();
		accepted.setType(PaymentTransactionType.AUTHORIZATION);
		accepted.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		accepted.setRequestId("pi_other");
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setEntries(List.of(accepted));
		order.setPaymentTransactions(List.of(transaction));

		assertFalse(service.hasAcceptedAuthorization(order, PI_ID));
	}

	@Test
	public void rejectedAuthorizationRemovesTransaction()
	{
		final PaymentTransactionEntryModel entry = givenAuthorization(TransactionStatus.REJECTED);

		assertFalse(service.authorizePendingOrder(order, paymentIntent));

		verify(modelService).removeAll(List.of(entry.getPaymentTransaction(), entry));
		verify(modelService, never()).save(any());
	}

	@Test(expected = IllegalStateException.class)
	public void authorizationErrorThrowsForRetry()
	{
		givenAuthorization(TransactionStatus.ERROR);

		service.authorizePendingOrder(order, paymentIntent);
	}

	@Test
	public void removeAuthorizationsDropsOnlyTheTransactionsOfThePaymentIntent()
	{
		final PaymentTransactionModel stale = transaction(PI_ID, TransactionStatus.ACCEPTED);
		final PaymentTransactionModel other = transaction("pi_other", TransactionStatus.ACCEPTED);
		final CartModel cart = new CartModel();
		cart.setCode("cart-9");
		cart.setPaymentTransactions(new ArrayList<>(List.of(stale, other)));

		service.removeAuthorizations(cart, PI_ID);

		verify(modelService).removeAll(List.of(stale.getEntries().get(0), stale));
		assertEquals(List.of(other), cart.getPaymentTransactions());
		assertFalse(service.hasAcceptedAuthorization(cart, PI_ID));
		assertTrue(service.hasAcceptedAuthorization(cart, "pi_other"));
	}

	@Test
	public void removeAuthorizationsWithoutMatchDoesNothing()
	{
		final CartModel cart = new CartModel();
		cart.setPaymentTransactions(List.of(transaction("pi_other", TransactionStatus.ACCEPTED)));

		service.removeAuthorizations(cart, PI_ID);

		verifyNoInteractions(modelService);
	}

	private static PaymentTransactionModel transaction(final String paymentIntentId, final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setType(PaymentTransactionType.AUTHORIZATION);
		entry.setTransactionStatus(status.name());
		entry.setRequestId(paymentIntentId);
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setCode("txn-" + paymentIntentId);
		transaction.setEntries(List.of(entry));
		return transaction;
	}

	private PaymentTransactionEntryModel givenAuthorization(final TransactionStatus status)
	{
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setPaymentTransaction(transaction);
		entry.setTransactionStatus(status.name());
		when(paymentService.authorize(anyString(), any(), any(), any(), anyString(), any(), anyString())).thenReturn(entry);
		return entry;
	}
}

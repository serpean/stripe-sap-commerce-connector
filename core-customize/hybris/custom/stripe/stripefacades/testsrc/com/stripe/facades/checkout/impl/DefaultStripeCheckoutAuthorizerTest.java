package com.stripe.facades.checkout.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.order.impl.DefaultStripeOrderAuthorizationService;
import com.stripe.core.payment.StripePaymentInfoUpdater;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.facades.checkout.StripeCheckoutException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
public class DefaultStripeCheckoutAuthorizerTest
{
	private static final String PI_ID = "pi_1";

	private StripeGateway gateway;
	private StripePaymentIntentService paymentIntentService;
	private StripeOrderPlacementLockService lockService;
	private CommerceCheckoutService commerceCheckoutService;
	private DefaultStripeCheckoutAuthorizer authorizer;
	private CartModel cart;
	private StripePaymentInfoModel paymentInfo;
	private StripePaymentIntentRecordModel record;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		paymentIntentService = mock(StripePaymentIntentService.class);
		lockService = mock(StripeOrderPlacementLockService.class);
		commerceCheckoutService = mock(CommerceCheckoutService.class);
		final StripeAmountConverter converter = mock(StripeAmountConverter.class);
		final StripePaymentIntentRecordDao recordDao = mock(StripePaymentIntentRecordDao.class);

		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		cart = new CartModel();
		cart.setCode("cart1");
		cart.setCurrency(currency);
		cart.setPaymentInfo(paymentInfo);
		record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);

		when(converter.chargeAmountMinor(cart)).thenReturn(1999L);
		when(converter.chargeAmount(cart)).thenReturn(new BigDecimal("19.99"));
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		when(lockService.tryAcquire(PI_ID)).thenReturn(true);
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.ACCEPTED));

		authorizer = new DefaultStripeCheckoutAuthorizer();
		authorizer.setStripeGateway(gateway);
		authorizer.setStripePaymentIntentService(paymentIntentService);
		authorizer.setStripeOrderPlacementLockService(lockService);
		authorizer.setCommerceCheckoutService(commerceCheckoutService);
		authorizer.setStripeAmountConverter(converter);
		authorizer.setStripePaymentIntentRecordDao(recordDao);
		authorizer.setModelService(mock(ModelService.class));
		final StripePaymentInfoUpdater paymentInfoUpdater = new StripePaymentInfoUpdater();
		paymentInfoUpdater.setModelService(mock(ModelService.class));
		authorizer.setStripePaymentInfoUpdater(paymentInfoUpdater);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(converter);
		authorizer.setStripePaymentIntentChecks(checks);
		authorizer.setStripeOrderAuthorizationService(new DefaultStripeOrderAuthorizationService());
	}

	@Test
	public void acceptedIntentCreatesAcceptedAuthorization()
	{
		givenIntent("requires_capture", 1999L, "eur");

		assertTrue(authorizer.authorize(cart));

		final ArgumentCaptor<CommerceCheckoutParameter> parameter = ArgumentCaptor.forClass(CommerceCheckoutParameter.class);
		verify(commerceCheckoutService).authorizePayment(parameter.capture());
		assertSame(cart, parameter.getValue().getCart());
		assertEquals(new BigDecimal("19.99"), parameter.getValue().getAuthorizationAmount());
		assertEquals("Stripe", parameter.getValue().getPaymentProvider());
		assertTrue(parameter.getValue().isEnableHooks());
		assertEquals(StripePaymentIntentState.AUTHORIZED, record.getState());
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void capturedIntentIsRecordedAsCaptured()
	{
		givenIntent("succeeded", 1999L, "eur");

		assertTrue(authorizer.authorize(cart));

		assertEquals(StripePaymentIntentState.CAPTURED, record.getState());
	}

	@Test
	public void processingIntentReturnsTrueWithoutAuthorizationEntry()
	{
		final PaymentIntent intent = givenIntent("processing", 1999L, "eur");
		intent.setPaymentMethodObject(paymentMethod("sepa_debit", "pm_sepa"));

		assertTrue(authorizer.authorize(cart));

		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
		assertEquals(StripePaymentIntentState.PROCESSING, record.getState());
		assertEquals("sepa_debit", paymentInfo.getPaymentMethodType());
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void failedIntentReturnsFalseAndReleasesLock()
	{
		givenIntent("requires_payment_method", 1999L, "eur");

		assertFalse(authorizer.authorize(cart));

		verify(lockService).release(PI_ID);
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void intentThatNeedsActionReturnsFalseAndReleasesLock()
	{
		givenIntent("requires_action", 1999L, "eur");

		assertFalse(authorizer.authorize(cart));

		verify(lockService).release(PI_ID);
	}

	@Test
	public void rejectedAuthorizationReturnsFalseAndReleasesLock()
	{
		givenIntent("requires_capture", 1999L, "eur");
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.REJECTED));

		assertFalse(authorizer.authorize(cart));

		verify(lockService).release(PI_ID);
	}

	@Test
	public void missingAuthorizationReturnsFalseAndReleasesLock()
	{
		givenIntent("requires_capture", 1999L, "eur");
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class))).thenReturn(null);

		assertFalse(authorizer.authorize(cart));

		verify(lockService).release(PI_ID);
	}

	@Test
	public void amountMismatchReleasesFundsAndThrows()
	{
		givenIntent("requires_capture", 1500L, "eur");

		assertReason(StripeCheckoutException.Reason.AMOUNT_MISMATCH);

		verify(paymentIntentService).releaseFunds(eq(PI_ID), anyString());
		assertNull(paymentInfo.getStripePaymentIntentId());
		verify(lockService, never()).tryAcquire(anyString());
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void currencyMismatchReleasesFundsAndThrows()
	{
		givenIntent("succeeded", 1999L, "usd");

		assertReason(StripeCheckoutException.Reason.AMOUNT_MISMATCH);

		verify(paymentIntentService).releaseFunds(eq(PI_ID), anyString());
	}

	@Test
	public void amountMismatchWithoutMoneyDoesNotReleaseFunds()
	{
		givenIntent("requires_payment_method", 1500L, "eur");

		assertFalse(authorizer.authorize(cart));

		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void secondPlacementForSamePaymentIntentIsRejectedByLock()
	{
		givenIntent("requires_capture", 1999L, "eur");
		when(lockService.tryAcquire(PI_ID)).thenReturn(true, false);

		assertTrue(authorizer.authorize(cart));
		assertReason(StripeCheckoutException.Reason.ALREADY_PLACED);

		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void doubleClickIsRejectedWhileFirstPlacementHoldsLock()
	{
		givenIntent("requires_capture", 1999L, "eur");
		givenRealLockAndAuthorization();

		assertTrue(authorizer.authorize(cart));
		assertReason(StripeCheckoutException.Reason.ALREADY_PLACED);

		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void retryAfterFailedPlacementReacquiresLockWithoutNewAuthorization()
	{
		givenIntent("requires_capture", 1999L, "eur");
		final InMemoryLock lock = givenRealLockAndAuthorization();
		assertTrue(authorizer.authorize(cart));
		lock.release(PI_ID);

		assertTrue(authorizer.authorize(cart));

		assertTrue(lock.held.contains(PI_ID));
		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void retryWithCanceledIntentDoesNotReuseAuthorization()
	{
		givenIntent("canceled", 1999L, "eur");
		givenAcceptedAuthorization(PI_ID);

		assertFalse(authorizer.authorize(cart));

		verify(lockService).tryAcquire(PI_ID);
		verify(lockService).release(PI_ID);
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void processingIntentWithExistingEntryReturnsFalseAndReleasesLock()
	{
		givenIntent("processing", 1999L, "eur");
		givenAcceptedAuthorization(PI_ID);

		assertFalse(authorizer.authorize(cart));

		verify(lockService).tryAcquire(PI_ID);
		verify(lockService).release(PI_ID);
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void acceptedAuthorizationForOtherIntentDoesNotShortCircuit()
	{
		givenIntent("requires_capture", 1999L, "eur");
		givenAcceptedAuthorization("pi_other");

		assertTrue(authorizer.authorize(cart));

		verify(lockService).tryAcquire(PI_ID);
		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void existingAuthorizationDoesNotSkipAmountCheck()
	{
		givenIntent("requires_capture", 1500L, "eur");
		givenAcceptedAuthorization(PI_ID);

		assertReason(StripeCheckoutException.Reason.AMOUNT_MISMATCH);

		verify(lockService, never()).tryAcquire(anyString());
	}

	@Test
	public void existingAuthorizationOfRefundedIntentIsNotReused()
	{
		final PaymentIntent intent = givenIntent("succeeded", 1999L, "eur");
		intent.setAmountReceived(1999L);
		intent.setLatestChargeObject(charge(500L));
		givenAcceptedAuthorization(PI_ID);
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.REJECTED));

		assertFalse(authorizer.authorize(cart));

		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(lockService).release(PI_ID);
	}

	@Test
	public void existingAuthorizationOfCapturedIntentWithAllMoneyIsReused()
	{
		final PaymentIntent intent = givenIntent("succeeded", 1999L, "eur");
		intent.setAmountReceived(1999L);
		intent.setLatestChargeObject(charge(0L));
		givenAcceptedAuthorization(PI_ID);

		assertTrue(authorizer.authorize(cart));

		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void cartWithoutPaymentIntentThrowsPaymentNotFound()
	{
		paymentInfo.setStripePaymentIntentId(null);

		assertReason(StripeCheckoutException.Reason.PAYMENT_NOT_FOUND);
	}

	@Test
	public void cardDisplayDataIsCopied()
	{
		final PaymentIntent intent = givenIntent("requires_capture", 1999L, "eur");
		final PaymentMethod method = paymentMethod("card", "pm_card");
		final PaymentMethod.Card card = new PaymentMethod.Card();
		card.setBrand("visa");
		card.setLast4("4242");
		card.setExpMonth(12L);
		card.setExpYear(2030L);
		method.setCard(card);
		intent.setPaymentMethodObject(method);

		assertTrue(authorizer.authorize(cart));

		assertEquals("card", paymentInfo.getPaymentMethodType());
		assertEquals("pm_card", paymentInfo.getPaymentMethodId());
		assertEquals("visa", paymentInfo.getCardBrand());
		assertEquals("4242", paymentInfo.getCardLast4());
		assertEquals(Integer.valueOf(12), paymentInfo.getCardExpMonth());
		assertEquals(Integer.valueOf(2030), paymentInfo.getCardExpYear());
	}

	private PaymentIntent givenIntent(final String status, final long amount, final String currency)
	{
		final PaymentIntent intent = new PaymentIntent();
		intent.setId(PI_ID);
		intent.setStatus(status);
		intent.setAmount(amount);
		intent.setCurrency(currency);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(intent);
		return intent;
	}

	private InMemoryLock givenRealLockAndAuthorization()
	{
		final InMemoryLock lock = new InMemoryLock();
		authorizer.setStripeOrderPlacementLockService(lock);
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class))).thenAnswer(invocation -> {
			givenAcceptedAuthorization(PI_ID);
			return cart.getPaymentTransactions().get(0).getEntries().get(0);
		});
		return lock;
	}

	private void givenAcceptedAuthorization(final String requestId)
	{
		final PaymentTransactionEntryModel entry = entry(TransactionStatus.ACCEPTED);
		entry.setType(PaymentTransactionType.AUTHORIZATION);
		entry.setRequestId(requestId);
		final PaymentTransactionModel transaction = new PaymentTransactionModel();
		transaction.setPaymentProvider("Stripe");
		transaction.setEntries(List.of(entry));
		cart.setPaymentTransactions(List.of(transaction));
	}

	private static Charge charge(final long refunded)
	{
		final Charge charge = new Charge();
		charge.setId("ch_1");
		charge.setAmountRefunded(refunded);
		return charge;
	}

	private PaymentMethod paymentMethod(final String type, final String id)
	{
		final PaymentMethod method = new PaymentMethod();
		method.setType(type);
		method.setId(id);
		return method;
	}

	private PaymentTransactionEntryModel entry(final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus(status.name());
		return entry;
	}

	private void assertReason(final StripeCheckoutException.Reason reason)
	{
		try
		{
			authorizer.authorize(cart);
			fail("Expected StripeCheckoutException " + reason);
		}
		catch (final StripeCheckoutException e)
		{
			assertEquals(reason, e.getReason());
		}
	}

	private static class InMemoryLock implements StripeOrderPlacementLockService
	{
		private final Set<String> held = new HashSet<>();

		@Override
		public boolean tryAcquire(final String paymentIntentId)
		{
			return held.add(paymentIntentId);
		}

		@Override
		public void release(final String paymentIntentId)
		{
			held.remove(paymentIntentId);
		}
	}
}

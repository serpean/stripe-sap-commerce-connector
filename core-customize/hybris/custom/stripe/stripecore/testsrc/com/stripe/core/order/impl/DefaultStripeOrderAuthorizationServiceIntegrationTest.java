package com.stripe.core.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.ServicelayerTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.i18n.I18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.test.TestThreadsHolder;
import de.hybris.platform.tx.Transaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Three callers authorize one pending order at the same time, as the webhook tasks and the order process do when a
 * pending payment succeeds. The test is not transactional, so each thread sees what the others commit. The payment
 * service is a stand-in that writes a real ACCEPTED entry after a pause (no Stripe call); the claim, the database read
 * and the models are real.
 */
@IntegrationTest
public class DefaultStripeOrderAuthorizationServiceIntegrationTest extends ServicelayerTest
{
	private static final int CALLERS = 3;

	@Resource
	private ModelService modelService;
	@Resource
	private FlexibleSearchService flexibleSearchService;
	@Resource
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	@Resource
	private StripeAmountConverter stripeAmountConverter;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

	private final AtomicInteger authorizeCalls = new AtomicInteger();
	private String paymentIntentId;
	private OrderModel order;
	private DefaultStripeOrderAuthorizationService service;

	@Before
	public void setUp()
	{
		paymentIntentId = "pi_concurrent_auth_" + System.nanoTime();
		order = modelService.create(OrderModel.class);
		order.setCode("stripe-auth-it-" + System.nanoTime());
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		order.setNet(Boolean.FALSE);
		order.setTotalPrice(Double.valueOf(10.0));
		modelService.save(order);

		service = new DefaultStripeOrderAuthorizationService();
		service.setPaymentService(slowAcceptingPaymentService());
		service.setStripeAmountConverter(stripeAmountConverter);
		// The test tenant currency is not an ISO code; the stand-in payment service needs only some currency.
		final I18NService i18nService = mock(I18NService.class);
		when(i18nService.getBestMatchingJavaCurrency(anyString())).thenReturn(Currency.getInstance("EUR"));
		service.setI18nService(i18nService);
		service.setModelService(modelService);
		service.setFlexibleSearchService(flexibleSearchService);
		service.setStripeOrderPlacementLockService(stripeOrderPlacementLockService);
		service.setClaimWaitMillis(10_000L);
		service.setClaimPollMillis(50L);
	}

	@After
	public void tearDown()
	{
		final List<Object> removed = new ArrayList<>();
		removed.addAll(search("SELECT {e.pk} FROM {PaymentTransactionEntry AS e JOIN PaymentTransaction AS t"
				+ " ON {e.paymentTransaction} = {t.pk}} WHERE {e.requestId} = ?id", paymentIntentId));
		removed.addAll(search("SELECT {pk} FROM {PaymentTransaction} WHERE {requestId} = ?id", paymentIntentId));
		removed.addAll(search("SELECT {pk} FROM {StripeOrderPlacementLock} WHERE {paymentIntentId} = ?id",
				DefaultStripeOrderAuthorizationService.CLAIM_PREFIX + paymentIntentId));
		removed.add(order);
		modelService.removeAll(removed);
	}

	@Test
	public void concurrentCallersCreateExactlyOneAuthorization()
	{
		final PK orderPk = order.getPk();
		final ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();
		final TestThreadsHolder<Runnable> threads = new TestThreadsHolder<>(CALLERS, () -> {
			final OrderModel own = modelService.get(orderPk);
			final PaymentIntent paymentIntent = new PaymentIntent();
			paymentIntent.setId(paymentIntentId);
			results.add(Boolean.valueOf(service.authorizePendingOrder(own, paymentIntent)));
		}, true);

		threads.startAll();
		assertTrue("all callers finished", threads.waitForAll(60, TimeUnit.SECONDS));

		assertEquals("no caller failed: " + threads.getErrors(), Map.of(), threads.getErrors());
		assertEquals(List.of(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE), new ArrayList<>(results));
		assertEquals("one call of the payment service", 1, authorizeCalls.get());
		final List<Object> entries = search("SELECT {e.pk} FROM {PaymentTransactionEntry AS e JOIN PaymentTransaction AS t"
				+ " ON {e.paymentTransaction} = {t.pk}} WHERE {e.requestId} = ?id AND {t.order} IS NOT NULL", paymentIntentId);
		assertEquals("exactly one AUTHORIZATION entry", 1, entries.size());
		final List<Object> claims = search("SELECT {pk} FROM {StripeOrderPlacementLock} WHERE {paymentIntentId} = ?id",
				DefaultStripeOrderAuthorizationService.CLAIM_PREFIX + paymentIntentId);
		assertEquals("the claim of the authorization stays", 1, claims.size());
	}

	@Test
	public void lostClaimInsideATransactionDoesNotMarkItRollbackOnly()
	{
		assertTrue(stripeOrderPlacementLockService.tryAcquire(DefaultStripeOrderAuthorizationService.CLAIM_PREFIX
				+ paymentIntentId));
		service.setClaimWaitMillis(100L);
		final PaymentIntent paymentIntent = new PaymentIntent();
		paymentIntent.setId(paymentIntentId);
		final Transaction tx = Transaction.current();
		tx.begin();
		try
		{
			assertThrows(IllegalStateException.class, () -> service.authorizePendingOrder(order, paymentIntent));
			assertFalse("the caller can still commit its work", tx.isRollbackOnly());
		}
		finally
		{
			tx.rollback();
		}
		assertEquals("no payment service call", 0, authorizeCalls.get());
	}

	/**
	 * Writes a PaymentTransaction with an ACCEPTED AUTHORIZATION entry, as the SAP payment service does with the Stripe
	 * authorization command, after a pause that lets the other callers reach the guard.
	 */
	private PaymentService slowAcceptingPaymentService()
	{
		final PaymentService paymentService = mock(PaymentService.class);
		when(paymentService.authorize(anyString(), any(BigDecimal.class), any(), any(), anyString(), any(), anyString()))
				.thenAnswer(invocation -> {
					authorizeCalls.incrementAndGet();
					Thread.sleep(500L);
					final String code = invocation.getArgument(0);
					final PaymentTransactionModel transaction = modelService.create(PaymentTransactionModel.class);
					transaction.setCode(code);
					transaction.setRequestId(paymentIntentId);
					transaction.setPaymentProvider("Stripe");
					transaction.setPlannedAmount(invocation.getArgument(1));
					modelService.save(transaction);
					final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);
					entry.setCode(code + "-AUTHORIZATION-1");
					entry.setType(PaymentTransactionType.AUTHORIZATION);
					entry.setTransactionStatus(TransactionStatus.ACCEPTED.name());
					entry.setTransactionStatusDetails(TransactionStatusDetails.SUCCESFULL.name());
					entry.setRequestId(paymentIntentId);
					entry.setAmount(invocation.getArgument(1));
					entry.setPaymentTransaction(transaction);
					modelService.save(entry);
					return entry;
				});
		return paymentService;
	}

	private List<Object> search(final String query, final String id)
	{
		return new ArrayList<>(flexibleSearchService.search(new FlexibleSearchQuery(query, Map.of("id", id))).getResult());
	}
}

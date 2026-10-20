package com.stripe.core.order.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.payment.StripePaymentInfoUpdater;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.commerceservices.enums.SalesApplication;
import de.hybris.platform.commerceservices.impersonation.ImpersonationContext;
import de.hybris.platform.commerceservices.impersonation.ImpersonationService;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.service.data.CommerceOrderResult;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.InvalidCartException;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.servicelayer.search.impl.SearchResultImpl;
import de.hybris.platform.site.BaseSiteService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@UnitTest
public class DefaultStripeOrderPlacementServiceTest
{
	private static final String PI_ID = "pi_789";
	private static final String CART_CODE = "00001234";
	private static final String SITE_UID = "electronics";
	private static final long AMOUNT_MINOR = 12100L;
	private static final BigDecimal AMOUNT = new BigDecimal("121.00");

	private final FlexibleSearchService flexibleSearchService = mock(FlexibleSearchService.class);
	private final BaseSiteService baseSiteService = mock(BaseSiteService.class);
	private final ImpersonationService impersonationService = mock(ImpersonationService.class);
	private final CommerceCheckoutService commerceCheckoutService = mock(CommerceCheckoutService.class);
	private final StripeOrderPlacementLockService lockService = mock(StripeOrderPlacementLockService.class);
	private final StripePaymentIntentRecordService recordService = mock(StripePaymentIntentRecordService.class);
	private final StripePaymentIntentService paymentIntentService = mock(StripePaymentIntentService.class);
	private final StripeAmountConverter amountConverter = mock(StripeAmountConverter.class);
	private final ModelService modelService = mock(ModelService.class);

	private final BaseSiteModel site = mock(BaseSiteModel.class);
	private final CustomerModel user = mock(CustomerModel.class);
	private final CurrencyModel currency = mock(CurrencyModel.class);
	private final LanguageModel userLanguage = mock(LanguageModel.class);
	private final LanguageModel siteLanguage = mock(LanguageModel.class);
	private final CartModel cart = mock(CartModel.class);
	private final StripePaymentInfoModel paymentInfo = mock(StripePaymentInfoModel.class);
	private final OrderModel order = mock(OrderModel.class);
	private final PaymentTransactionEntryModel acceptedEntry = mock(PaymentTransactionEntryModel.class);

	private DefaultStripeOrderPlacementService service;
	private PaymentIntent paymentIntent;
	private ImpersonationContext usedContext;

	@Before
	public void setUp() throws Exception
	{
		service = new DefaultStripeOrderPlacementService();
		service.setFlexibleSearchService(flexibleSearchService);
		service.setBaseSiteService(baseSiteService);
		service.setImpersonationService(impersonationService);
		service.setCommerceCheckoutService(commerceCheckoutService);
		service.setStripeOrderPlacementLockService(lockService);
		service.setStripePaymentIntentRecordService(recordService);
		service.setStripePaymentIntentService(paymentIntentService);
		service.setStripeAmountConverter(amountConverter);
		service.setModelService(modelService);
		final StripePaymentInfoUpdater paymentInfoUpdater = new StripePaymentInfoUpdater();
		paymentInfoUpdater.setModelService(modelService);
		service.setStripePaymentInfoUpdater(paymentInfoUpdater);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(amountConverter);
		service.setStripePaymentIntentChecks(checks);
		service.setStripeOrderAuthorizationService(new DefaultStripeOrderAuthorizationService());

		paymentIntent = new PaymentIntent();
		paymentIntent.setId(PI_ID);
		paymentIntent.setAmount(AMOUNT_MINOR);
		paymentIntent.setCurrency("eur");
		paymentIntent.setStatus("requires_capture");
		paymentIntent.setMetadata(Map.of("cartCode", CART_CODE, "baseSiteUid", SITE_UID, "userUid", "anna@example.com",
				"currencyIso", "EUR"));

		when(baseSiteService.getBaseSiteForUID(SITE_UID)).thenReturn(site);
		when(site.getDefaultLanguage()).thenReturn(siteLanguage);
		when(flexibleSearchService.search(any(FlexibleSearchQuery.class))).thenReturn(result(List.of(cart)));
		when(cart.getCode()).thenReturn(CART_CODE);
		when(cart.getSite()).thenReturn(site);
		when(cart.getUser()).thenReturn(user);
		when(cart.getCurrency()).thenReturn(currency);
		when(cart.getPaymentInfo()).thenReturn(paymentInfo);
		when(cart.getPaymentTransactions()).thenReturn(Collections.emptyList());
		when(paymentInfo.getStripePaymentIntentId()).thenReturn(PI_ID);
		when(currency.getIsocode()).thenReturn("EUR");
		when(user.getSessionLanguage()).thenReturn(userLanguage);
		when(amountConverter.chargeAmountMinor(cart)).thenReturn(AMOUNT_MINOR);
		when(amountConverter.chargeAmount(cart)).thenReturn(AMOUNT);
		when(lockService.tryAcquire(PI_ID)).thenReturn(true);
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.empty());
		when(order.getCode()).thenReturn("order-1");
		when(order.getCurrency()).thenReturn(currency);
		when(amountConverter.chargeAmountMinor(order)).thenReturn(AMOUNT_MINOR);

		when(acceptedEntry.getTransactionStatus()).thenReturn(TransactionStatus.ACCEPTED.name());
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class))).thenReturn(acceptedEntry);
		final CommerceOrderResult orderResult = new CommerceOrderResult();
		orderResult.setOrder(order);
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class))).thenReturn(orderResult);

		when(impersonationService.executeInContext(any(ImpersonationContext.class), any())).thenAnswer(invocation -> {
			usedContext = invocation.getArgument(0);
			final ImpersonationService.Executor<?, ?> executor = invocation.getArgument(1);
			return executor.execute();
		});
	}

	@Test
	public void placesOrderInCartContext() throws Exception
	{
		final Optional<OrderModel> placed = service.placeOrderFor(paymentIntent);

		assertTrue(placed.isPresent());
		assertSame(order, placed.get());

		assertSame(site, usedContext.getSite());
		assertSame(user, usedContext.getUser());
		assertSame(currency, usedContext.getCurrency());
		assertSame(userLanguage, usedContext.getLanguage());
		assertSame(cart, usedContext.getOrder());

		final ArgumentCaptor<CommerceCheckoutParameter> authorization = ArgumentCaptor.forClass(CommerceCheckoutParameter.class);
		verify(commerceCheckoutService).authorizePayment(authorization.capture());
		assertSame(cart, authorization.getValue().getCart());
		assertEquals(AMOUNT, authorization.getValue().getAuthorizationAmount());
		assertEquals(StripeCoreConstants.STRIPE_PROVIDER, authorization.getValue().getPaymentProvider());
		assertTrue(authorization.getValue().isEnableHooks());

		final ArgumentCaptor<CommerceCheckoutParameter> placement = ArgumentCaptor.forClass(CommerceCheckoutParameter.class);
		verify(commerceCheckoutService).placeOrder(placement.capture());
		assertSame(cart, placement.getValue().getCart());
		assertEquals(SalesApplication.WEB, placement.getValue().getSalesApplication());
		assertTrue(placement.getValue().isEnableHooks());

		verify(recordService).linkOrder(PI_ID, order);
		verify(lockService, never()).release(anyString());
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
	}

	@Test
	public void placedOrderCarriesCardDisplayData() throws Exception
	{
		final PaymentMethod.Card card = new PaymentMethod.Card();
		card.setBrand("visa");
		card.setLast4("4242");
		card.setExpMonth(12L);
		card.setExpYear(2030L);
		final PaymentMethod method = new PaymentMethod();
		method.setId("pm_card");
		method.setType("card");
		method.setCard(card);
		paymentIntent.setPaymentMethodObject(method);

		service.placeOrderFor(paymentIntent);

		final InOrder inOrder = inOrder(paymentInfo, modelService, commerceCheckoutService);
		inOrder.verify(paymentInfo).setPaymentMethodType("card");
		inOrder.verify(modelService).save(paymentInfo);
		inOrder.verify(commerceCheckoutService).placeOrder(any(CommerceCheckoutParameter.class));
		verify(paymentInfo).setPaymentMethodId("pm_card");
		verify(paymentInfo).setCardBrand("visa");
		verify(paymentInfo).setCardLast4("4242");
		verify(paymentInfo).setCardExpMonth(12);
		verify(paymentInfo).setCardExpYear(2030);
	}

	@Test
	public void usesTheSiteLanguageWhenTheUserHasNone()
	{
		when(user.getSessionLanguage()).thenReturn(null);

		service.placeOrderFor(paymentIntent);

		assertSame(siteLanguage, usedContext.getLanguage());
	}

	@Test
	public void missingCartReleasesFunds() throws Exception
	{
		when(flexibleSearchService.search(any(FlexibleSearchQuery.class))).thenReturn(result(Collections.emptyList()));

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "cart-not-found");
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void unknownSiteReleasesFunds() throws Exception
	{
		when(baseSiteService.getBaseSiteForUID(SITE_UID)).thenReturn(null);

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "cart-not-found");
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void cartOfAnotherPaymentIntentReleasesFunds() throws Exception
	{
		when(paymentInfo.getStripePaymentIntentId()).thenReturn("pi_other");

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "cart-not-found");
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
		verify(paymentInfo, never()).setStripePaymentIntentId(any());
	}

	@Test
	public void amountMismatchReleasesFunds() throws Exception
	{
		when(amountConverter.chargeAmountMinor(cart)).thenReturn(AMOUNT_MINOR + 1);

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "amount-mismatch");
		verify(paymentInfo).setStripePaymentIntentId(null);
		verify(modelService).save(paymentInfo);
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void currencyMismatchReleasesFunds() throws Exception
	{
		paymentIntent.setCurrency("usd");

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "amount-mismatch");
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void lockHeldByOtherPathSkipsPlacement() throws Exception
	{
		when(lockService.tryAcquire(PI_ID)).thenReturn(false);

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
		verify(lockService, never()).release(anyString());
	}

	@Test
	public void liveOrderFoundUnderTheLockIsLinkedAndNotPlacedAgain() throws Exception
	{
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.of(order));

		assertSame(order, service.placeOrderFor(paymentIntent).get());

		verify(recordService).linkOrder(PI_ID, order);
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void failureReleasesLock() throws Exception
	{
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class)))
				.thenThrow(new InvalidCartException("cart is not valid"));

		try
		{
			service.placeOrderFor(paymentIntent);
			fail("A failed placement must be thrown so the runner retries");
		}
		catch (final RuntimeException e)
		{
			assertTrue(e.getCause() instanceof InvalidCartException);
		}

		verify(lockService).release(PI_ID);
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(modelService, never()).remove(cart);
	}

	@Test
	public void failureKeepsLockWhenAnOrderExists() throws Exception
	{
		final RuntimeException failure = new IllegalStateException("submit failed");
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class))).thenThrow(failure);
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.empty(), Optional.of(order));

		try
		{
			service.placeOrderFor(paymentIntent);
			fail("A failed placement must be thrown so the runner retries");
		}
		catch (final RuntimeException e)
		{
			assertSame(failure, e);
		}

		verify(lockService, never()).release(anyString());
	}

	@Test
	public void rejectedAuthorizationReleasesFundsAndKeepsLock() throws Exception
	{
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.REJECTED));

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(paymentIntentService).releaseFunds(PI_ID, "authorization-rejected");
		verify(paymentInfo).setStripePaymentIntentId(null);
		verify(modelService).save(paymentInfo);
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
		verify(lockService, never()).release(anyString());
		verify(modelService, never()).remove(cart);
	}

	@Test
	public void errorAuthorizationIsRetried() throws Exception
	{
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.ERROR));

		try
		{
			service.placeOrderFor(paymentIntent);
			fail("An authorization in error must be thrown so the runner retries");
		}
		catch (final IllegalStateException expected)
		{
			// expected
		}

		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
		verify(lockService).release(PI_ID);
	}

	@Test
	public void missingAuthorizationEntryIsRetried() throws Exception
	{
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class))).thenReturn(null);

		try
		{
			service.placeOrderFor(paymentIntent);
			fail("A missing authorization entry must be thrown so the runner retries");
		}
		catch (final IllegalStateException expected)
		{
			// expected
		}

		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(lockService).release(PI_ID);
	}

	@Test
	public void existingAuthorizationOfRefundedIntentIsNotReused() throws Exception
	{
		givenAcceptedAuthorizationOnCart();
		paymentIntent.setStatus("succeeded");
		paymentIntent.setAmountReceived(AMOUNT_MINOR);
		paymentIntent.setLatestChargeObject(charge(500L));
		when(commerceCheckoutService.authorizePayment(any(CommerceCheckoutParameter.class)))
				.thenReturn(entry(TransactionStatus.REJECTED));

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(commerceCheckoutService).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(paymentIntentService).releaseFunds(PI_ID, "authorization-rejected");
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void existingAuthorizationOfCapturedIntentWithAllMoneyIsReused() throws Exception
	{
		givenAcceptedAuthorizationOnCart();
		paymentIntent.setStatus("succeeded");
		paymentIntent.setAmountReceived(AMOUNT_MINOR);
		paymentIntent.setLatestChargeObject(charge(0L));

		assertSame(order, service.placeOrderFor(paymentIntent).get());

		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void orderTotalMismatchAfterPlacementIsLogged()
	{
		when(amountConverter.chargeAmountMinor(order)).thenReturn(AMOUNT_MINOR + 100);
		final List<String> errors = new ArrayList<>();
		final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(DefaultStripeOrderPlacementService.class);
		final AbstractAppender appender = new AbstractAppender("placement-test", null, null, true, Property.EMPTY_ARRAY)
		{
			@Override
			public void append(final LogEvent event)
			{
				if (Level.ERROR.equals(event.getLevel()))
				{
					errors.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		logger.addAppender(appender);
		try
		{
			assertSame(order, service.placeOrderFor(paymentIntent).get());
		}
		finally
		{
			logger.removeAppender(appender);
			appender.stop();
		}

		assertEquals(1, errors.size());
		assertTrue(errors.get(0).contains(PI_ID));
		assertTrue(errors.get(0).contains("order-1"));
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(recordService).linkOrder(PI_ID, order);
	}

	@Test
	public void matchingOrderTotalLogsNoError()
	{
		assertSame(order, service.placeOrderFor(paymentIntent).get());

		verify(amountConverter).chargeAmountMinor(order);
	}

	@Test
	public void processingPaymentPlacesOrderWithoutAuthorization() throws Exception
	{
		paymentIntent.setStatus("processing");

		assertSame(order, service.placeOrderFor(paymentIntent).get());

		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(commerceCheckoutService).placeOrder(any(CommerceCheckoutParameter.class));
		verify(recordService).linkOrder(PI_ID, order);
	}

	@Test
	public void existingAcceptedAuthorizationOfTheCartIsReused() throws Exception
	{
		givenAcceptedAuthorizationOnCart();

		assertSame(order, service.placeOrderFor(paymentIntent).get());

		verify(commerceCheckoutService, never()).authorizePayment(any(CommerceCheckoutParameter.class));
		verify(commerceCheckoutService).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void paymentWithoutMoneyPlacesNoOrder() throws Exception
	{
		paymentIntent.setStatus("requires_payment_method");

		assertFalse(service.placeOrderFor(paymentIntent).isPresent());

		verify(lockService, never()).tryAcquire(anyString());
		verify(paymentIntentService, never()).releaseFunds(anyString(), anyString());
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void cartIsRemovedAfterPlacement() throws Exception
	{
		service.placeOrderFor(paymentIntent);

		final InOrder inOrder = inOrder(commerceCheckoutService, recordService, modelService);
		inOrder.verify(commerceCheckoutService).placeOrder(any(CommerceCheckoutParameter.class));
		inOrder.verify(recordService).linkOrder(PI_ID, order);
		inOrder.verify(modelService).remove(cart);
	}

	private void givenAcceptedAuthorizationOnCart()
	{
		final PaymentTransactionModel transaction = mock(PaymentTransactionModel.class);
		when(transaction.getPaymentProvider()).thenReturn(StripeCoreConstants.STRIPE_PROVIDER);
		when(transaction.getEntries()).thenReturn(List.of(acceptedEntry));
		when(acceptedEntry.getType()).thenReturn(PaymentTransactionType.AUTHORIZATION);
		when(acceptedEntry.getRequestId()).thenReturn(PI_ID);
		when(cart.getPaymentTransactions()).thenReturn(List.of(transaction));
	}

	private static PaymentTransactionEntryModel entry(final TransactionStatus status)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus(status.name());
		return entry;
	}

	private static Charge charge(final long refunded)
	{
		final Charge charge = new Charge();
		charge.setId("ch_1");
		charge.setAmountRefunded(refunded);
		return charge;
	}

	private static <T> SearchResult<T> result(final List<T> items)
	{
		return new SearchResultImpl<>(items, items.size(), items.size(), 0);
	}
}

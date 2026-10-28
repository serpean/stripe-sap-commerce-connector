package com.stripe.core.payment.impl;

import static com.stripe.core.testsupport.StripePaymentIntentSamples.BANK_TRANSFER;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.CAPTURE_BEFORE;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.CARD_AUTHORIZED;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.EXPIRES_AT;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.MULTIBANCO;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.PAYPAL_AUTHORIZED;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.SEPA_DEBIT;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.paymentIntent;
import static com.stripe.core.testsupport.StripePaymentIntentSamples.retrievedPaymentIntent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.Date;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripePaymentDetailsCopierTest
{
	private ModelService modelService;
	private DefaultStripePaymentDetailsCopier copier;
	private StripePaymentInfoModel paymentInfo;

	@Before
	public void setUp()
	{
		modelService = mock(ModelService.class);
		copier = new DefaultStripePaymentDetailsCopier();
		copier.setModelService(modelService);
		paymentInfo = new StripePaymentInfoModel();
	}

	@Test
	public void multibanco()
	{
		copier.copy(paymentIntent(MULTIBANCO), paymentInfo);

		assertEquals("multibanco", paymentInfo.getPaymentMethodType());
		assertEquals("pm_mb", paymentInfo.getPaymentMethodId());
		assertEquals("12345 123456789", paymentInfo.getPaymentReference());
		assertEquals("https://payments.stripe.com/multibanco/voucher/test_YWNjdF8x", paymentInfo.getInstructionsUrl());
		assertEquals(new Date(EXPIRES_AT * 1000L), paymentInfo.getInstructionsExpireAt());
		assertNull(paymentInfo.getAmountRemaining());
		assertNull(paymentInfo.getCardLast4());
		verify(modelService).save(paymentInfo);
	}

	@Test
	public void bankTransfer()
	{
		copier.copy(paymentIntent(BANK_TRANSFER), paymentInfo);

		assertEquals("customer_balance", paymentInfo.getPaymentMethodType());
		assertEquals("QHJ7RZ2MXN4P", paymentInfo.getPaymentReference());
		assertEquals("https://payments.stripe.com/bank_transfers/instructions/test_YWNjdF8x",
				paymentInfo.getInstructionsUrl());
		assertEquals(Long.valueOf(5100L), paymentInfo.getAmountRemaining());
		assertNull(paymentInfo.getInstructionsExpireAt());
		verify(modelService).save(paymentInfo);
	}

	@Test
	public void sepaMandate()
	{
		copier.copy(paymentIntent(SEPA_DEBIT), paymentInfo);

		assertEquals("sepa_debit", paymentInfo.getPaymentMethodType());
		assertEquals("mandate_1Abc", paymentInfo.getSepaMandateId());
		assertNull(paymentInfo.getCaptureBefore());
		assertNull(paymentInfo.getPaymentReference());
	}

	@Test
	public void cardCaptureBefore()
	{
		copier.copy(paymentIntent(CARD_AUTHORIZED), paymentInfo);

		assertEquals("card", paymentInfo.getPaymentMethodType());
		assertEquals("visa", paymentInfo.getCardBrand());
		assertEquals("4242", paymentInfo.getCardLast4());
		assertEquals(Integer.valueOf(12), paymentInfo.getCardExpMonth());
		assertEquals(Integer.valueOf(2030), paymentInfo.getCardExpYear());
		assertEquals(new Date(CAPTURE_BEFORE * 1000L), paymentInfo.getCaptureBefore());
	}

	@Test
	public void otherMethodCaptureBeforeComesFromTheRawResponse()
	{
		copier.copy(retrievedPaymentIntent(PAYPAL_AUTHORIZED), paymentInfo);

		assertEquals("paypal", paymentInfo.getPaymentMethodType());
		assertEquals(new Date(CAPTURE_BEFORE * 1000L), paymentInfo.getCaptureBefore());
	}

	@Test
	public void otherMethodWithoutRawResponseHasNoCaptureBefore()
	{
		copier.copy(paymentIntent(PAYPAL_AUTHORIZED), paymentInfo);

		assertEquals("paypal", paymentInfo.getPaymentMethodType());
		assertNull(paymentInfo.getCaptureBefore());
	}

	@Test
	public void dataThatThePaymentIntentDoesNotHaveIsKept()
	{
		paymentInfo.setPaymentReference("QHJ7RZ2MXN4P");
		paymentInfo.setAmountRemaining(5100L);
		final PaymentIntent succeeded = paymentIntent(BANK_TRANSFER);
		succeeded.setStatus("succeeded");
		succeeded.setNextAction(null);

		copier.copy(succeeded, paymentInfo);

		assertEquals("QHJ7RZ2MXN4P", paymentInfo.getPaymentReference());
		assertEquals("customer_balance", paymentInfo.getPaymentMethodType());
	}

	@Test
	public void paymentIntentWithoutExpandedDataChangesNothing()
	{
		final PaymentIntent bare = new PaymentIntent();
		bare.setId("pi_1");
		bare.setStatus("requires_payment_method");

		copier.copy(bare, paymentInfo);

		assertNull(paymentInfo.getPaymentMethodType());
		assertNull(paymentInfo.getPaymentReference());
	}
}

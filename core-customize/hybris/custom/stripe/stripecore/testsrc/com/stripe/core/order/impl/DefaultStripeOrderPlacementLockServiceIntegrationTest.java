package com.stripe.core.order.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.stripe.core.model.StripeOrderPlacementLockModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.annotation.Resource;
import org.junit.Test;

@IntegrationTest
public class DefaultStripeOrderPlacementLockServiceIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	@Resource
	private ModelService modelService;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

	@Test
	public void lockCanBeAcquiredOnlyOnce()
	{
		assertTrue(stripeOrderPlacementLockService.tryAcquire("pi_lock_once"));
		assertFalse(stripeOrderPlacementLockService.tryAcquire("pi_lock_once"));
	}

	@Test
	public void lockCanBeReacquiredAfterRelease()
	{
		assertTrue(stripeOrderPlacementLockService.tryAcquire("pi_lock_release"));

		stripeOrderPlacementLockService.release("pi_lock_release");

		assertTrue(stripeOrderPlacementLockService.tryAcquire("pi_lock_release"));
	}

	@Test
	public void staleLockIsReclaimed()
	{
		existingLock("pi_lock_stale", Duration.ofMinutes(11));
		record("pi_lock_stale", null);

		assertTrue(stripeOrderPlacementLockService.tryAcquire("pi_lock_stale"));
	}

	@Test
	public void freshLockIsNotReclaimed()
	{
		existingLock("pi_lock_fresh", Duration.ofMinutes(5));
		record("pi_lock_fresh", null);

		assertFalse(stripeOrderPlacementLockService.tryAcquire("pi_lock_fresh"));
	}

	@Test
	public void staleLockOfPlacedOrderIsNotReclaimed()
	{
		existingLock("pi_lock_placed", Duration.ofMinutes(11));
		record("pi_lock_placed", newOrder("order-lock-placed"));

		assertFalse(stripeOrderPlacementLockService.tryAcquire("pi_lock_placed"));
	}

	private void existingLock(final String paymentIntentId, final Duration age)
	{
		final StripeOrderPlacementLockModel lock = modelService.create(StripeOrderPlacementLockModel.class);
		lock.setPaymentIntentId(paymentIntentId);
		lock.setOwnerNode("other-node");
		lock.setCreationtime(Date.from(Instant.now().minus(age)));
		modelService.save(lock);
	}

	private void record(final String paymentIntentId, final OrderModel order)
	{
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(paymentIntentId);
		record.setOrder(order);
		modelService.save(record);
	}

	private OrderModel newOrder(final String code)
	{
		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		modelService.save(order);
		return order;
	}
}

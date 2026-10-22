package com.stripe.core.customer.impl;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCustomerService;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.core.util.StripeDigests;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import de.hybris.platform.commerceservices.enums.CustomerType;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeCustomerService}. Emails are never logged and never part of an idempotency key (a hash is).
 */
public class DefaultStripeCustomerService implements StripeCustomerService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeCustomerService.class);
	private static final int MAX_SYNCED_EMAILS = 10000;

	private StripeGateway stripeGateway;
	private StripeConfigurationService stripeConfigurationService;
	private StripeCustomerDao stripeCustomerDao;
	private ModelService modelService;

	/** Hash of the email last sent to Stripe for a Stripe Customer id, to skip repeated calls. */
	private final Map<String, String> syncedEmails = new ConcurrentHashMap<>();

	@Override
	public Optional<String> customerIdFor(final CartModel cart)
	{
		if (!stripeConfigurationService.isCustomerCreationEnabled() || !(cart.getUser() instanceof CustomerModel))
		{
			return Optional.empty();
		}
		final CustomerModel user = (CustomerModel) cart.getUser();
		if (isGuest(user))
		{
			return Optional.of(guestCustomerId(cart, user));
		}
		return Optional.of(registeredCustomerId(user));
	}

	@Override
	public String replaceCustomer(final CartModel cart, final String missingStripeCustomerId)
	{
		final CustomerModel user = (CustomerModel) cart.getUser();
		syncedEmails.remove(missingStripeCustomerId);
		if (isGuest(user))
		{
			final Customer customer = stripeGateway.createCustomer(guestCreateParams(cart),
					guestKey(cart) + "-replaces-" + missingStripeCustomerId);
			syncEmailBestEffort(customer.getId(), guestEmail(user));
			return customer.getId();
		}
		return replaceRegistered(user, stripeGateway.retrieveAccountKey(), missingStripeCustomerId);
	}

	private String replaceRegistered(final CustomerModel user, final String accountKey, final String missingId)
	{
		syncedEmails.remove(missingId);
		stripeCustomerDao.find(user, accountKey).ifPresent(stored -> modelService.remove(stored));
		return createAndStore(user, accountKey, registeredKey(user, accountKey) + "-replaces-" + missingId);
	}

	protected String registeredCustomerId(final CustomerModel user)
	{
		final String accountKey = stripeGateway.retrieveAccountKey();
		final Optional<StripeCustomerModel> stored = stripeCustomerDao.find(user, accountKey);
		if (stored.isPresent())
		{
			final String id = stored.get().getStripeCustomerId();
			try
			{
				updateEmailIfChanged(id, registeredEmail(user));
			}
			catch (final StripeGatewayException e)
			{
				if (isMissing(e))
				{
					LOG.warn("Stripe customer {} is missing at Stripe: a new one is created", id);
					return replaceRegistered(user, accountKey, id);
				}
				logEmailSyncFailure(e);
			}
			return id;
		}
		return createAndStore(user, accountKey, registeredKey(user, accountKey));
	}

	/**
	 * Creates the Stripe Customer and stores it. When two requests create it at the same time, the stored row of the
	 * other request is used. The email update is best effort: it never loses the customer id.
	 */
	private String createAndStore(final CustomerModel user, final String accountKey, final String idempotencyKey)
	{
		final Customer customer = stripeGateway.createCustomer(
				CustomerCreateParams.builder().putMetadata("userPk", String.valueOf(user.getPk())).build(), idempotencyKey);
		String id = customer.getId();
		try
		{
			final StripeCustomerModel stored = modelService.create(StripeCustomerModel.class);
			stored.setCustomer(user);
			stored.setAccountKey(accountKey);
			stored.setStripeCustomerId(id);
			modelService.save(stored);
		}
		catch (final RuntimeException e)
		{
			final Optional<StripeCustomerModel> other = stripeCustomerDao.find(user, accountKey);
			if (other.isEmpty())
			{
				throw e;
			}
			LOG.info("Stripe customer of user {} was stored by another request: it is used", user.getPk());
			id = other.get().getStripeCustomerId();
		}
		syncEmailBestEffort(id, registeredEmail(user));
		return id;
	}

	/**
	 * The create call has the same parameters on every call (no email), so a repeated call with the same idempotency key
	 * gets the same Stripe Customer back. The email is set by a separate update.
	 */
	protected String guestCustomerId(final CartModel cart, final CustomerModel user)
	{
		final Customer customer = stripeGateway.createCustomer(guestCreateParams(cart), guestKey(cart));
		try
		{
			updateEmailIfChanged(customer.getId(), guestEmail(user));
		}
		catch (final StripeGatewayException e)
		{
			if (isMissing(e))
			{
				return replaceCustomer(cart, customer.getId());
			}
			logEmailSyncFailure(e);
		}
		return customer.getId();
	}

	private void syncEmailBestEffort(final String customerId, final String email)
	{
		try
		{
			updateEmailIfChanged(customerId, email);
		}
		catch (final StripeGatewayException e)
		{
			logEmailSyncFailure(e);
		}
	}

	private void logEmailSyncFailure(final StripeGatewayException e)
	{
		LOG.warn("The email of a Stripe customer was not updated (code={})", e.getStripeCode());
	}

	private boolean isMissing(final StripeGatewayException e)
	{
		return "resource_missing".equals(e.getStripeCode());
	}

	private CustomerCreateParams guestCreateParams(final CartModel cart)
	{
		return CustomerCreateParams.builder().putMetadata("cartCode", cart.getCode()).build();
	}

	private String guestKey(final CartModel cart)
	{
		return "customer-cart-" + cart.getCode();
	}

	private String registeredKey(final CustomerModel user, final String accountKey)
	{
		return "customer-" + user.getPk() + "-" + accountKey;
	}

	private void updateEmailIfChanged(final String customerId, final String email)
	{
		if (email == null)
		{
			return;
		}
		final String hash = StripeDigests.sha256Hex(email);
		if (hash.equals(syncedEmails.get(customerId)))
		{
			return;
		}
		stripeGateway.updateCustomer(customerId, CustomerUpdateParams.builder().setEmail(email).build(),
				"customer-email-" + customerId + "-" + hash);
		remember(customerId, email);
	}

	private void remember(final String customerId, final String email)
	{
		if (syncedEmails.size() >= MAX_SYNCED_EMAILS)
		{
			syncedEmails.clear();
		}
		syncedEmails.put(customerId, StripeDigests.sha256Hex(email));
	}

	protected boolean isGuest(final UserModel user)
	{
		return user instanceof CustomerModel && CustomerType.GUEST.equals(((CustomerModel) user).getType());
	}

	private String guestEmail(final CustomerModel user)
	{
		final String uid = user.getUid();
		if (uid != null && uid.indexOf('|') >= 0)
		{
			final String fromUid = StringUtils.trimToNull(StringUtils.substringAfter(uid, "|"));
			if (fromUid != null)
			{
				return fromUid;
			}
		}
		return StringUtils.trimToNull(user.getContactEmail());
	}

	private String registeredEmail(final CustomerModel user)
	{
		final String contact = StringUtils.trimToNull(user.getContactEmail());
		return contact != null ? contact : StringUtils.trimToNull(user.getUid());
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	public void setStripeCustomerDao(final StripeCustomerDao stripeCustomerDao)
	{
		this.stripeCustomerDao = stripeCustomerDao;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}

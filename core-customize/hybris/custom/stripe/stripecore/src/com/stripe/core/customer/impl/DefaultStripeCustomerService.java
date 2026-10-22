package com.stripe.core.customer.impl;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCustomerService;
import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripeCustomerModel;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import de.hybris.platform.commerceservices.enums.CustomerType;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.model.ModelService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

/**
 * Default {@link StripeCustomerService}. Emails are never logged and never part of an idempotency key (a hash is).
 */
public class DefaultStripeCustomerService implements StripeCustomerService
{
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
			syncGuestEmail(customer.getId(), user);
			return customer.getId();
		}
		final String accountKey = stripeGateway.retrieveAccountKey();
		stripeCustomerDao.find(user, accountKey).ifPresent(stored -> modelService.remove(stored));
		return createAndStore(user, accountKey, registeredKey(user, accountKey) + "-replaces-" + missingStripeCustomerId);
	}

	protected String registeredCustomerId(final CustomerModel user)
	{
		final String accountKey = stripeGateway.retrieveAccountKey();
		final Optional<StripeCustomerModel> stored = stripeCustomerDao.find(user, accountKey);
		if (stored.isPresent())
		{
			final String id = stored.get().getStripeCustomerId();
			updateEmailIfChanged(id, registeredEmail(user));
			return id;
		}
		return createAndStore(user, accountKey, registeredKey(user, accountKey));
	}

	private String createAndStore(final CustomerModel user, final String accountKey, final String idempotencyKey)
	{
		final Customer customer = stripeGateway.createCustomer(
				CustomerCreateParams.builder().putMetadata("userPk", String.valueOf(user.getPk())).build(), idempotencyKey);
		final StripeCustomerModel stored = modelService.create(StripeCustomerModel.class);
		stored.setCustomer(user);
		stored.setAccountKey(accountKey);
		stored.setStripeCustomerId(customer.getId());
		modelService.save(stored);
		updateEmailIfChanged(customer.getId(), registeredEmail(user));
		return customer.getId();
	}

	/**
	 * The create call has the same parameters on every call (no email), so a repeated call with the same idempotency key
	 * gets the same Stripe Customer back. The email is set by a separate update.
	 */
	protected String guestCustomerId(final CartModel cart, final CustomerModel user)
	{
		final Customer customer = stripeGateway.createCustomer(guestCreateParams(cart), guestKey(cart));
		syncGuestEmail(customer.getId(), user);
		return customer.getId();
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

	private void syncGuestEmail(final String customerId, final CustomerModel user)
	{
		updateEmailIfChanged(customerId, guestEmail(user));
	}

	private void updateEmailIfChanged(final String customerId, final String email)
	{
		if (email == null)
		{
			return;
		}
		final String hash = sha256(email);
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
		syncedEmails.put(customerId, sha256(email));
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

	static String sha256(final String value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (final NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is not available", e);
		}
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

package com.stripe.core.site;

import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.site.BaseSiteService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs work in the base site of a cart, an order or a PaymentIntent record, so that
 * {@link com.stripe.core.config.StripeConfigurationService} reads the site values ({@code stripe.<name>.<siteUid>}).
 * Background work (task runners, order process actions, cancel and refund) has no base site in its session. The site
 * is set in a local view of the session: the session keeps its own site after the work. Without a known site, the work
 * runs in the current session and reads the global values.
 */
public class StripeSiteContext
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeSiteContext.class);

	/**
	 * Work that runs in a site.
	 */
	@FunctionalInterface
	public interface SiteWork<T, E extends Exception>
	{
		T run() throws E;
	}

	private SessionService sessionService;
	private BaseSiteService baseSiteService;

	/**
	 * Runs the work in the base site with this uid. A blank or unknown uid runs the work in the current session.
	 */
	public <T, E extends Exception> T runInSite(final String siteUid, final SiteWork<T, E> work) throws E
	{
		if (StringUtils.isBlank(siteUid))
		{
			return work.run();
		}
		final BaseSiteModel site = findSite(siteUid);
		if (site == null)
		{
			LOG.warn("Base site {} is not found: the Stripe settings of the current session are used", siteUid);
			return work.run();
		}
		return runInSite(site, work);
	}

	/**
	 * Runs the work in the site. A null site runs the work in the current session.
	 */
	@SuppressWarnings("unchecked")
	public <T, E extends Exception> T runInSite(final BaseSiteModel site, final SiteWork<T, E> work) throws E
	{
		if (site == null)
		{
			return work.run();
		}
		try
		{
			return sessionService.executeInLocalView(new SessionExecutionBody()
			{
				@Override
				public Object execute()
				{
					baseSiteService.setCurrentBaseSite(site, false);
					try
					{
						return work.run();
					}
					catch (final RuntimeException e)
					{
						throw e;
					}
					catch (final Exception e)
					{
						throw new CheckedFailure(e);
					}
				}
			});
		}
		catch (final CheckedFailure failure)
		{
			throw (E) failure.getCause();
		}
	}

	private BaseSiteModel findSite(final String siteUid)
	{
		try
		{
			return baseSiteService.getBaseSiteForUID(siteUid);
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Base site {} cannot be read: {}", siteUid, e.getMessage());
			return null;
		}
	}

	/**
	 * Carries a checked exception of the work through the session body, which allows only unchecked exceptions.
	 */
	private static final class CheckedFailure extends RuntimeException
	{
		private CheckedFailure(final Exception cause)
		{
			super(cause);
		}
	}

	public void setSessionService(final SessionService sessionService)
	{
		this.sessionService = sessionService;
	}

	public void setBaseSiteService(final BaseSiteService baseSiteService)
	{
		this.baseSiteService = baseSiteService;
	}
}

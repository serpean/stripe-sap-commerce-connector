package com.stripe.sampledata.setup;

import de.hybris.platform.commerceservices.setup.SetupImpexService;
import de.hybris.platform.core.initialization.SystemSetup;
import de.hybris.platform.core.initialization.SystemSetup.Process;
import de.hybris.platform.core.initialization.SystemSetup.Type;


/**
 * Imports the reference sample data that enables Stripe on the electronics store and the OAuth client of the
 * reference storefront. Runs after the electronics store
 * and Spartacus sample data, because this extension requires them.
 */
@SystemSetup(extension = "stripesampledata")
public class StripeSampleDataSystemSetup
{
	private static final String STORE_IMPEX = "/stripesampledata/import/sampledata/stores/electronics/store.impex";
	private static final String PRICES_IMPEX = "/stripesampledata/import/sampledata/stores/electronics/prices.impex";
	private static final String WAREHOUSES_IMPEX = "/stripesampledata/import/sampledata/stores/electronics/warehouses.impex";
	private static final String OAUTH_CLIENTS_IMPEX = "/stripesampledata/import/common/oauth-clients.impex";

	private SetupImpexService setupImpexService;

	@SystemSetup(type = Type.PROJECT, process = Process.ALL)
	public void importStripeSampleData()
	{
		setupImpexService.importImpexFile(STORE_IMPEX, true);
		setupImpexService.importImpexFile(PRICES_IMPEX, true);
		setupImpexService.importImpexFile(WAREHOUSES_IMPEX, true);
		setupImpexService.importImpexFile(OAUTH_CLIENTS_IMPEX, true);
	}

	public void setSetupImpexService(final SetupImpexService setupImpexService)
	{
		this.setupImpexService = setupImpexService;
	}
}

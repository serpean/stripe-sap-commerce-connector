package com.stripe.validators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.errors.StripeOccExceptionHandler;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.validators.StripePlaceOrderCartValidatorTest.SapLikePlaceOrderCartValidator;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;

/**
 * Loads the real stripeocc-web-spring.xml over stand-ins for the SAP OCC web beans, in the same order as SAP's
 * v2-web-spring.xml: SAP's validators first, then the OCC extension files.
 */
@UnitTest
public class StripeOccWebSpringContextTest
{
	private GenericApplicationContext context;
	private CartModel cart;

	@Before
	public void setUp()
	{
		cart = new CartModel();
		final CartService cartService = mock(CartService.class);
		when(cartService.hasSessionCart()).thenReturn(true);
		when(cartService.getSessionCart()).thenReturn(cart);

		context = new GenericApplicationContext();
		context.registerBean("defaultPlaceOrderCartValidator", SapLikePlaceOrderCartValidator.class);
		context.registerAlias("defaultPlaceOrderCartValidator", "placeOrderCartValidator");
		final RootBeanDefinition fieldSetLevelMapping = new RootBeanDefinition(FieldSetLevelMapping.class);
		fieldSetLevelMapping.setAbstract(true);
		context.registerBeanDefinition("fieldSetLevelMapping", fieldSetLevelMapping);
		context.registerBean("cartService", CartService.class, () -> cartService);
		context.registerBean("stripePaymentIntentService", StripePaymentIntentService.class,
				() -> mock(StripePaymentIntentService.class));
		context.registerBean("stripeConfigurationService", StripeConfigurationService.class,
				() -> mock(StripeConfigurationService.class));
		context.registerBean("stripePaymentFacade", StripePaymentFacade.class, () -> mock(StripePaymentFacade.class));
		new XmlBeanDefinitionReader(context).loadBeanDefinitions("classpath:/occ/v2/stripeocc/web/spring/stripeocc-web-spring.xml");
		context.refresh();
	}

	@After
	public void tearDown()
	{
		context.close();
	}

	@Test
	public void placeOrderCartValidatorAliasIsTheStripeProxyOfSapValidator()
	{
		final Object validator = context.getBean("placeOrderCartValidator");

		assertTrue(validator instanceof SapLikePlaceOrderCartValidator);
		assertTrue(AopUtils.isCglibProxy(validator));
	}

	@Test
	public void placeOrderCartValidatorAcceptsStripeCart()
	{
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId("pi_1");
		cart.setPaymentInfo(paymentInfo);
		final SapLikePlaceOrderCartValidator validator = context.getBean("placeOrderCartValidator",
				SapLikePlaceOrderCartValidator.class);
		final CartData cartData = new CartData();
		final Errors errors = new BeanPropertyBindingResult(cartData, "sessionCart");

		validator.validate(cartData, errors);

		assertEquals(List.of("cart.deliveryModeNotSet"),
				errors.getAllErrors().stream().map(ObjectError::getCode).collect(Collectors.toList()));
	}

	@Test
	public void exceptionHandlerAndLevelMappingsAreDefined()
	{
		assertEquals(1, context.getBeansOfType(StripeOccExceptionHandler.class).size());
		final List<String> dtoClasses = context.getBeansOfType(FieldSetLevelMapping.class).values().stream()
				.map(mapping -> mapping.getDtoClass().getSimpleName()).sorted().collect(Collectors.toList());
		assertEquals(List.of("CartWsDTO", "OrderWsDTO", "StripePaymentInfoWsDTO"), dtoClasses);
	}
}

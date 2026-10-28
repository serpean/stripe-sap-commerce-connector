package com.stripe.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash helper. Used for cache keys and idempotency keys that must not hold a secret or an email.
 */
public final class StripeDigests
{
	private StripeDigests()
	{
		// utility class
	}

	public static String sha256Hex(final String value)
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
}

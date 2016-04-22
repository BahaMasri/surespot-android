package com.twofours.surespot.common;

import android.util.Log;
import ch.boye.httpclientandroidlib.client.HttpResponseException;

public class SurespotLog
{
	private static boolean mLogging = SurespotConstants.LOGGING;

	public static void setLogging(boolean logging)
	{
		try
		{
		v("SurespotLog", "setting logging to: %b", logging);
		mLogging = logging;
		}
		catch (Exception e)
		{
		}
	}

	// by using string.format we avoid string concat overhead when logging is disabled
	public static void w(String tag, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging)
		{
			if (msg == null) msg = "";
			Log.w(tag, tag + ": " + String.format(msg, msgArgs));
		}
		}
		catch (Exception e)
		{
		}
	}

	public static void w(String tag, Throwable tr, String msg, Object... msgArgs)
	{
		try
		{
		String message = null;
		if (mLogging) {
			if (msg == null) msg = "";
			message = tag + ": " + String.format(msg, msgArgs);
			// Log.w(tag, msg +", " + tr.getMessage());
			Log.w(tag, message, tr);
		}
		}
		catch (Exception e)
		{
		}
	}

	public static void v(String tag, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging) {
			if (msg == null) msg = "";
			Log.v(tag, tag + ": " + String.format(msg, msgArgs));
		}
		}
		catch (Exception e)
		{
		}

	}

	public static void d(String tag, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging) {
			if (msg == null) msg = "";
			Log.d(tag, tag + ": " + String.format(msg, msgArgs));
		}
		}
		catch (Exception e)
		{
		}

	}

	public static void e(String tag, Throwable tr, String msg, Object... msgArgs)
	{
		try
		{
		String message = null;
		if (mLogging) {
			if (msg == null) msg = "";
			message = tag + ": " + String.format(msg, msgArgs);
			Log.e(tag, message, tr);
		}

		if (tr instanceof HttpResponseException) {
			HttpResponseException error = (HttpResponseException) tr;
			int statusCode = error.getStatusCode();

			// no need to report these
			switch (statusCode) {
			case 400:
			case 401:
			case 403:
			case 404:
			case 409:
				return;
			}
		}
		}
		catch (Exception e)
		{
		}
	}

	public static void i(String tag, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging) {
			if (msg == null) msg = "";
			Log.i(tag, tag + ": " + String.format(msg, msgArgs));
		}
		}
		catch (Exception e)
		{
		}
	}

	public static void i(String tag, Throwable tr, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging) {
			if (msg == null) msg = "";
			Log.i(tag, tag + ": " + String.format(msg, msgArgs), tr);
		}
		}
		catch (Exception e)
		{
		}
	}

	public static void v(String tag, Throwable tr, String msg, Object... msgArgs)
	{
		try
		{
		if (mLogging) {
			if (msg == null) msg = "";
			Log.v(tag, tag + ": " + String.format(msg, msgArgs), tr);
		}
		}
		catch (Exception e)
		{
		}
	}

	public static boolean isLogging()
	{
		return mLogging;
	}
}

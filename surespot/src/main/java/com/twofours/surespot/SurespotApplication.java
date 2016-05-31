package com.twofours.surespot;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.support.multidex.MultiDex;

import com.twofours.surespot.billing.BillingController;
import com.twofours.surespot.chat.ChatController;
import com.twofours.surespot.chat.EmojiParser;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConfiguration;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.images.FileCacheController;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.NetworkController;
import com.twofours.surespot.services.CommunicationService;
import com.twofours.surespot.services.CredentialCachingService;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;

import java.io.IOException;
import java.security.Security;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ReportsCrashes(mode = ReportingInteractionMode.DIALOG,  // will not be used
formUri = "https://www.surespot.me:3000/logs/surespot", resToastText = R.string.crash_toast_text, resDialogText = R.string.crash_dialog_text, resDialogOkToast = R.string.crash_dialog_ok_toast, resDialogCommentPrompt = R.string.crash_dialog_comment_prompt)
public class SurespotApplication extends Application {
	private static final String TAG = "SurespotApplication";
	private static CredentialCachingService mCredentialCachingService;
	private static CommunicationService mCommunicationService;
	private static StateController mStateController = null;
	private static String mVersion;
	private static BillingController mBillingController;
	private static String mUserAgent;
	private static NetworkController mNetworkController = null;
	private static ChatController mChatController = null;
	private static Context mContext;

	public static final int CORE_POOL_SIZE = 24;
	public static final int MAXIMUM_POOL_SIZE = Integer.MAX_VALUE;
	public static final int KEEP_ALIVE = 1;
	private static FileCacheController mFileCacheController;

	public static ChatController getChatController() {
		return mChatController;
	}

	public static void setChatController(ChatController chatController) {
		SurespotApplication.mChatController = chatController;
	}


	protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
	// create our own thread factory to handle message decryption where we have potentially hundreds of messages to decrypt
	// we need a tall queue and a slim pipe
	public static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		public Thread newThread(Runnable r) {
			return new Thread(r, "surespot #" + mCount.getAndIncrement());
		}
	};

	public static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<Runnable>();

	/**
	 * An {@link Executor} that can be used to execute tasks in parallel.
	 */
	public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, sPoolWorkQueue,
			sThreadFactory);

	public void onCreate() {
		super.onCreate();

		mContext = getApplicationContext();

		ACRA.init(this);

		EmojiParser.init(this);

		PackageManager manager = this.getPackageManager();
		PackageInfo info = null;

		try {
			info = manager.getPackageInfo(this.getPackageName(), 0);
			mVersion = info.versionName;
		}
		catch (PackageManager.NameNotFoundException e) {
			mVersion = "unknown";
		}


		mUserAgent = "surespot/" + SurespotApplication.getVersion() + " (Android)";

		Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());

		SurespotConfiguration.LoadConfigProperties(getApplicationContext());
		mStateController = new StateController(this);
		try {
			mFileCacheController = new FileCacheController(this);
		}
		catch (IOException e) {
			SurespotLog.w(TAG, e, "could not create file cache controller");
		}

		boolean oneTimeGotNoCase = Utils.getSharedPrefsBoolean(this, "66onetime");
		if (!oneTimeGotNoCase) {

			//wipe the cache
			StateController.clearCache(this, new IAsyncCallback<Void>() {
				@Override
				public void handleResponse(Void result) {
					SurespotLog.d(TAG, "cache cleared");
					Utils.putSharedPrefsBoolean(SurespotApplication.this, "66onetime", true);
				}
			});

			//set the default theme to black
			//Utils.putSharedPrefsBoolean(SurespotApplication.this,SurespotConstants.PrefNames.BLACK, true);
		}


		SurespotLog.v(TAG, "starting cache service");
		Intent cacheIntent = new Intent(this, CredentialCachingService.class);
		startService(cacheIntent);

		SurespotLog.v(TAG, "starting chat transmission service");
		Intent chatIntent = new Intent(this, CommunicationService.class);
		startService(chatIntent);

		mBillingController = new BillingController(this);
		FileUtils.wipeImageCaptureDir(this);
	}

	private boolean versionChanged(Context context) {

		// Check if app was updated; if so, it must clear the registration ID
		// since the existing regID is not guaranteed to work with the new
		// app version.

		String registeredVersion = Utils.getSharedPrefsString(context, SurespotConstants.PrefNames.APP_VERSION);
		SurespotLog.v(TAG, "registeredversion: %s, currentVersion: %s", registeredVersion, getVersion());
		if (!getVersion().equals(registeredVersion)) {
			SurespotLog.i(TAG, "App version changed.");
			return true;
		}
		return false;
	}

	public static CredentialCachingService getCachingService() {
		return mCredentialCachingService;
	}

	public static NetworkController getNetworkController() {
		if (mNetworkController == null) {
			mNetworkController = new NetworkController();
		}
		return mNetworkController;
	}



	public static CommunicationService getCommunicationService() {
		if (mCommunicationService == null) {
			SurespotLog.w(TAG, "mChatTransmissionServiceWasNull", new NullPointerException("mCommunicationService"));
		}
		return mCommunicationService;
	}

	public static void setCachingService(CredentialCachingService credentialCachingService) {
		SurespotApplication.mCredentialCachingService = credentialCachingService;
	}

	public static void setCommunicationService(CommunicationService communicationService) {
		SurespotApplication.mCommunicationService = communicationService;
	}

	public static StateController getStateController() {
		return mStateController;
	}

	public static String getVersion() {
		return mVersion;
	}

	public static BillingController getBillingController() {
		return mBillingController;
	}

	public static String getUserAgent() {
		return mUserAgent;
	}

	public static CommunicationService getCommunicationServiceNoThrow() {
		return mCommunicationService;
	}

	public static Context getContext() {
		return mContext;
	}

	public static FileCacheController getFileCacheController() {
		return mFileCacheController;
	}
}

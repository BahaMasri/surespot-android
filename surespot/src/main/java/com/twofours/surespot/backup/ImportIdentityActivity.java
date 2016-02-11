package com.twofours.surespot.backup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.MenuItem;









import com.twofours.surespot.R;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.identity.IdentityOperationResult;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.ui.SingleProgressDialog;
import com.twofours.surespot.ui.UIUtils;

public class ImportIdentityActivity extends SherlockActivity {
	private static final String TAG = null;
	private boolean mSignup;

	private TextView mAccountNameDisplay;
	private boolean mShowingLocal;
	
	private ListView mDriveListview;
	private SingleProgressDialog mSpd;
	private SingleProgressDialog mSpdLoadIdentities;
	public static final String[] ACCOUNT_TYPE = new String[] { "dummy" };
	private static final String ACTION_DRIVE_OPEN = "com.google.android.apps.drive.DRIVE_OPEN";
	private static final String EXTRA_FILE_ID = "resourceId";
	private String mFileId;
	private int mMode;
	private static final int MODE_NORMAL = 0;
	private static final int MODE_DRIVE = 1;
	private ViewSwitcher mSwitcher;
	private SimpleAdapter mDriveAdapter;
	private AlertDialog mDialog;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_import_identity);
		Utils.configureActionBar(this, getString(R.string.identity), getString(R.string.restore), true);

		Intent intent = getIntent();
		
		mMode = MODE_NORMAL;
		mSwitcher = (ViewSwitcher) findViewById(R.id.restoreViewSwitcher);
		RadioButton rbRestoreLocal = (RadioButton) findViewById(R.id.rbRestoreLocal);
		mShowingLocal = true;
		rbRestoreLocal.setTag("local");
		rbRestoreLocal.setChecked(true);
		setupLocal();
	}

	private void setupLocal()
	{

		ListView lvIdentities = (ListView) findViewById(R.id.lvLocalIdentities);
		lvIdentities.setEmptyView(findViewById(R.id.no_local_identities));

		List<HashMap<String, String>> items = new ArrayList<HashMap<String, String>>();

		// query the filesystem for identities
		final File exportDir = FileUtils.getIdentityExportDir();
		File[] files = IdentityController.getExportIdentityFiles(this, exportDir.getPath());

		TextView tvLocalLocation = (TextView) findViewById(R.id.restoreLocalLocation);

		if (files != null) {
			TreeMap<Long, File> sortedFiles = new TreeMap<Long, File>(new Comparator<Long>() {
				public int compare(Long o1, Long o2) {
					return o2.compareTo(o1);
				}
			});

			for (File file : files) {
				sortedFiles.put(file.lastModified(), file);
			}

			for (File file : sortedFiles.values()) {
				long lastModTime = file.lastModified();
				String date = DateFormat.getDateFormat(this).format(lastModTime) + " " + DateFormat.getTimeFormat(this).format(lastModTime);

				HashMap<String, String> map = new HashMap<String, String>();
				map.put("name", IdentityController.getIdentityNameFromFile(file));
				map.put("date", date);
				items.add(map);
			}
		}

		final SimpleAdapter adapter = new SimpleAdapter(this, items, R.layout.identity_item, new String[] { "name", "date" }, new int[] {
				R.id.identityBackupName, R.id.identityBackupDate });
		tvLocalLocation.setText(exportDir.toString());
		lvIdentities.setVisibility(View.VISIBLE);

		lvIdentities.setAdapter(adapter);
		lvIdentities.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				if (IdentityController.getIdentityCount(ImportIdentityActivity.this) >= SurespotConstants.MAX_IDENTITIES) {
					Utils.makeLongToast(ImportIdentityActivity.this, getString(R.string.login_max_identities_reached, SurespotConstants.MAX_IDENTITIES));
					return;
				}

				@SuppressWarnings("unchecked")
				Map<String, String> map = (Map<String, String>) adapter.getItem(position);

				final String user = map.get("name");			

				// make sure file we're going to save to is writable before we
				// start
				if (!IdentityController.ensureIdentityFile(ImportIdentityActivity.this, user, true)) {
					Utils.makeToast(ImportIdentityActivity.this, getString(R.string.could_not_import_identity));
					if (mMode == MODE_DRIVE) {
						finish();
					}
					return;
				}

				UIUtils.passwordDialog(ImportIdentityActivity.this, getString(R.string.restore_identity, user), getString(R.string.enter_password_for, user),
						new IAsyncCallback<String>() {
							@Override
							public void handleResponse(String result) {
								if (!TextUtils.isEmpty(result)) {
									IdentityController.importIdentity(ImportIdentityActivity.this, exportDir, user, result,
											new IAsyncCallback<IdentityOperationResult>() {

												@Override
												public void handleResponse(IdentityOperationResult response) {

													Utils.makeLongToast(ImportIdentityActivity.this, response.getResultText());

													if (response.getResultSuccess()) {
														// if launched
														// from
														// signup and
														// successful
														// import, go to
														// login
														// screen
														if (mSignup) {
															IdentityController.logout();

															Intent intent = new Intent(ImportIdentityActivity.this, MainActivity.class);
															intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
															startActivity(intent);
														}

													}

												}
											});
								}
								else {
									Utils.makeToast(ImportIdentityActivity.this, getString(R.string.no_identity_imported));
								}

							}
						});

			}

		});

	}

	private void restoreExternal(boolean firstTime) {
	}

	private void populateDriveIdentities(boolean firstAttempt) {
	}

/*
	private ChildList getIdentityFiles(String identityDirId) {
		ChildList identityFileList = null;
		return identityFileList;
	}
*/
	public String ensureDriveIdentityDirectory() {
		String identityDirId = null;
		return identityDirId;
	}

	// //////// DRIVE
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
	}

	private void chooseAccount(boolean ask) {
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			finish();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (mDialog != null && mDialog.isShowing()) {
			mDialog.dismiss();
		}
	}
}

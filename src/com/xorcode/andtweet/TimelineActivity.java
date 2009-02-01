/* 
 * Copyright (C) 2008 Torgny Bjers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xorcode.andtweet;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.MultiAutoCompleteTextView;


/**
 * @author torgny.bjers
 */
public class TimelineActivity extends ListActivity implements ITimelineActivity {

	public static String TAG = "TimelineActivity";

	// Handler message codes
	public static final int MSG_TWEETS_CHANGED = 1;
	public static final int MSG_DATA_LOADING = 2;
	public static final int MSG_UPDATE_STATUS = 3;
	public static final int MSG_MANUAL_RELOAD = 4;
	public static final int MSG_AUTHENTICATION_ERROR = 5;
	public static final int MSG_LOAD_ITEMS = 6;
	public static final int MSG_DIRECT_MESSAGES_CHANGED = 7;

	// Handler message status codes
	public static final int STATUS_LOAD_ITEMS_FAILURE = 0;
	public static final int STATUS_LOAD_ITEMS_SUCCESS = 1;

	// Dialog identifier codes
	public static final int DIALOG_AUTHENTICATION_FAILED = 1;
	public static final int DIALOG_SENDING_MESSAGE = 2;

	// Intent bundle result keys
	public static final String INTENT_RESULT_KEY_AUTHENTICATION = "authentication";

	// Bundle identifier keys
	public static final String BUNDLE_KEY_REPLY_ID = "replyId";
	public static final String BUNDLE_KEY_CURRENT_PAGE = "currentPage";
	public static final String BUNDLE_KEY_IS_LOADING = "isLoading";

	// Options menu items
	public static final int OPTIONS_MENU_PREFERENCES = Menu.FIRST;
	public static final int OPTIONS_MENU_MORE = Menu.FIRST + 1;
	public static final int OPTIONS_MENU_RELOAD = Menu.FIRST + 2;
	public static final int OPTIONS_MENU_MORE_SWITCH_TIMELINE = Menu.FIRST + 3;

	// Views and widgets
	protected LinearLayout mListFooter;

	protected Cursor mCursor;
	protected NotificationManager mNM;
	protected IAndTweetService mService;
	protected SharedPreferences mSP;
	protected ProgressDialog mProgressDialog;
	protected Handler mHandler;

	protected int mCurrentPage = 1;
	protected int mTotalItemCount = 0;

	protected boolean mIsBound;
	protected boolean mIsLoading;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Set up preference manager
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
		mSP = PreferenceManager.getDefaultSharedPreferences(this);

		// Make sure the window has access to progress animation
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

		String username = mSP.getString("twitter_username", null);
		String password = mSP.getString("twitter_password", null);
		if (username == null || "".equals(username) || password == null || "".equals(password)) {
			startActivity(new Intent(this, SplashActivity.class));
			finish();
		}

		loadTheme();
		setContentView(R.layout.tweetlist);

		// Set up notification manager
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
	}

	@Override
	protected void onStart() {
		super.onStart();
		mNM.cancelAll();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		mNM.cancelAll();
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case DIALOG_AUTHENTICATION_FAILED:
			return new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.dialog_title_authentication_failed)
				.setMessage(R.string.dialog_summary_authentication_failed)
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface Dialog, int whichButton) {
						startActivity(new Intent(TimelineActivity.this, PreferencesActivity.class));
					}
				}).create();

		case DIALOG_SENDING_MESSAGE:
			mProgressDialog = new ProgressDialog(this);
			mProgressDialog.setTitle(R.string.dialog_title_sending_message);
			mProgressDialog.setMessage(getText(R.string.dialog_summary_sending_message));
			return mProgressDialog;

		default:
			return super.onCreateDialog(id);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);

		int order = 0;
		int groupId = Menu.NONE;

		menu.add(groupId, OPTIONS_MENU_PREFERENCES, order++, R.string.options_menu_preferences)
			.setShortcut('1', 'p')
			.setIcon(android.R.drawable.ic_menu_preferences);

		menu.add(groupId, OPTIONS_MENU_RELOAD, order++, R.string.options_menu_reload)
			.setShortcut('2', 'r')
			.setIcon(android.R.drawable.ic_menu_rotate);

		SubMenu more = menu.addSubMenu(groupId, OPTIONS_MENU_MORE, order++, R.string.options_menu_more)
			.setIcon(android.R.drawable.ic_menu_more);
		more.add(groupId, OPTIONS_MENU_MORE_SWITCH_TIMELINE, order++, "");

		Intent intent = new Intent(null, getIntent().getData());
		intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
		menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0, new ComponentName(this, TweetListActivity.class), null, intent, 0, null);

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case OPTIONS_MENU_PREFERENCES:
			startActivity(new Intent(this, PreferencesActivity.class));
			break;
		}
		return super.onOptionsItemSelected(item);
	}

	public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {}

	public boolean onKey(View v, int keyCode, KeyEvent event) {
		return false;
	}

	public void onScroll(AbsListView view, int firstVisibleItem,
			int visibleItemCount, int totalItemCount) {}

	public void onScrollStateChanged(AbsListView view, int scrollState) {}

	/**
	 * Load the theme for preferences.
	 */
	public void loadTheme() {
		boolean light = mSP.getBoolean("appearance_light_theme", false);
		StringBuilder theme = new StringBuilder();
		String name = mSP.getString("theme", "AndTweet");
		if (name.indexOf("Theme.") > -1) {
			name = name.substring(name.indexOf("Theme."));
		}
		theme.append("Theme.");
		if (light) {
			theme.append("Light.");
		}
		theme.append(name);
		setTheme((int) getResources().getIdentifier(theme.toString(), "style", "com.xorcode.andtweet"));
	}

	/**
	 * Retrieve the text that is currently in the editor.
	 * 
	 * @return Text currently in the editor
	 */
	protected CharSequence getSavedText() {
		return ((MultiAutoCompleteTextView) findViewById(R.id.messageEditTextAC)).getText();
	}

	/**
	 * Set the text in the text editor.
	 * 
	 * @param text
	 */
	protected void setSavedText(CharSequence text) {
		((MultiAutoCompleteTextView) findViewById(R.id.messageEditTextAC)).setText(text);
	}

	/**
	 * Initialize the user interface.
	 */
	protected void initUI() {
		// Attach listeners to the message list
		getListView().setOnCreateContextMenuListener(this);
		getListView().setOnItemClickListener(this);
	}

	/**
	 * Initialize service and bind to it.
	 */
	protected void bindToService() {
		if (mSP.contains("automatic_updates") && mSP.getBoolean("automatic_updates", false)) {
			Log.d(TAG, "Automatic updates enabled");
			Intent serviceIntent = new Intent(IAndTweetService.class.getName());
			if (!mIsBound) {
				startService(serviceIntent);
				mIsBound = true;
			}
			bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
		}
	}

	/**
	 * Disconnect and unregister the service.
	 */
	protected void disconnectService() {
		if (mIsBound) {
			if (mService != null) {
				try {
					mService.unregisterCallback(mServiceCallback);
				} catch (RemoteException e) {
					// Service crashed, not much we can do.
				}
			}
			unbindService(mConnection);
			mIsBound = false;
		}
	}

	/**
	 * Disconnects from the service and stops it.
	 */
	protected void destroyService() {
		disconnectService();
		stopService(new Intent(IAndTweetService.class.getName()));
		mService = null;
		mIsBound = false;
	}

	/**
	 * Service connection handler.
	 */
	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName name, IBinder service) {
			mService = IAndTweetService.Stub.asInterface(service);
			// We want to monitor the service for as long as we are
			// connected to it.
			try {
				mService.registerCallback(mServiceCallback);
			} catch (RemoteException e) {
				// Service has already crashed, nothing much we can do
				// except hope that it will restart.
			}
		}

		public void onServiceDisconnected(ComponentName name) {
			mService = null;
		}
	};

	/**
	 * Service callback handler.
	 */
	protected IAndTweetServiceCallback mServiceCallback = new IAndTweetServiceCallback.Stub() {
		/**
		 * Tweets changed callback method
		 * 
		 * @param value
		 * @throws RemoteException
		 */
		public void tweetsChanged(int value) throws RemoteException {
			mHandler.sendMessage(mHandler.obtainMessage(MSG_TWEETS_CHANGED, value, 0));
		}

		/**
		 * dataLoading callback method
		 * 
		 * @param value
		 * @throws RemoteException
		 */
		public void dataLoading(int value) throws RemoteException {
			mHandler.sendMessage(mHandler.obtainMessage(MSG_DATA_LOADING, value, 0));
		}


		/**
		 * Messages changed callback method
		 * 
		 * @param value
		 * @throws RemoteException
		 */
		public void messagesChanged(int value) throws RemoteException {
			mHandler.sendMessage(mHandler.obtainMessage(MSG_DIRECT_MESSAGES_CHANGED, value, 0));
		}
	};
}

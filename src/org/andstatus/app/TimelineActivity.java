/* 
 * Copyright (c) 2011-2014 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.SearchRecentSuggestions;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.andstatus.app.account.AccountSelector;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.account.MyAccount.CredentialsVerificationStatus;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.MyPreferenceActivity;
import org.andstatus.app.context.MyPreferences;
import org.andstatus.app.data.AccountUserIds;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyDatabase.Msg;
import org.andstatus.app.data.MyDatabase.MsgOfUser;
import org.andstatus.app.data.MyDatabase.User;
import org.andstatus.app.data.MyProvider;
import org.andstatus.app.data.TimelineSearchSuggestionProvider;
import org.andstatus.app.data.TimelineTypeEnum;
import org.andstatus.app.data.TimelineViewBinder;
import org.andstatus.app.net.Connection.ApiRoutineEnum;
import org.andstatus.app.service.CommandData;
import org.andstatus.app.service.CommandEnum;
import org.andstatus.app.service.MyService;
import org.andstatus.app.service.MyServiceListener;
import org.andstatus.app.service.MyServiceManager;
import org.andstatus.app.service.MyServiceReceiver;
import org.andstatus.app.support.android.v11.app.MyLoader;
import org.andstatus.app.support.android.v11.app.MyLoaderManager;
import org.andstatus.app.support.android.v11.widget.MySimpleCursorAdapter;
import org.andstatus.app.util.InstanceId;
import org.andstatus.app.util.MyLog;

import java.util.ArrayList;
import java.util.List;

/**
 * @author yvolk@yurivolkov.com, torgny.bjers
 */
public class TimelineActivity extends ListActivity implements MyServiceListener, OnScrollListener, OnItemClickListener, ActionableMessageList, MyLoaderManager.LoaderCallbacks<Cursor> {
    private static final int DIALOG_ID_TIMELINE_TYPE = 9;

    private static final String KEY_LAST_POSITION = "last_position_";

    /**
     * Visibility of the layout indicates whether Messages are being loaded into the list (asynchronously...)
     * The layout appears at the bottom of the list of messages 
     * when new items are being loaded into the list 
     */
    private LinearLayout loadingLayout;

    /** Parameters of currently shown Timeline */
    private TimelineListParameters listParameters = new TimelineListParameters();

    private NotificationManager mNM;
    
    /**
     * Msg are being loaded into the list starting from one page. More Msg
     * are being loaded in a case User scrolls down to the end of list.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * Is saved position restored (or some default positions set)?
     */
    private boolean positionRestored = false;
    
    /**
     * The is no more items in the query, so don't try to load more pages
     */
    private boolean noMoreItems = false;
    
    /**
     * For testing purposes
     */
    private int instanceId = 0;
    MyServiceReceiver serviceConnector;

    /**
     * We are going to finish/restart this Activity (e.g. onResume or even onCreate)
     */
    private volatile boolean isFinishing = false;

    /**
     * Timeline type
     */
    private TimelineTypeEnum timelineType = TimelineTypeEnum.UNKNOWN;

    /**
     * Is the timeline combined? (Timeline shows messages from all accounts)
     */
    private boolean timelineCombined = false;
    
    /**
     * UserId of the MyAccount, for which we show the activity
     */
    private long currentMyAccountUserId = 0;
    
    /**
     * Selected User for the {@link TimelineTypeEnum#USER} timeline.
     * This is either User Id of current account OR user id of any other selected user.
     * So it's never == 0 for the {@link TimelineTypeEnum#USER} timeline
     */
    private long selectedUserId = 0;

    /**
     * The string is not empty if this timeline is filtered using query string
     * ("Mentions" are not counted here because they have separate TimelineType)
     */
    private String searchQuery = "";

    /**
     * Time when shared preferences where changed
     */
    private long preferencesChangeTime = 0;

    private MessageContextMenu contextMenu;
    private MessageEditor messageEditor;

    private static final int LOADER_ID = 1;
    private MyLoaderManager<Cursor> loaderManager = null;
    
    private boolean isLoading() {
        return loadingLayout.getVisibility() == View.VISIBLE;
    }
    
    private void setLoading(boolean loading) {
        if (isLoading() != loading && !isFinishing()) {
            MyLog.v(this, "isLoading set to " + loading + ", instanceId=" + instanceId );
            if (loading) {
                loadingLayout.setVisibility(View.VISIBLE);
            } else {
                loadingLayout.setVisibility(View.INVISIBLE);
            }
        }
    }
    
    /**
     * This method is the first of the whole application to be called 
     * when the application starts for the very first time.
     * So we may put some Application initialization code here. 
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);    // Before loading the content view
        super.onCreate(savedInstanceState);
        if (instanceId == 0) {
            instanceId = InstanceId.next();
        } else {
            MyLog.d(this, "onCreate reuse the same instanceId=" + instanceId);
        }

        preferencesChangeTime = MyContextHolder.initialize(this, this);
        
        if (MyLog.isLoggable(this, MyLog.DEBUG)) {
            MyLog.d(this, "onCreate instanceId=" + instanceId 
                    + " , preferencesChangeTime=" + preferencesChangeTime
                    + (MyContextHolder.get().isReady() ? "" : ", MyContext is not ready")
                    );
        }
        if (HelpActivity.startFromActivity(this)) {
            return;
        }

        currentMyAccountUserId = MyContextHolder.get().persistentAccounts().getCurrentAccountUserId();
        serviceConnector = new MyServiceReceiver(this);
        
        MyPreferences.loadTheme(this, this);
        setContentView(R.layout.timeline);

        ViewGroup messageListParent = (ViewGroup) findViewById(R.id.messageListParent);
        // We use "this" as a context, otherwise custom styles are not recognized...
        LayoutInflater inflater = LayoutInflater.from(this);
        ViewGroup actionsView = (ViewGroup) inflater.inflate(R.layout.timeline_actions, null);
        messageListParent.addView(actionsView, 0);

        contextMenu = new MessageContextMenu(this);
        
        messageEditor = new MessageEditor(this);
        // TODO: Maybe this should be a parameter
        messageEditor.hide();

        boolean isInstanceStateRestored = false;
        if (savedInstanceState != null) {
            TimelineTypeEnum timelineTypeNew = TimelineTypeEnum.load(savedInstanceState
                    .getString(IntentExtra.EXTRA_TIMELINE_TYPE.key));
            if (timelineTypeNew != TimelineTypeEnum.UNKNOWN) {
                isInstanceStateRestored = true;
                timelineType = timelineTypeNew;
                messageEditor.loadState(savedInstanceState);
                contextMenu.loadState(savedInstanceState);
                if (savedInstanceState.containsKey(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key)) {
                    timelineCombined = savedInstanceState.getBoolean(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key);
                }
                if (savedInstanceState.containsKey(SearchManager.QUERY)) {
                    searchQuery = notNullString(savedInstanceState.getString(SearchManager.QUERY));
                }
                if (savedInstanceState.containsKey(IntentExtra.EXTRA_SELECTEDUSERID.key)) {
                    selectedUserId = savedInstanceState.getLong(IntentExtra.EXTRA_SELECTEDUSERID.key);
                }
            }
        }

        // Set up notification manager
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        loaderManager = new MyLoaderManager<Cursor>();
        
        // Create list footer to show the progress of message loading
        loadingLayout = (LinearLayout) inflater.inflate(R.layout.item_loading, null);
        getListView().addFooterView(loadingLayout);
        createListAdapter(new MatrixCursor(getProjection()));

        // Attach listeners to the message list
        getListView().setOnScrollListener(this);
        getListView().setOnCreateContextMenuListener(contextMenu);
        getListView().setOnItemClickListener(this);

        Button accountButton = (Button) findViewById(R.id.selectAccountButton);
        accountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MyContextHolder.get().persistentAccounts().size() > 1) {
                    Intent i = new Intent(TimelineActivity.this, AccountSelector.class);
                    startActivityForResult(i, ActivityRequestCode.SELECT_ACCOUNT.id);
                }
            }
        });

        if (!isInstanceStateRestored) {
            timelineCombined = MyPreferences.getDefaultSharedPreferences().getBoolean(MyPreferences.KEY_TIMELINE_IS_COMBINED, false);
            processNewIntent(getIntent());
        }
        updateThisOnChangedParameters();
    }

    /**
     * Switch combined timeline on/off
     * @param view combinedTimelineToggle
     */
    public void onCombinedTimelineToggle(View view) {
        if (view instanceof android.widget.ToggleButton) {
            boolean on = ((android.widget.ToggleButton) view).isChecked();
            MyPreferences.getDefaultSharedPreferences().edit().putBoolean(MyPreferences.KEY_TIMELINE_IS_COMBINED, on).commit();
            contextMenu.switchTimelineActivity(timelineType, on, currentMyAccountUserId);
        }
    }
    
    public void onTimelineTypeButtonClick(View view) {
        showDialog(DIALOG_ID_TIMELINE_TYPE);
    }
    
    /**
     * See <a href="http://developer.android.com/guide/topics/search/search-dialog.html">Creating 
     * a Search Interface</a>
     */
    @Override
    public boolean onSearchRequested() {
        return onSearchRequested(false);
    }

    private boolean onSearchRequested(boolean appGlobalSearch) {
        final String method = "onSearchRequested";
        Bundle appSearchData = new Bundle();
        appSearchData.putString(IntentExtra.EXTRA_TIMELINE_TYPE.key, timelineType.save());
        appSearchData.putBoolean(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key, timelineCombined);
        appSearchData.putLong(IntentExtra.EXTRA_SELECTEDUSERID.key, selectedUserId);
        appSearchData.putBoolean(IntentExtra.EXTRA_GLOBAL_SEARCH.key, appGlobalSearch);
        MyLog.v(this, method  + ": " + appSearchData);
        startSearch(null, false, appSearchData, false);
        return true;
    }
    
    @Override
    protected void onResume() {
        String method = "onResume";
        super.onResume();
        MyLog.v(this, method + ", instanceId=" + instanceId);
        if (!isFinishing) {
            if (MyContextHolder.get().persistentAccounts().getCurrentAccount() != null) {
                long preferencesChangeTimeNew = MyContextHolder.initialize(this, this);
                if (preferencesChangeTimeNew != preferencesChangeTime) {
                    MyLog.v(this, method + "; Restarting this Activity to apply all new changes of preferences");
                    finish();
                    contextMenu.switchTimelineActivity(timelineType, timelineCombined, selectedUserId);
                }
            } else { 
                MyLog.v(this, method + "; Finishing this Activity because there is no Account selected");
                finish();
            }
        }
        if (!isFinishing) {
            serviceConnector.registerReceiver(this);
            loaderManager.onResumeActivity(LOADER_ID);
        }
    }

    /**
     * Save current position per User and per TimeleneType. 
     * The position is NOT saved (i.e. the stored position remains the same),
     * if there are no items in the list. Actually we save two Item IDs.
     * 1. The first visible item. 
     * 2. The last item we should retrieve before restoring the position.
     */
    private void saveListPosition() {
        final String method = "saveListPosition";
        android.widget.ListAdapter la = getListView().getAdapter();
        if (la == null) {
            MyLog.v(this, method + " skipped: no ListAdapter");
            return;
        }
        if (listParameters.isEmpty()) {
            MyLog.v(this, method + " skipped: no listParameters");
            return;
        }

        int firstVisiblePosition = getListView().getFirstVisiblePosition();
        // Don't count a footer
        int itemCount = la.getCount() - 1;
        if (firstVisiblePosition >= itemCount) {
            firstVisiblePosition = itemCount - 1;
        }
        long firstVisibleItemId = 0;
        int lastPosition = -1;
        long lastItemId = 0;
        if (firstVisiblePosition >= 0) {
            firstVisibleItemId = la.getItemId(firstVisiblePosition);
            MyLog.v(this, method + " firstVisiblePos:" + firstVisiblePosition + " of " + itemCount
                    + "; itemId:" + firstVisibleItemId);
            // We will load one more "page of messages" below (older) current top item
            lastPosition = firstVisiblePosition + PAGE_SIZE;
            if (lastPosition >= itemCount) {
                lastPosition = itemCount - 1;
            }
            if (lastPosition >= 0) {
                lastItemId = la.getItemId(lastPosition);
            }
        }

        ListPositionStorage ps = new ListPositionStorage(listParameters);
        if (firstVisibleItemId <= 0) {
            MyLog.v(this, method + " failed: no visible items for \"" + ps.accountGuid + "\"; " + ps.keyFirst);
        } else {
            ps.put(firstVisibleItemId, lastItemId);

            if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                MyLog.v(this, method + " succeeded \"" + ps.accountGuid + "\"; " + ps.keyFirst + "="
                        + firstVisibleItemId + ", pos=" + firstVisiblePosition + "; lastId="
                        + lastItemId + ", pos=" + lastPosition);
            }
        }
    }
    
    /**
     * Determines where to save / retrieve position in the list
     * Two rows are always stored for each position hence two keys. 
     * Plus Query string is being stored for the search results.
     * @author yvolk@yurivolkov.com
     */
    private static class ListPositionStorage {
        /**
         * MyAccount for SharedPreferences ( =="" for DefaultSharedPreferences) 
         */
        private String accountGuid = "";
        /**
         * SharePreferences to use for storage 
         */
        private SharedPreferences sp = null;
        /**
         * Key name for the first visible item
         */
        private String keyFirst = "";
        /**
         * Key for the last item we should retrieve before restoring position
         */
        private String keyLast = "";
        private String queryString;
        /**
         * Key for the Query string
         */
        private String keyQueryString = "";
        
        private ListPositionStorage(TimelineListParameters listParameters) {
            queryString = listParameters.searchQuery; 
            if ((listParameters.timelineType != TimelineTypeEnum.USER) && !listParameters.timelineCombined) {
                MyAccount ma = MyContextHolder.get().persistentAccounts().fromUserId(listParameters.myAccountUserId);
                if (ma != null) {
                    sp = ma.getAccountPreferences();
                    accountGuid = ma.getAccountName();
                } else {
                    MyLog.e(this, "No accoount for IserId=" + listParameters.myAccountUserId);
                }
            }
            if (sp == null) {
                sp = MyPreferences.getDefaultSharedPreferences();
            }
            
            keyFirst = KEY_LAST_POSITION
                    + listParameters.timelineType.save()
                    + (listParameters.timelineType == TimelineTypeEnum.USER ? "_user"
                            + Long.toString(listParameters.selectedUserId) : "") + (TextUtils.isEmpty(queryString) ? "" : "_search");
            keyLast = keyFirst + "_last";
            keyQueryString = KEY_LAST_POSITION + listParameters.timelineType.save() + "_querystring";
        }

        private void put(long firstVisibleItemId, long lastRetrievedItemId) {
            sp.edit().putLong(keyFirst, firstVisibleItemId)
            .putLong(keyLast, lastRetrievedItemId)
            .putString(keyQueryString, queryString).commit();
        }

        private static final long ID_NOT_FOUND_IN_LIST_POSITION_STORAGE = -4;
        private static final long ID_NOT_FOUND_IN_SHARED_PREFERENCES = -1;
        private long getFirst() {
            long savedItemId = ID_NOT_FOUND_IN_LIST_POSITION_STORAGE;
            if (isThisPositionStored()) {
                savedItemId = sp.getLong(keyFirst, ID_NOT_FOUND_IN_SHARED_PREFERENCES);
            }
            return savedItemId;
        }
        
        private boolean isThisPositionStored() {
            return queryString.compareTo(sp.getString(
                            keyQueryString, "")) == 0;
        }

        private long getLast() {
            long savedItemId = ID_NOT_FOUND_IN_LIST_POSITION_STORAGE;
            if (isThisPositionStored()) {
                savedItemId = sp.getLong(keyLast, ID_NOT_FOUND_IN_SHARED_PREFERENCES);
            }
            return savedItemId;
        }
        
        private void clear() {
            sp.edit().remove(keyFirst).remove(keyLast)
                    .remove(keyQueryString).commit();
            if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                MyLog.v(this, "Position forgot   \"" + accountGuid + "\"; " + keyFirst);
            }
        }
    }
    
    /**
     * Restore (First visible item) position saved for this user and for this type of timeline
     */
    private void restoreListPosition() {
        final String method = "restoreListPosition";
        ListPositionStorage ps = new ListPositionStorage(listParameters);
        boolean loaded = false;
        int scrollPos = -1;
        long firstItemId = -3;
        try {
            firstItemId = ps.getFirst();
            if (firstItemId > 0) {
                scrollPos = listPosForId(firstItemId);
            }
            if (scrollPos >= 0) {
                getListView().setSelectionFromTop(scrollPos, 0);
                loaded = true;
            } else {
                // There is no stored position
                if (TextUtils.isEmpty(searchQuery)) {
                    scrollPos = getListView().getCount() - 2;
                } else {
                    // In search mode start from the most recent message!
                    scrollPos = 0;
                }
                if (scrollPos >= 0) {
                    setSelectionAtBottom(scrollPos);
                }
            }
        } catch (Exception e) {
            MyLog.v(this, method, e);
        }
        if (loaded) {
            if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                MyLog.v(this, method + " succeeded \"" + ps.accountGuid + "\"; " + ps.keyFirst + "="
                        + firstItemId +"; index=" + scrollPos);
            }
        } else {
            if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                MyLog.v(this, method + " failed \"" + ps.accountGuid + "\"; " + ps.keyFirst + "="
                        + firstItemId);
            }
            ps.clear();
        }
        positionRestored = true;
    }

    private void setSelectionAtBottom(int scrollPos) {
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "setSelectionAtBottom, 1");
        }
        int viewHeight = getListView().getHeight();
        int childHeight;
        childHeight = 30;
        int y = viewHeight - childHeight;
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "set position of last item to " + y + "px");
        }
        getListView().setSelectionFromTop(scrollPos, y);
    }

    /**
     * Returns the position of the item with the given ID.
     * 
     * @param searchedId the ID of the item whose position in the list is to be
     *            returned.
     * @return the position in the list or -1 if the item was not found
     */
    private int listPosForId(long searchedId) {
        int listPos;
        boolean itemFound = false;
        ListView lv = getListView();
        int itemCount = lv.getCount();
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "item count: " + itemCount);
        }
        for (listPos = 0; !itemFound && (listPos < itemCount); listPos++) {
            long itemId = lv.getItemIdAtPosition(listPos);
            if (itemId == searchedId) {
                itemFound = true;
                break;
            }
        }

        if (!itemFound) {
            listPos = -1;
        }
        return listPos;
    }

    @Override
    public void onContentChanged() {
        if (MyLog.isLoggable(this, MyLog.DEBUG)) {
            MyLog.d(this, "onContentChanged started");
        }
        super.onContentChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "onPause, instanceId=" + instanceId);
        }
        serviceConnector.unregisterReceiver(this);
        loaderManager.onPauseActivity(LOADER_ID);

        if (positionRestored) {
            // Get rid of the "fast scroll thumb"
            ((ListView) findViewById(android.R.id.list)).setFastScrollEnabled(false);
            clearNotifications();
            if (!isLoading()) {
                saveListPosition();
            }
            positionRestored = false;
        }        
    }
   
    /**
     *  Cancel notifications of loading timeline, which were set during Timeline downloading 
     */
    private void clearNotifications() {
        try {
            // TODO: Check if there are any notifications
            // and if none than don't waist time for this:

            mNM.cancel(CommandEnum.NOTIFY_HOME_TIMELINE.ordinal());
            mNM.cancel(CommandEnum.NOTIFY_MENTIONS.ordinal());
            mNM.cancel(CommandEnum.NOTIFY_DIRECT_MESSAGE.ordinal());

            // Reset notifications on AppWidget(s)
            Intent intent = new Intent(MyService.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(IntentExtra.EXTRA_MSGTYPE.key, CommandEnum.NOTIFY_CLEAR.save());
            sendBroadcast(intent);
        } finally {
            // Nothing yet...
        }
    }

    @Override
    public void onDestroy() {
        MyLog.v(this,"onDestroy, instanceId=" + instanceId);
        if (serviceConnector != null) {
            serviceConnector.unregisterReceiver(this);
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        MyLog.v(this, "Finish requested" + (isFinishing ? ", already finishing" : "") 
                + ", instanceId=" + instanceId);
        if (!isFinishing) {
            isFinishing = true;
        }
        runOnUiThread( new Runnable() {
            @Override 
            public void run() {
                if (positionRestored) {
                    saveListPosition();
                }
                if (loaderManager != null) {
                    loaderManager.destroyLoader(LOADER_ID);
                }
            }
        });
        super.finish();
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_ID_TIMELINE_TYPE:
                return newTimelinetypeSelector();
            default:
                break;
        }
        return super.onCreateDialog(id);
    }

    private AlertDialog newTimelinetypeSelector() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_title_select_timeline);
        final TimelineTypeSelector selector = new TimelineTypeSelector(this);
        builder.setItems(selector.getTitles(), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // The 'which' argument contains the index position of the
                // selected item
                TimelineTypeEnum type = selector.positionToType(which);
                if (type != TimelineTypeEnum.UNKNOWN) {
                    contextMenu.switchTimelineActivity(type,
                            timelineCombined, currentMyAccountUserId);
                }
            }
        });
        return builder.create();                
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.timeline, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0, new ComponentName(this,
                TimelineActivity.class), null, intent, 0, null);
        MyAccount ma = MyContextHolder.get().persistentAccounts().getCurrentAccount();
        
        boolean enableReload = ma != null
                && ma.getCredentialsVerified() == CredentialsVerificationStatus.SUCCEEDED;
        MenuItem item = menu.findItem(R.id.reload_menu_item);
        item.setEnabled(enableReload);
        item.setVisible(enableReload);

        boolean enableGlobalSearch = TimelineTypeEnum.PUBLIC == getTimelineType() 
                && ma != null
                && ma.getCredentialsVerified() == CredentialsVerificationStatus.SUCCEEDED
                && ma.getConnection().isApiSupported(ApiRoutineEnum.SEARCH_MESSAGES);
        item = menu.findItem(R.id.global_search_menu_id);
        item.setEnabled(enableGlobalSearch);
        item.setVisible(enableGlobalSearch);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        contextMenu.onContextItemSelected(item);
        return super.onContextItemSelected(item);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.preferences_menu_id:
                startMyPreferenceActivity();
                break;
            case R.id.search_menu_id:
                onSearchRequested();
                break;
            case R.id.reload_menu_item:
                manualReload(false);
                break;
            case R.id.global_search_menu_id:
                onSearchRequested(true);
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Listener that checks for clicks on the main list view.
     * 
     * @param adapterView
     * @param view
     * @param position
     * @param id
     */
    @Override
    public void onItemClick(AdapterView<?> adapterView, final View view, final int position, final long id) {
        if (id <= 0) {
            if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                MyLog.v(this, "onItemClick, position=" + position + "; id=" + id + "; view=" + view);
            }
            return;
        }
        
        new AsyncTask<Void, Void, Uri>() {

            @Override
            protected Uri doInBackground(Void... params) {
                long linkedUserId = getLinkedUserIdFromCursor(position);
                MyAccount ma = MyContextHolder.get().persistentAccounts().getAccountWhichMayBeLinkedToThisMessage(id, linkedUserId,
                        currentMyAccountUserId);
                if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
                    MyLog.v(this,
                            "onItemClick, position=" + position + "; id=" + id + "; view=" + view
                                    + "; linkedUserId=" + linkedUserId + " account="
                                    + ((ma != null) ? ma.getAccountName() : "?"));
                }
                Uri uri = MyProvider.getTimelineMsgUri(((ma != null) ? ma.getUserId() : 0), timelineType, true, id);
                return uri;
            }

            @Override
            protected void onPostExecute(Uri uri) {
                String action = getIntent().getAction();
                if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
                    if (MyLog.isLoggable(this, MyLog.DEBUG)) {
                        MyLog.d(this, "onItemClick, setData=" + uri);
                    }
                    setResult(RESULT_OK, new Intent().setData(uri));
                } else {
                    if (MyLog.isLoggable(this, MyLog.DEBUG)) {
                        MyLog.d(this, "onItemClick, startActivity=" + uri);
                    }
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                }
            }
            
        }.execute();
    }

    /**
     * @param position Of current item in the underlying Cursor
     * @return id of the User linked to this message. This link reflects the User's timeline 
     * or an Account which was used to retrieved the message
     */
    @Override
    public long getLinkedUserIdFromCursor(int position) {
        long userId = 0;
        try {
            Cursor cursor = null;
            if (getListAdapter() != null) {
                cursor = ((CursorAdapter) getListAdapter()).getCursor();
            }
            if (cursor != null && !cursor.isClosed()) {
                cursor.moveToPosition(position);
                int columnIndex = cursor.getColumnIndex(User.LINKED_USER_ID);
                if (columnIndex > -1) {
                    userId = cursor.getLong(columnIndex);
                }
            }
        } catch (Exception e) {
            MyLog.v(this, e);
        }
        return userId;
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        if (!noMoreItems && positionRestored && !isLoading()) {
            // Idea from http://stackoverflow.com/questions/1080811/android-endless-list
            boolean loadMore = (visibleItemCount > 0) && (firstVisibleItem > 0)
                    && (firstVisibleItem + visibleItemCount >= totalItemCount);
            if (loadMore) {
                MyLog.d(this, "Start Loading more items, rows=" + totalItemCount);
                queryListData(true);
            }
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        switch (scrollState) {
            case OnScrollListener.SCROLL_STATE_IDLE:
                break;
            case OnScrollListener.SCROLL_STATE_TOUCH_SCROLL:
                break;
            case OnScrollListener.SCROLL_STATE_FLING:
                // Turn the "fast scroll thumb" on
                view.setFastScrollEnabled(true);
                break;
            default:
                break;
        }
    }

    private void updateActionBarText() {
        updateTimelineTypeButtonText();
        updateAccountButtonText();
        updateRightText("");
    }

    private void updateTimelineTypeButtonText() {
        String timelinename = getString(timelineType.getTitleResId());
        Button timelineTypeButton = (Button) findViewById(R.id.timelineTypeButton);
        timelineTypeButton.setText(timelinename + (TextUtils.isEmpty(searchQuery) ? "" : " *"));
    }

    private void updateAccountButtonText() {
        Button selectAccountButton = (Button) findViewById(R.id.selectAccountButton);
        MyAccount ma = MyContextHolder.get().persistentAccounts().getCurrentAccount();
        String accountButtonText = "";
        if (ma == null) {
            accountButtonText = "?";
        } else if (isTimelineCombined() || getTimelineType() != TimelineTypeEnum.PUBLIC) {
            accountButtonText = ma.shortestUniqueAccountName();
            if (ma.getCredentialsVerified() != CredentialsVerificationStatus.SUCCEEDED) {
                accountButtonText = "(" + accountButtonText + ")";
            }
        } else {
            accountButtonText = ma.getOriginName();
        }
        selectAccountButton.setText(accountButtonText);
    }

    private void updateRightText(String rightText) {
        TextView rightTitle = (TextView) findViewById(R.id.custom_title_right_text);
        rightTitle.setText(rightText);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "onNewIntent, instanceId=" + instanceId
                    + (isFinishing ? ", Is finishing" : "")
                    );
        }
        if (isFinishing) {
            finish();
            return;
        }
        super.onNewIntent(intent);
        MyContextHolder.initialize(this, this);
        processNewIntent(intent);
        updateThisOnChangedParameters();
    }

    /**
     * Change the Activity according to the new intent. This procedure is done
     * both {@link #onCreate(Bundle)} and {@link #onNewIntent(Intent)}
     * 
     * @param intentNew
     */
    private void processNewIntent(Intent intentNew) {
        TimelineTypeEnum timelineTypeNew = TimelineTypeEnum.load(intentNew
                .getStringExtra(IntentExtra.EXTRA_TIMELINE_TYPE.key));
        if (timelineTypeNew != TimelineTypeEnum.UNKNOWN) {
            timelineType = timelineTypeNew;
            timelineCombined = intentNew.getBooleanExtra(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key, timelineCombined);
            searchQuery = notNullString(intentNew.getStringExtra(SearchManager.QUERY));
            selectedUserId = intentNew.getLongExtra(IntentExtra.EXTRA_SELECTEDUSERID.key, selectedUserId);
        } else {
            parseAppSearchData(intentNew);
        }
        if (timelineType == TimelineTypeEnum.UNKNOWN) {
            /* Set default values */
            timelineType = TimelineTypeEnum.HOME;
            searchQuery = "";
            selectedUserId = 0;
        }
        currentMyAccountUserId = MyContextHolder.get().persistentAccounts().getCurrentAccountUserId();
        if (selectedUserId == 0 && timelineType == TimelineTypeEnum.USER) {
            selectedUserId = currentMyAccountUserId;
        }

        // Are we supposed to send a tweet?
        if (Intent.ACTION_SEND.equals(intentNew.getAction())) {
            String textInitial = "";
            // This is Title of the page is Sharing Web page
            String subject = intentNew.getStringExtra(Intent.EXTRA_SUBJECT);
            // This is URL of the page if sharing web page
            String text = intentNew.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(subject)) {
                textInitial += subject;
            }
            if (!TextUtils.isEmpty(text)) {
                if (!TextUtils.isEmpty(textInitial)) {
                    textInitial += " ";
                }
                textInitial += text;
            }
            MyLog.v(this, "Intent.ACTION_SEND '" + textInitial +"'");
            messageEditor.startEditingMessage(textInitial, 0, 0, MyContextHolder.get().persistentAccounts().getCurrentAccount(), timelineCombined);
        }

        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this, "processNewIntent; " + timelineType);
        }
    }

    private void parseAppSearchData(Intent intentNew) {
        Bundle appSearchData = intentNew.getBundleExtra(SearchManager.APP_DATA);
        if (appSearchData != null) {
            // We use other packaging of the same parameters in onSearchRequested
            TimelineTypeEnum timelineTypeNew = TimelineTypeEnum.load(appSearchData
                    .getString(IntentExtra.EXTRA_TIMELINE_TYPE.key));
            if (timelineTypeNew != TimelineTypeEnum.UNKNOWN) {
                timelineType = timelineTypeNew;
                timelineCombined = appSearchData.getBoolean(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key, timelineCombined);
                /* The query itself is still from the Intent */
                searchQuery = notNullString(intentNew.getStringExtra(SearchManager.QUERY));
                selectedUserId = appSearchData.getLong(IntentExtra.EXTRA_SELECTEDUSERID.key, selectedUserId);
                if (!TextUtils.isEmpty(searchQuery)
                        && appSearchData.getBoolean(IntentExtra.EXTRA_GLOBAL_SEARCH.key, false)) {
                    MyLog.v(this, "Global search: " + searchQuery);
                    setLoading(true);
                    MyServiceManager.sendCommand(
                            CommandData.searchCommand(
                                    isTimelineCombined()
                                            ? ""
                                            : MyContextHolder.get().persistentAccounts()
                                                    .getCurrentAccountName(),
                                    searchQuery));
                }
            }
        }
    }

    private void updateThisOnChangedParameters() {
        MyServiceManager.setServiceAvailable();
        TextView selectedUserText = (TextView) findViewById(R.id.selectedUserText);
        ToggleButton combinedTimelineToggle = (ToggleButton) findViewById(R.id.combinedTimelineToggle);
        combinedTimelineToggle.setChecked(timelineCombined);
        if (selectedUserId != 0 && selectedUserId != currentMyAccountUserId) {
            combinedTimelineToggle.setVisibility(View.GONE);
            selectedUserText.setText(MyProvider.userIdToName(selectedUserId));
            selectedUserText.setVisibility(View.VISIBLE);
        } else {
            selectedUserText.setVisibility(View.GONE);
            // Show the "Combined" toggle even for one account to see messages, 
            // which are not on the timeline.
            // E.g. messages by users, downloaded on demand.
            combinedTimelineToggle.setVisibility(View.VISIBLE);
        }
        contextMenu.setAccountUserIdToActAs(0);

        updateActionBarText();
        if (messageEditor.isStateLoaded()) {
            messageEditor.continueEditingLoadedState();
        } else if (messageEditor.isVisible()) {
            // This is done to request focus (if we need this...)
            messageEditor.show();
        } else {
            messageEditor.updateCreateMessageButton();
        }
        
        queryListData(false);
    }
    
    /**
     * Prepare a query to the ContentProvider (to the database) and load the visible List of
     * messages with this data
     * This is done asynchronously.
     * This method should be called from UI thread only.
     * 
     * @param loadOneMorePage true - load one more page of messages, false - reload the same page
     */
    protected void queryListData(boolean loadOneMorePage) {
        final String method = "queryListData";
        if (!loadOneMorePage) {
            noMoreItems = false;
        }
        MyLog.v(this, method + (loadOneMorePage ? "loadOneMorePage" : ""));
        Bundle args = new Bundle();
        args.putBoolean(IntentExtra.EXTRA_LOAD_ONE_MORE_PAGE.key, loadOneMorePage);
        args.putInt(IntentExtra.EXTRA_ROWS_LIMIT.key, calcRowsLimit(loadOneMorePage));
        loaderManager.restartLoader(LOADER_ID, args, this);
        setLoading(true);
    }

    private int calcRowsLimit(boolean loadOneMorePage) {
        int nMessages = 0;
        if (getListAdapter() != null) {
            Cursor cursor = ((CursorAdapter) getListAdapter()).getCursor();
            if (cursor != null && !cursor.isClosed()) {
                nMessages = cursor.getCount();
            }
        }
        if (loadOneMorePage) {
            nMessages += PAGE_SIZE;
        } else if (nMessages < PAGE_SIZE) {
            nMessages = PAGE_SIZE;
        }
        return nMessages;
    }

    @Override
    public MyLoader<Cursor> onCreateLoader(int id, Bundle args) {
        final String method = "onCreateLoader";
        MyLog.v(this, method + " #" + id);
        TimelineListParameters params = new TimelineListParameters();
        params.loaderCallbacks = this;
        params.timelineType = getTimelineType();
        params.timelineCombined = isTimelineCombined();
        params.myAccountUserId = getCurrentMyAccountUserId();
        params.selectedUserId = getSelectedUserId();
        params.projection = getProjection();
        params.searchQuery = this.searchQuery;

        boolean loadOneMorePage = false;
        boolean reQuery = false;
        if (args != null) {
            loadOneMorePage = args.getBoolean(IntentExtra.EXTRA_LOAD_ONE_MORE_PAGE.key);
            reQuery = args.getBoolean(IntentExtra.EXTRA_REQUERY.key);
            params.rowsLimit = args.getInt(IntentExtra.EXTRA_ROWS_LIMIT.key);
        }
        params.loadOneMorePage = loadOneMorePage;
        params.incrementallyLoadingPages = positionRestored
                && (getListAdapter() != null)
                && loadOneMorePage;
        params.reQuery = reQuery;
        
        saveSearchQuery();
        prepareQueryForeground(params);

        return new TimelineCursorLoader(params);
    }

    private void saveSearchQuery() {
        if (!TextUtils.isEmpty(searchQuery)) {
            // Record the query string in the recent queries
            // of the Suggestion Provider
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this,
                    TimelineSearchSuggestionProvider.AUTHORITY,
                    TimelineSearchSuggestionProvider.MODE);
            suggestions.saveRecentQuery(searchQuery, null);

        }
    }
    
    private void prepareQueryForeground(TimelineListParameters params) {
        params.contentUri = MyProvider.getTimelineSearchUri(currentMyAccountUserId, timelineType,
                params.timelineCombined, params.searchQuery);
        Intent intent = getIntent();
        if (!params.contentUri.equals(intent.getData())) {
            intent.setData(params.contentUri);
        }

        if (params.sa.nArgs == 0) {
            // In fact this is needed every time you want to load
            // next page of messages

            /* TODO: Other conditions... */
            params.sa.clear();

            // TODO: Move these selections to the {@link MyProvider} ?!
            switch (timelineType) {
                case HOME:
                    // In the Home of the combined timeline we see ALL loaded
                    // messages, even those that we downloaded
                    // not as Home timeline of any Account
                    if (!isTimelineCombined()) {
                        params.sa.addSelection(MyDatabase.MsgOfUser.SUBSCRIBED + " = ?", new String[] {
                                "1"
                        });
                    }
                    break;
                case MENTIONS:
                    params.sa.addSelection(MyDatabase.MsgOfUser.MENTIONED + " = ?", new String[] {
                            "1"
                    });
                    /*
                     * We already figured this out and set {@link MyDatabase.MsgOfUser.MENTIONED}:
                     * sa.addSelection(MyDatabase.Msg.BODY + " LIKE ?" ...
                     */
                    break;
                case FAVORITES:
                    params.sa.addSelection(MyDatabase.MsgOfUser.FAVORITED + " = ?", new String[] {
                            "1"
                    });
                    break;
                case DIRECT:
                    params.sa.addSelection(MyDatabase.MsgOfUser.DIRECTED + " = ?", new String[] {
                            "1"
                    });
                    break;
                case USER:
                    AccountUserIds userIds = new AccountUserIds(isTimelineCombined(), getSelectedUserId());
                    // Reblogs are included also
                    params.sa.addSelection(MyDatabase.Msg.AUTHOR_ID + " " + userIds.getSqlUserIds() 
                            + " OR "
                            + MyDatabase.Msg.SENDER_ID + " " + userIds.getSqlUserIds() 
                            + " OR " 
                            + "("
                            + User.LINKED_USER_ID + " " + userIds.getSqlUserIds() 
                            + " AND "
                            + MyDatabase.MsgOfUser.REBLOGGED + " = 1"
                            + ")",
                            null);
                    break;
                default:
                    break;
            }
        }

        if (!positionRestored) {
            // We have to ensure that saved position will be
            // loaded from database into the list
            params.lastItemId = new ListPositionStorage(params).getLast();
        }

        if (params.lastItemId <= 0) {
            int rowsLimit = params.rowsLimit;
            if (rowsLimit < PAGE_SIZE) {
                rowsLimit = PAGE_SIZE;
            }
            params.sortOrder += " LIMIT 0," + rowsLimit;
        }
    }
   
    /** 
     * Table columns to use for the messages content
     */
    protected String[] getProjection() {
        List<String> columnNames = new ArrayList<String>();
        columnNames.add(Msg._ID);
        columnNames.add(User.AUTHOR_NAME);
        columnNames.add(Msg.BODY);
        columnNames.add(Msg.IN_REPLY_TO_MSG_ID);
        columnNames.add(User.IN_REPLY_TO_NAME);
        columnNames.add(User.RECIPIENT_NAME);
        columnNames.add(MsgOfUser.FAVORITED);
        columnNames.add(Msg.CREATED_DATE);
        columnNames.add(User.LINKED_USER_ID);
        if (MyPreferences.showAvatars()) {
            columnNames.add(Msg.AUTHOR_ID);
            columnNames.add(MyDatabase.Avatar.FILE_NAME);
        }
        return columnNames.toArray(new String[]{});
    }

    @Override
    public void onLoaderReset(MyLoader<Cursor> loader) {
        MyLog.v(this, "onLoaderReset; " + loader);
        setLoading(false);
    }
    
    @Override
    public void onLoadFinished(MyLoader<Cursor> loader, Cursor cursor) {
        MyLog.v(this, "onLoadFinished");
        TimelineTypeEnum timelineToReload = TimelineTypeEnum.UNKNOWN;
        if (loader.isStarted()) {
            if (loader instanceof TimelineCursorLoader) {
                TimelineCursorLoader myLoader = (TimelineCursorLoader) loader;
                changeListContent(myLoader.getParams(), cursor);
                timelineToReload = myLoader.getParams().timelineToReload;
            } else {
                MyLog.e(this, "Wrong type of loader: " + MyLog.objTagToString(loader));
            }
        }
        setLoading(false);
        launchReloadIfNeeded(timelineToReload);
    }
    
    private void changeListContent(TimelineListParameters params, Cursor cursor) {
        if (!params.cancelled && cursor != null && !isFinishing) {
            MyLog.v(this, "On changing Cursor");
            // This check will prevent continuous loading...
            noMoreItems = params.incrementallyLoadingPages &&
                    cursor.getCount() <= getListAdapter().getCount();
            saveListPosition();
            ((CursorAdapter) getListAdapter()).changeCursor(cursor);
            listParameters = params;
            restoreListPosition();
        }
    }
    
    private void launchReloadIfNeeded(TimelineTypeEnum timelineToReload) {
        switch (timelineToReload) {
            case ALL:
                manualReload(true);
                break;
            case UNKNOWN:
                break;
            default:
                manualReload(false);
                break;
        }
    }

    /**
     * Ask a service to load data from the Internet for the selected TimelineType
     * Only newer messages (newer than last loaded) are being loaded from the
     * Internet, older ones are not being reloaded.
     */
    protected void manualReload(boolean allTimelineTypes) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().fromUserId(currentMyAccountUserId);
        TimelineTypeEnum timelineTypeForReload = TimelineTypeEnum.HOME;
        long userId = 0;
        switch (timelineType) {
            case DIRECT:
            case MENTIONS:
            case PUBLIC:
                timelineTypeForReload = timelineType;
                break;
            case USER:
            case FOLLOWING_USER:
                timelineTypeForReload = timelineType;
                userId = selectedUserId;
                break;
            default:
                break;
        }
        boolean allAccounts = timelineCombined;
        if (userId != 0) {
            allAccounts = false;
            long originId = MyProvider.userIdToLongColumnValue(MyDatabase.User.ORIGIN_ID, userId);
            if (originId == 0) {
                MyLog.e(this, "Unknown origin for userId=" + userId);
                return;
            }
            if (ma == null || ma.getOriginId() != originId) {
                ma = MyContextHolder.get().persistentAccounts().fromUserId(userId);
                if (ma == null) {
                    ma = MyContextHolder.get().persistentAccounts().findFirstMyAccountByOriginId(originId);
                }
            }
        }
        if (!allAccounts && ma == null) {
            return;
        }

        setLoading(true);
        MyServiceManager.sendCommand(
                new CommandData(CommandEnum.FETCH_TIMELINE,
                        allAccounts ? "" : ma.getAccountName(), timelineTypeForReload, userId)
                );

        if (allTimelineTypes && ma != null) {
            ma.requestSync();
        }
    }
    
    protected void startMyPreferenceActivity() {
        finish();
        startActivity(new Intent(this, MyPreferenceActivity.class));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        messageEditor.saveState(outState);
        outState.putString(IntentExtra.EXTRA_TIMELINE_TYPE.key, timelineType.save());
        contextMenu.saveState(outState);
        outState.putBoolean(IntentExtra.EXTRA_TIMELINE_IS_COMBINED.key, timelineCombined);
        outState.putString(SearchManager.QUERY, searchQuery);
        outState.putLong(IntentExtra.EXTRA_SELECTEDUSERID.key, selectedUserId);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (ActivityRequestCode.fromId(requestCode)) {
            case SELECT_ACCOUNT:
                if (resultCode == RESULT_OK) {
                    MyAccount ma = MyContextHolder.get().persistentAccounts().fromAccountName(data.getStringExtra(IntentExtra.EXTRA_ACCOUNT_NAME.key));
                    if (ma != null) {
                        MyLog.v(this, "Restarting the activity for the selected account " + ma.getAccountName());
                        finish();
                        TimelineTypeEnum timelineTypeNew = timelineType;
                        if (timelineType == TimelineTypeEnum.USER 
                                &&  (MyContextHolder.get().persistentAccounts().fromUserId(selectedUserId) == null)) {
                            /*  "Other User's timeline" vs "My User's timeline" 
                             * Actually we saw messages of the user, who is not MyAccount,
                             * so let's switch to the HOME
                             * TODO: Open "Other User timeline" in a separate Activity?!
                             */
                            timelineTypeNew = TimelineTypeEnum.HOME;
                        }
                        MyContextHolder.get().persistentAccounts().setCurrentAccount(ma);
                        contextMenu.switchTimelineActivity(timelineTypeNew, timelineCombined, ma.getUserId());
                    }
                }
                break;
            case SELECT_ACCOUNT_TO_ACT_AS:
                if (resultCode == RESULT_OK) {
                    MyAccount ma = MyContextHolder.get().persistentAccounts().fromAccountName(data.getStringExtra(IntentExtra.EXTRA_ACCOUNT_NAME.key));
                    if (ma != null) {
                        contextMenu.setAccountUserIdToActAs(ma.getUserId());
                        contextMenu.showContextMenu();
                    }
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private void createListAdapter(Cursor cursor) {
        List<String> columnNames = new ArrayList<String>();
        List<Integer> viewIds = new ArrayList<Integer>();
        columnNames.add(MyDatabase.User.AUTHOR_NAME);
        viewIds.add(R.id.message_author);
        columnNames.add(MyDatabase.Msg.BODY);
        viewIds.add(R.id.message_body);
        columnNames.add(MyDatabase.Msg.CREATED_DATE);
        viewIds.add(R.id.message_details);
        columnNames.add(MyDatabase.MsgOfUser.FAVORITED);
        viewIds.add(R.id.message_favorited);
        columnNames.add(MyDatabase.Msg._ID);
        viewIds.add(R.id.id);
        int listItemLayoutId = R.layout.message_basic;
        if (MyPreferences.showAvatars()) {
            listItemLayoutId = R.layout.message_avatar;
            columnNames.add(MyDatabase.Avatar.FILE_NAME);
            viewIds.add(R.id.avatar_image);
        }
        MySimpleCursorAdapter messageAdapter = new MySimpleCursorAdapter(TimelineActivity.this,
                listItemLayoutId, cursor, columnNames.toArray(new String[]{}),
                toIntArray(viewIds), 0);
        messageAdapter.setViewBinder(new TimelineViewBinder());

        setListAdapter(messageAdapter);
    }
    
    /**
     * See http://stackoverflow.com/questions/960431/how-to-convert-listinteger-to-int-in-java
     */
    private static int[] toIntArray(List<Integer> list){
        int[] ret = new int[list.size()];
        for(int i = 0;i < ret.length;i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    private static String notNullString(String string) {
        return string == null ? "" : string;
    }

    @Override
    public void onReceive(CommandData commandData) {
        MyLog.v(this, "onReceive: " + commandData);
        switch (commandData.getCommand()) {
            case FETCH_TIMELINE:
            case SEARCH_MESSAGE:
                if (isLoading()) {
                    setLoading(false);
                }
                break;
            case RATE_LIMIT_STATUS:
                if (commandData.getResult().getHourlyLimit() > 0) {
                    updateRightText(commandData.getResult().getRemainingHits() + "/"
                            + commandData.getResult().getHourlyLimit());
                }
                break;
            default:
                break;
        }
    }
    
    @Override
    public Activity getActivity() {
        return this;
    }

    @Override
    public MessageEditor getMessageEditor() {
        return messageEditor;
    }

    @Override
    public long getCurrentMyAccountUserId() {
        return currentMyAccountUserId;
    }

    @Override
    public TimelineTypeEnum getTimelineType() {
        return timelineType;
    }

    @Override
    public boolean isTimelineCombined() {
        return timelineCombined;
    }

    @Override
    public long getSelectedUserId() {
        return selectedUserId;
    }
}

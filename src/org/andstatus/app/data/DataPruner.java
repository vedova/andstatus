/**
 * Copyright (C) 2013 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import org.andstatus.app.context.MyPreferences;
import org.andstatus.app.data.MyDatabase.FollowingUser;
import org.andstatus.app.data.MyDatabase.Msg;
import org.andstatus.app.data.MyDatabase.MsgOfUser;
import org.andstatus.app.data.MyDatabase.User;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SelectionAndArgs;

import java.io.File;
import java.util.Date;

/**
 * Clean database from outdated information
 * currently only old Messages are being deleted 
 */
public class DataPruner {
    private ContentResolver mContentResolver;
    private int mDeleted = 0;
    final static long MAX_DAYS_LOGS_TO_KEEP = 10;
    
    public DataPruner(Context context) {
        mContentResolver = context.getContentResolver();
    }

    /**
     * Do prune the data!

     * Remove old records to ensure that the database does not grow too large.
     * Maximum number of records is configured in "history_size" preference
     * @return true if succeeded
     */
    public boolean prune() {
        final String method = "prune";
        boolean ok = true;
       
        mDeleted = 0;
        int nDeletedTime = 0;
        // We're using global preferences here
        SharedPreferences sp = MyPreferences
                .getDefaultSharedPreferences();

        // Don't delete messages which are favorited by any user
        String sqlNotFavoritedMessage = "NOT EXISTS ("
                + "SELECT * FROM " + MsgOfUser.TABLE_NAME + " AS gnf WHERE "
                + Msg.TABLE_NAME + "." + Msg._ID + "=gnf." + MyDatabase.MsgOfUser.MSG_ID
                + " AND gnf." + MyDatabase.MsgOfUser.FAVORITED + "=1" 
                + ")";
        String sqlNotLatestMessageByFollowedUser = Msg.TABLE_NAME + "." + Msg._ID + " NOT IN("
                + "SELECT " + User.USER_MSG_ID 
                + " FROM " + User.TABLE_NAME + " AS userf"
                + " INNER JOIN " + FollowingUser.TABLE_NAME 
                + " ON" 
                + " userf." + User._ID + "=" + FollowingUser.TABLE_NAME + "." + FollowingUser.FOLLOWING_USER_ID
                + " AND " + FollowingUser.TABLE_NAME + "." + FollowingUser.USER_FOLLOWED + "=1"
                + ")";
        
        int maxDays = Integer.parseInt(sp.getString(MyPreferences.KEY_HISTORY_TIME, "3"));
        long latestTimestamp = 0;

        int nTweets = 0;
        int nToDeleteSize = 0;
        int nDeletedSize = 0;
        int maxSize = Integer.parseInt(sp.getString(MyPreferences.KEY_HISTORY_SIZE, "2000"));
        long latestTimestampSize = 0;
        Cursor cursor = null;
        try {
            if (maxDays > 0) {
                latestTimestamp = System.currentTimeMillis() - MyLog.daysToMillis(maxDays);
                SelectionAndArgs sa = new SelectionAndArgs();
                sa.addSelection(Msg.TABLE_NAME + "." + MyDatabase.Msg.INS_DATE + " <  ?", new String[] {
                    String.valueOf(latestTimestamp)
                });
                sa.selection += " AND " + sqlNotFavoritedMessage;
                sa.selection += " AND " + sqlNotLatestMessageByFollowedUser;
                nDeletedTime = mContentResolver.delete(MyProvider.MSG_CONTENT_URI, sa.selection, sa.selectionArgs);
            }

            if (maxSize > 0) {
                nDeletedSize = 0;
                cursor = mContentResolver.query(MyProvider.MSG_CONTENT_COUNT_URI, null, null, null, null);
                if (cursor.moveToFirst()) {
                    // Count is in the first column
                    nTweets = cursor.getInt(0);
                    nToDeleteSize = nTweets - maxSize;
                }
                cursor.close();
                if (nToDeleteSize > 0) {
                    // Find INS_DATE of the most recent tweet to delete
                    cursor = mContentResolver.query(MyProvider.MSG_CONTENT_URI, new String[] {
                            MyDatabase.Msg.INS_DATE
                    }, null, null, MyDatabase.Msg.INS_DATE + " ASC LIMIT 0," + nToDeleteSize);
                    if (cursor.moveToLast()) {
                        latestTimestampSize = cursor.getLong(0);
                    }
                    cursor.close();
                    if (latestTimestampSize > 0) {
                        SelectionAndArgs sa = new SelectionAndArgs();
                        sa.addSelection(Msg.TABLE_NAME + "." + MyDatabase.Msg.INS_DATE + " <=  ?", new String[] {
                            String.valueOf(latestTimestampSize)
                        });
                        sa.selection += " AND " + sqlNotFavoritedMessage;
                        sa.selection += " AND " + sqlNotLatestMessageByFollowedUser;
                        nDeletedSize = mContentResolver.delete(MyProvider.MSG_CONTENT_URI, sa.selection,
                                sa.selectionArgs);
                    }
                }
            }
        } catch (Exception e) {
            MyLog.e(this, method + " failed", e);
        } finally {
            DbUtils.closeSilently(cursor);
        }
        mDeleted = nDeletedTime + nDeletedSize;
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this,
                    method + "; History time=" + maxDays + " days; deleted " + nDeletedTime
                            + " , before " + new Date(latestTimestamp).toString());
            MyLog.v(this, method + "; History size=" + maxSize + " messages; deleted "
                    + nDeletedSize + " of " + nTweets + " messages, before " + new Date(latestTimestampSize).toString());
        }
        pruneLogs(MAX_DAYS_LOGS_TO_KEEP);
        return ok;
    }

    long pruneLogs(long maxDaysToKeep) {
        final String method = "pruneLogs";
        long latestTimestamp = System.currentTimeMillis() 
                - MyLog.daysToMillis(maxDaysToKeep);
        long count = 0;
        File dir = MyLog.getLogDir(true);
        if (dir == null) {
            return count;
        }
        for (String filename : dir.list()) {
            File file = new File(dir, filename);
            if (file.isFile() && (file.lastModified() < latestTimestamp)) {
                if (file.delete()) {
                    count++;
                    MyLog.v(this, method + "; deleted " + file.getName());
                } else {
                    MyLog.v(this, method + " couldn't delete: " + file.getAbsolutePath());
                }
            } else {
                MyLog.v(this, method + "; skipped " + file.getName() + ", modified " + new Date(file.lastModified()).toString());
            }
        }
        if (MyLog.isLoggable(this, MyLog.VERBOSE)) {
            MyLog.v(this,
                    method + "; deleted " + count
                            + " files, before " + new Date(latestTimestamp).toString());
        }
        return count;
    }

    /**
     * @return number of Messages deleted
     */
    public int getDeleted() {
        return mDeleted;
    }
}

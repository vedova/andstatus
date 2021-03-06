/* 
 * Copyright (c) 2013 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.net;

import android.test.InstrumentationTestCase;

import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.TestSuite;
import org.andstatus.app.net.OAuthClientKeys;
import org.andstatus.app.origin.Origin;
import org.andstatus.app.origin.OriginType;
import org.andstatus.app.util.TriState;

public class OAuthClientKeysTest extends InstrumentationTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TestSuite.forget();
        TestSuite.initialize(this);
    }

    public void testKeysSave() {
       HttpConnectionData connectionData = HttpConnectionData.fromConnectionData(
               MyContextHolder.get().persistentOrigins().firstOfType(OriginType.PUMPIO)
               .getConnectionData(TriState.UNKNOWN));
       final String consumerKey = "testConsumerKey" + Long.toString(System.nanoTime());
       final String consumerSecret = "testConsumerSecret" + Long.toString(System.nanoTime());

       connectionData.host = "example.com";
       OAuthClientKeys keys1 = OAuthClientKeys.fromConnectionData(connectionData);
       keys1.clear();
       assertEquals("Keys are cleared", false, keys1.areKeysPresent());
       keys1.setConsumerKeyAndSecret(consumerKey, consumerSecret);

       OAuthClientKeys keys2 = OAuthClientKeys.fromConnectionData(connectionData);
       assertEquals("Keys are loaded", true, keys2.areKeysPresent());
       assertEquals(consumerKey, keys2.getConsumerKey());
       assertEquals(consumerSecret, keys2.getConsumerSecret());
       keys2.clear();

       OAuthClientKeys keys3 = OAuthClientKeys.fromConnectionData(connectionData);
       assertEquals("Keys are cleared", false, keys3.areKeysPresent());
    }

    public static void insertTestKeys(Origin origin) {
        HttpConnectionData connectionData = HttpConnectionData.fromConnectionData(
                origin.getConnectionData(TriState.UNKNOWN));
        final String consumerKey = "testConsumerKey" + Long.toString(System.nanoTime());
        final String consumerSecret = "testConsumerSecret" + Long.toString(System.nanoTime());
        // Saving
        connectionData.host = "identi.ca";
        OAuthClientKeys keys1 = OAuthClientKeys.fromConnectionData(connectionData);
        keys1.setConsumerKeyAndSecret(consumerKey, consumerSecret);
        // Checking
        OAuthClientKeys keys2 = OAuthClientKeys.fromConnectionData(connectionData);
        assertEquals("Keys are loaded", true, keys2.areKeysPresent());
        assertEquals(consumerKey, keys2.getConsumerKey());
        assertEquals(consumerSecret, keys2.getConsumerSecret());
    }
    
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }
}

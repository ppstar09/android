/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twofours.surespot.services;

import android.app.IntentService;
import android.content.Intent;

import com.google.android.gms.gcm.GcmPubSub;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.common.SurespotConfiguration;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.network.NetworkController;
import com.twofours.surespot.network.SurespotCookieJar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class RegistrationIntentService extends IntentService {

    private static final String TAG = "RegIntentService";
    public static final String SENDER_ID = "428168563991";
    private static final String[] TOPICS = {"global"};

    public RegistrationIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        //SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        try {
            // [START register_for_gcm]
            // Initially this call goes out to the network to retrieve the token, subsequent calls
            // are local.
            // [START get_token]
            InstanceID instanceID = InstanceID.getInstance(this);
            String token = instanceID.getToken(SENDER_ID, GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
            // [END get_token]
            SurespotLog.i(TAG, "GCM Registration Token: " + token);

            SurespotLog.i(TAG, "Received gcm id, saving it in shared prefs.");
            Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.GCM_ID_RECEIVED, token);
            Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.APP_VERSION, SurespotApplication.getVersion());

            // TODO: Implement this method to send any registration to your app's servers.
            sendRegistrationToServer(token);

            // Subscribe to topic channels
            subscribeTopics(token);

            // You should store a boolean that indicates whether the generated token has been
            // sent to your server. If the boolean is false, send the token to your server,
            // otherwise your server should have already received the token.
//            sharedPreferences.edit().putBoolean(QuickstartPreferences.SENT_TOKEN_TO_SERVER, true).apply();
            // [END register_for_gcm]
        }
        catch (Exception e) {
            SurespotLog.i(TAG, e, "Failed to complete token refresh");
            // If an exception happens while fetching the new token or updating our registration data
            // on a third-party server, this ensures that we'll attempt the update at a later time.
            //  sharedPreferences.edit().putBoolean(QuickstartPreferences.SENT_TOKEN_TO_SERVER, false).apply();
        }
        // Notify UI that registration has completed, so the progress indicator can be hidden.
        //Intent registrationComplete = new Intent(QuickstartPreferences.REGISTRATION_COMPLETE);
        //LocalBroadcastManager.getInstance(this).sendBroadcast(registrationComplete);
    }

    /**
     * Persist registration to third-party servers.
     * <p/>
     * Modify this method to associate the user's GCM registration token with any server-side account
     * maintained by your application.
     *
     * @param id The new token.
     */
    private void sendRegistrationToServer(String id) {
        if (IdentityController.hasLoggedInUser()) {

            String username = IdentityController.getLoggedInUser();
            //see if it's different for this user
            String sentId = Utils.getUserSharedPrefsString(this, username, SurespotConstants.PrefNames.GCM_ID_SENT);

            if (id.equals(sentId)) {
                //if it's not different don't upload it
                SurespotLog.i(TAG, "GCM id already registered on surespot server.");
                return;
            }

            SurespotLog.i(TAG, "Attempting to register gcm id on surespot server.");

            okhttp3.Cookie cookie = IdentityController.getCookieForUser(IdentityController.getLoggedInUser());
            if (cookie != null) {
                SurespotCookieJar jar = new SurespotCookieJar();
                jar.setCookie(cookie);

                OkHttpClient client = new OkHttpClient.Builder()
                        .cookieJar(jar)
                        .build();

                JSONObject params = new JSONObject();
                try {
                    params.put("gcmId", id);
                }
                catch (JSONException e) {
                    SurespotLog.i(TAG, e, "Error saving gcmId on surespot server");
                    return;
                }

                RequestBody body = RequestBody.create(NetworkController.JSON, params.toString());
                Request request = new Request.Builder()
                        .url(SurespotConfiguration.getBaseUrl() + "/registergcm")
                        .post(body)
                        .build();

                Response response;
                try {
                    response = client.newCall(request).execute();
                }
                catch (IOException e) {
                    SurespotLog.i(TAG, e, "Error saving gcmId on surespot server");
                    return;
                }

                // success returns 204
                if (response.code() == 204) {
                    SurespotLog.i(TAG, "Successfully saved GCM id on surespot server.");

                    // the server and client match, we're golden
                    Utils.putUserSharedPrefsString(this, username, SurespotConstants.PrefNames.GCM_ID_SENT, id);
                }
            }
        }
        else {
            SurespotLog.i(TAG, "Can't save GCM id on surespot server as user is not logged in.");
        }
    }

    /**
     * Subscribe to any GCM topics of interest, as defined by the TOPICS constant.
     *
     * @param token GCM token
     * @throws IOException if unable to reach the GCM PubSub service
     */
    // [START subscribe_topics]
    private void subscribeTopics(String token) throws IOException {
        GcmPubSub pubSub = GcmPubSub.getInstance(this);
        for (String topic : TOPICS) {
            pubSub.subscribe(token, "/topics/" + topic, null);
        }
    }
    // [END subscribe_topics]

}
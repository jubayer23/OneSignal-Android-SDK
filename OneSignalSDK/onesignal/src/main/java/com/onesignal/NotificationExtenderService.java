/**
 * Modified MIT License
 *
 * Copyright 2016 OneSignal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * 2. All copies of substantial portions of the Software may only be used in connection
 * with services provided by OneSignal.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.onesignal;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Designed to be extended by app developer.
// Must add the following to the AndroidManifest.xml for this to be triggered.

/*
<service
   android:name=".NotificationExtenderServiceExample"
   android:exported="false">
   <intent-filter>
      <action android:name="com.onesignal.NotificationExtender" />
   </intent-filter>
</service>
*/

// NOTE: Currently does not support Amazon ADM messages.

public abstract class NotificationExtenderService extends IntentService {

   public static class OverrideSettings {
      public NotificationCompat.Extender extender;
      public Integer androidNotificationId;

      // Note: Make sure future fields are nullable.
      // Possible future options
      //    int badgeCount;
      //   NotificationCompat.Extender summaryExtender;

      void override(OverrideSettings overrideSettings) {
         if (overrideSettings == null)
            return;

         if (overrideSettings.androidNotificationId != null)
            androidNotificationId = overrideSettings.androidNotificationId;
      }
   }

   public NotificationExtenderService() {
      super("NotificationExtenderService");
   }

   private OSNotificationDisplayedResult osNotificationDisplayedResult;
   private JSONObject currentJsonPayload;
   private boolean currentlyRestoring;
   private OverrideSettings currentBaseOverrideSettings = null;

   // Developer may call to override some notification settings.
   //   - If called the normal SDK notification will not be displayed.
   protected final OSNotificationDisplayedResult displayNotification(OverrideSettings overrideSettings) {
      if (osNotificationDisplayedResult != null || overrideSettings == null)
         return null;

      overrideSettings.override(currentBaseOverrideSettings);
      osNotificationDisplayedResult = new OSNotificationDisplayedResult();
      osNotificationDisplayedResult.notificationId = NotificationBundleProcessor.Process(this, currentlyRestoring, currentJsonPayload, overrideSettings);
      return osNotificationDisplayedResult;
   }

   // App developer must implement
   //   - Return true to count it as processed to prevent the default OneSignal notification from displaying.
   protected abstract boolean onNotificationProcessing(OSNotificationPayload notification);

   @Override
   protected final void onHandleIntent(Intent intent) {
      processIntent(intent);
      GcmBroadcastReceiver.completeWakefulIntent(intent);
   }

   private void processIntent(Intent intent) {
      Bundle bundle = intent.getExtras();
      try {
         currentJsonPayload = new JSONObject(bundle.getString("json_payload"));
         currentlyRestoring = bundle.getBoolean("restoring", false);
         if (bundle.containsKey("android_notif_id")) {
            currentBaseOverrideSettings = new OverrideSettings();
            currentBaseOverrideSettings.androidNotificationId = bundle.getInt("android_notif_id");
         }

         if (!currentlyRestoring && OneSignal.notValidOrDuplicated(this, currentJsonPayload))
            return;

         processJsonObject(currentJsonPayload, currentlyRestoring);
      } catch (JSONException e) {
         e.printStackTrace();
      }
   }

   void processJsonObject(JSONObject currentJsonPayload, boolean restoring) {
      OSNotificationPayload notification = new OSNotificationPayload();
      try {
         JSONObject customJson = new JSONObject(currentJsonPayload.optString("custom"));
         notification.notificationId = customJson.optString("i");
         notification.additionalData = customJson.optJSONObject("a");
         notification.launchUrl = customJson.optString("u", null);

         notification.message = currentJsonPayload.optString("alert", null);
         notification.title = currentJsonPayload.optString("title", null);
         notification.smallIcon = currentJsonPayload.optString("sicon", null);
         notification.bigPicture = currentJsonPayload.optString("bicon", null);
         notification.largeIcon = currentJsonPayload.optString("licon", null);
         notification.sound = currentJsonPayload.optString("sound", null);
         notification.group = currentJsonPayload.optString("grp", null);
         notification.groupMessage = currentJsonPayload.optString("grp_msg", null);
         notification.backgroundColor = currentJsonPayload.optString("bgac", null);
         notification.ledColor = currentJsonPayload.optString("ledc", null);
         String visibility = currentJsonPayload.optString("vis", null);
         if (visibility != null)
            notification.visibility = Integer.parseInt(visibility);
         notification.backgroundData = "1".equals(currentJsonPayload.optString("bgn", null));
         notification.fromProjectNumber = currentJsonPayload.optString("from", null);
         notification.restoring = restoring;

         try {
            setActionButtons(notification);
         } catch (Throwable t) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Error assigning OSNotificationPayload.actionButtons values!", t);
         }

         try {
            setBackgroundImageLayout(notification, currentJsonPayload);
         } catch (Throwable t) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Error assigning OSNotificationPayload.backgroundImageLayout values!", t);
         }
      } catch (Throwable t) {
         OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Error assigning OSNotificationPayload values!", t);
      }

      osNotificationDisplayedResult = null;
      boolean developerProcessed = false;
      try {
         developerProcessed = onNotificationProcessing(notification);
      }
      catch (Throwable t) {
         //noinspection ConstantConditions - displayNotification might have been called by the developer
         if (osNotificationDisplayedResult == null)
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "onNotificationProcessing throw an exception. Displaying normal OneSignal notification.", t);
         else
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "onNotificationProcessing throw an exception. Extended notification displayed but custom processing did not finish.", t);
      }

      // If developer did not call displayNotification from onNotificationProcessing
      if (osNotificationDisplayedResult == null) {
         // Save as processed to prevent possible duplicate calls from canonical ids.
         if (developerProcessed) {
            if (!restoring)
               NotificationBundleProcessor.saveNotification(this, currentJsonPayload, true, -1);
         }
         else
            NotificationBundleProcessor.Process(this, currentlyRestoring, currentJsonPayload, currentBaseOverrideSettings);
      }
   }

   private static void setActionButtons(OSNotificationPayload notification) throws Throwable {
      if (notification.additionalData != null && notification.additionalData.has("actionButtons")) {
         JSONArray jsonActionButtons = notification.additionalData.getJSONArray("actionButtons");
         notification.actionButtons = new ArrayList<>();

         for (int i = 0; i < jsonActionButtons.length(); i++) {
            JSONObject jsonActionButton = jsonActionButtons.getJSONObject(i);
            OSNotificationPayload.ActionButton actionButton = new OSNotificationPayload.ActionButton();
            actionButton.id = jsonActionButton.optString("id", null);
            actionButton.text = jsonActionButton.optString("text", null);
            actionButton.icon = jsonActionButton.optString("icon", null);
            notification.actionButtons.add(actionButton);
         }
         notification.additionalData.remove("actionSelected");
         notification.additionalData.remove("actionButtons");
      }
   }

   private static void setBackgroundImageLayout(OSNotificationPayload notification, JSONObject currentJsonPayload) throws Throwable {
      String jsonStrBgImage = currentJsonPayload.optString("bg_img", null);
      if (jsonStrBgImage != null) {
         JSONObject jsonBgImage = new JSONObject(jsonStrBgImage);
         notification.backgroundImageLayout = new OSNotificationPayload.BackgroundImageLayout();
         notification.backgroundImageLayout.image = jsonBgImage.optString("img");
         notification.backgroundImageLayout.titleTextColor = jsonBgImage.optString("tc");
         notification.backgroundImageLayout.bodyTextColor = jsonBgImage.optString("bc");
      }
   }

   static Intent getIntent(Context context) {
      PackageManager packageManager = context.getPackageManager();
      Intent intent = new Intent().setAction("com.onesignal.NotificationExtender").setPackage(context.getPackageName());
      List<ResolveInfo> resolveInfo = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA);
      if (resolveInfo.size() < 1)
         return null;

      return intent;
   }
}
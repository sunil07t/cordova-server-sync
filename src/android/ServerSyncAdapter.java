/**
 * Creates an adapter to post data to the SMAP server
 */
package edu.berkeley.eecs.emission.cordova.serversync;

import android.accounts.Account;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import edu.berkeley.eecs.e_mission.BatteryUtils;
import edu.berkeley.eecs.e_mission.ClientStatsHelper;
import edu.berkeley.eecs.e_mission.CommunicationHelper;
import edu.berkeley.eecs.e_mission.ModeClassificationHelper;
import edu.berkeley.eecs.e_mission.OnboardingActivity;
import edu.berkeley.eecs.e_mission.R;
import edu.berkeley.eecs.e_mission.UnclassifiedSection;
import edu.berkeley.eecs.e_mission.UserClassification;
import edu.berkeley.eecs.e_mission.auth.GoogleAccountManagerAuth;
import edu.berkeley.eecs.e_mission.auth.UserProfile;
import edu.berkeley.eecs.e_mission.log.Log;
import edu.berkeley.eecs.e_mission.usercache.BuiltinUserCache;
import edu.berkeley.eecs.e_mission.usercache.UserCache;

/**
 * @author shankari
 *
 */
public class ServerSyncAdapter extends AbstractThreadedSyncAdapter {
	private String userName;
	private static final String TAG = "ServerSyncAdapter";

	Properties uuidMap;
	boolean syncSkip = false;
	Context cachedContext;
	ModeClassificationHelper dbHelper;
	ClientStatsHelper statsHelper;
	// TODO: Figure out a principled way to do this
	private static int CONFIRM_TRIPS_ID = 99;
	
	public ConfirmTripsAdapter(Context context, boolean autoInitialize) {
		super(context, autoInitialize);
		
		System.out.println("Creating ConfirmTripsAdapter");
		// Dunno if it is OK to cache the context like this, but there are other
		// people doing it, so let's do it as well.
		// See https://nononsense-notes.googlecode.com/git-history/3716b44b527096066856133bfc8dfa09f9244db8/NoNonsenseNotes/src/com/nononsenseapps/notepad/sync/SyncAdapter.java
		// for an example
		cachedContext = context;
		dbHelper = new ModeClassificationHelper(context);
		statsHelper = new ClientStatsHelper(context);
		// Our ContentProvider is a dummy so there is nothing else to do here
	}
	
	/* (non-Javadoc)
	 * @see android.content.AbstractThreadedSyncAdapter#onPerformSync(android.accounts.Account, android.os.Bundle, java.lang.String, android.content.ContentProviderClient, android.content.SyncResult)
	 */
	@Override
	public void onPerformSync(Account account, Bundle extras, String authority,
			ContentProviderClient provider, SyncResult syncResult) {
        android.util.Log.i("SYNC", "PERFORMING SYNC");
        long msTime = System.currentTimeMillis();
		String syncTs = String.valueOf(msTime);
		statsHelper.storeMeasurement(cachedContext.getString(R.string.sync_launched), null, syncTs);
		
		/*
		 * Read the battery level when the app is being launched anyway.
		 */
		statsHelper.storeMeasurement(cachedContext.getString(R.string.battery_level),
				String.valueOf(BatteryUtils.getBatteryLevel(cachedContext)), syncTs);
				
		if (syncSkip == true) {
			System.err.println("Something is wrong and we have been asked to skip the sync, exiting immediately");
			return;
		}

		if (!OnboardingActivity.getOnboardingComplete(cachedContext)) {
			generateNotification(CONFIRM_TRIPS_ID, "Finish setting up app", edu.berkeley.eecs.e_mission.OnboardingActivity.class);
			return;
		}
		
		System.out.println("Can we use the extras bundle to transfer information? "+extras);
		// Get the list of uncategorized trips from the server
		// hardcoding the URL and the userID for now since we are still using fake data
		String userName = UserProfile.getInstance(cachedContext).getUserEmail();
		System.out.println("real user name = "+userName);

		if (userName == null || userName.trim().length() == 0) {
			System.out.println("we don't know who we are, so we can't get our data");
			return;
		}
		// First, get a token so that we can make the authorized calls to the server
		String userToken = GoogleAccountManagerAuth.getServerToken(cachedContext, userName);


		/*
		 * We send almost all pending trips to the server
		 */

		/*
		 * We are going to send over information for all the data in a single JSON object, to avoid overhead.
		 * So we take a quick check to see if the number of entries is zero.
		 */
		BuiltinUserCache biuc = new BuiltinUserCache(cachedContext);

		Log.i(cachedContext, TAG, "No push in e-mission, now pulling");

        /*
         * Now, read all the information from the server. This is in a different try/catch block,
         * because we want to try it even if the push fails.
         */
		try {
			JSONArray entriesReceived = CommunicationHelper.server_to_phone(cachedContext, userToken);
			biuc.sync_server_to_phone(entriesReceived);
		} catch (JSONException e) {
			Log.e(cachedContext, TAG, "Error "+e+" while saving converting trips to JSON, skipping all of them");
		} catch (IOException e) {
			Log.e(cachedContext, TAG, "IO Error "+e+" while posting converted trips to JSON");
		}


		try{
			// Now, we push the stats and clear it
			// Note that the database ensures that we have a blank document if there are no stats
			// by skipping the metadata in that case.
			JSONObject freshStats = statsHelper.getMeasurements();
			if (freshStats.length() > 0) {
				CommunicationHelper.pushStats(cachedContext, userToken, freshStats);
				statsHelper.clear();
			}
			statsHelper.storeMeasurement(cachedContext.getString(R.string.sync_duration),
					String.valueOf(System.currentTimeMillis() - msTime), syncTs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/*
	 * The original thought was that the sectionId would be unique, so we could
	 * use it to uniquely generate an integer that could be used in the notification code.
	 * We also assumed that we could use is as a database key and as the data in
	 * the HTTP post request.
	 * 
	 * However, the current database schema is that the tripID is unique, and the section ID
	 * is unique within a trip.
	 * 
	 * So we need to send a (tripId, sectionId, userClassification) triplet to the server.
	 * So we can not longer use key = sectionId, value = userMode.
	 * 
	 * Also, we need to create a new data structure that we can pass around instead of using a pair.
	 */
	
	public JSONArray convertListToJSON(List<UserClassification> resultList) {
		JSONArray retArray = new JSONArray();
		for (int i = 0; i < resultList.size(); i++) {
			JSONObject currObj = new JSONObject();
			UserClassification currClass = resultList.get(i);
			try {
				currObj.put("trip_id", currClass.getTripId());
				currObj.put("section_id", currClass.getSectionId());
				currObj.put("userMode", currClass.getUserMode());
				// System.out.println("currObj = "+currObj);
				retArray.put(currObj);
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return retArray;
	}
	
	public List<UnclassifiedSection> convertJSONToList(JSONArray fromServer) {
		List<UnclassifiedSection> resultList = new ArrayList<UnclassifiedSection>();
		for (int i=0; i < fromServer.length(); i++) {
			try {
				resultList.add(UnclassifiedSection.parse(fromServer.getJSONObject(i)));
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return resultList;
	}

	/*
	 * Generates a notification for the user.
	 */
	
	public void generateNotifications(JSONArray sections) {
		if (sections.length() > 0) {
			String message = "You have "+sections.length()+" pending trips to categorize";
			generateNotification(CONFIRM_TRIPS_ID, message, edu.berkeley.eecs.e_mission.ConfirmSectionListActivity.class);
		} else {
			// no unclassified sections, don't generate a notification
		}
	}
	
	@SuppressWarnings("rawtypes")
	private void generateNotification(int messageId, String message, Class activityToLaunch) {
		System.out.println("While generating notification sectionId = "+messageId);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(cachedContext);
		builder.setAutoCancel(true);
		builder.setSmallIcon(R.drawable.ic_launcher);
		builder.setContentTitle(cachedContext.getString(R.string.app_name));
		builder.setContentText(message);
		
		/*
		 * This is a bit of magic voodoo. The tutorial on launching the activity actually uses a stackbuilder
		 * to create a fake stack for the new activity. However, it looks like the stackbuilder
		 * is only available in more recent versions of the API. So I use the version for a special activity PendingIntent
		 * (since our app currently has only one activity) which resolves that issue.
		 * This also appears to work, at least in the emulator.
		 * 
		 * TODO: Decide what level API we want to support, and whether we want a more comprehensive activity.
		 */
		
		Intent activityIntent = new Intent(cachedContext, activityToLaunch);
		activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		
		PendingIntent activityPendingIntent = PendingIntent.getActivity(cachedContext, CONFIRM_TRIPS_ID,
				activityIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		builder.setContentIntent(activityPendingIntent);		
		
		NotificationManager nMgr =
				(NotificationManager)cachedContext.getSystemService(Context.NOTIFICATION_SERVICE);
		
		nMgr.notify(messageId, builder.build());
	}
	
	public String getPath(String serviceName) {
		return "/"+userName+"/"+serviceName;
	}
}
package com.odoo.addons.mail.services;

import java.util.ArrayList;
import java.util.List;

import odoo.OArguments;
import odoo.ODomain;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;

import com.odoo.App;
import com.odoo.MainActivity;
import com.odoo.addons.mail.models.MailMessage;
import com.odoo.addons.mail.models.MailNotification;
import com.odoo.addons.mail.providers.mail.MailProvider;
import com.odoo.orm.OColumn;
import com.odoo.orm.ODataRow;
import com.odoo.orm.OSyncHelper;
import com.odoo.orm.OValues;
import com.odoo.receivers.SyncFinishReceiver;
import com.odoo.support.OUser;
import com.odoo.support.service.OSyncAdapter;
import com.odoo.support.service.OSyncFinishListener;
import com.odoo.support.service.OSyncService;
import com.odoo.util.ONotificationHelper;
import com.odoo.util.logger.OLog;
import com.openerp.R;

public class MailSyncService extends OSyncService implements
		OSyncFinishListener {
	public static final String TAG = MailSyncService.class.getSimpleName();

	@Override
	public OSyncAdapter getSyncAdapter() {
		return new OSyncAdapter(getApplicationContext(), new MailMessage(
				getApplicationContext()), this, true);
	}

	@Override
	public void performDataSync(OSyncAdapter adapter, Bundle extras, OUser user) {
		Context mContext = getApplicationContext();
		Intent intent = new Intent();
		intent.setAction(SyncFinishReceiver.SYNC_FINISH);
		try {
			MailMessage mdb = new MailMessage(mContext);
			mdb.setUser(user);
			ODomain domain = new ODomain();
			if (extras.containsKey(MailGroupSyncService.KEY_GROUP_IDS)) {
				JSONArray group_ids = new JSONArray(
						extras.getString(MailGroupSyncService.KEY_GROUP_IDS));
				domain.add("res_id", "in", group_ids);
				domain.add("model", "=", "mail.group");
			}

			boolean showNotification = true;
			ActivityManager am = (ActivityManager) mContext
					.getSystemService(ACTIVITY_SERVICE);
			List<ActivityManager.RunningTaskInfo> taskInfo = am
					.getRunningTasks(1);
			ComponentName componentInfo = taskInfo.get(0).topActivity;
			if (componentInfo.getPackageName().equalsIgnoreCase("com.openerp")) {
				showNotification = false;
			}
			if (sendMails(mContext, user, mdb, mdb.getSyncHelper())) {
				if (mdb.getSyncHelper().syncDataLimit(30)
						.syncWithServer(domain, true)) {
					if (showNotification && mdb.newMessageIds().size() > 0) {
						int newTotal = mdb.newMessageIds().size();
						ONotificationHelper mNotification = new ONotificationHelper();
						Intent mainActiivty = new Intent(mContext,
								MainActivity.class);
						mNotification.setResultIntent(mainActiivty, mContext);
						mNotification.showNotification(mContext, newTotal
								+ " unread messages", newTotal
								+ " new message received (Odoo)",
								mdb.authority(), R.drawable.ic_odoo_o);
						if (updateOldMessages(mContext, user, mdb.ids())) {
							if (user.getAndroidName().equals(user.getName())) {
								mContext.sendBroadcast(intent);
							}
						}
					}

				}
			}
			adapter.syncDataLimit(30).onSyncFinish(this).setDomain(domain);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Boolean sendMails(Context context, OUser user, MailMessage mails,
			OSyncHelper helper) {
		for (ODataRow mail : mails.select("id = ?", new Object[] { 0 })) {
			try {
				JSONArray partner_ids = new JSONArray();
				JSONArray p_ids = new JSONArray();
				List<ODataRow> partners = mail.getM2MRecord("partner_ids")
						.browseEach();
				for (ODataRow partner : partners) {
					p_ids.put(partner.getInt("id"));
				}
				partner_ids.put(6);
				partner_ids.put(false);
				partner_ids.put(p_ids);
				if ((Integer) mail.getM2ORecord("parent_id").getId() == 0) {
					_sendMail(mail, partner_ids, helper, mails);
				} else {
					ODataRow parent = mail.getM2ORecord("parent_id").browse();
					sendReply(context, parent, mail, partner_ids, helper, mails);
				}
			} catch (Exception e) {
				Log.e(TAG, "sendMails():" + e.getMessage());
			}
		}
		return true;
	}

	private void _sendMail(ODataRow mail, JSONArray partner_ids,
			OSyncHelper helper, MailMessage mails) {
		try {
			JSONObject arguments = new JSONObject();
			arguments.put("composition_mode", "comment");
			arguments.put("model", false);
			arguments.put("parent_id", false);
			arguments.put("subject", mail.getString("subject"));
			arguments.put("body", mail.getString("body"));
			arguments.put("post", true);
			arguments.put("notify", false);
			arguments.put("same_thread", true);
			arguments.put("use_active_domain", false);
			arguments.put("reply_to", false);
			arguments.put("res_id", false);
			arguments.put("record_name", false);
			arguments.put("partner_ids", new JSONArray().put(partner_ids));
			arguments.put("template_id", false);
			JSONObject kwargs = new JSONObject();
			kwargs.put("context", helper.getContext(new JSONObject()));
			OArguments args = new OArguments();
			args.add(arguments);
			String model = "mail.compose.message";
			// Creating compose message
			Object message_id = helper.callMethod(model, "create", args, null,
					kwargs);
			// sending mail
			args = new OArguments();
			args.add(new JSONArray().put(message_id));
			args.add(helper.getContext(null));
			helper.callMethod(model, "send_mail", args, null, null);
			mails.delete(mail.getInt(OColumn.ROW_ID));
		} catch (Exception e) {
			Log.e(TAG, "_sendMail() : " + e.getMessage());
		}
	}

	private void sendReply(Context context, ODataRow parent, ODataRow mail,
			JSONArray partner_ids, OSyncHelper helper, MailMessage mails) {
		try {
			// sending reply
			String model = (parent.getString("model").equals("false")) ? "mail.thread"
					: parent.getString("model");
			String method = "message_post";
			Object res_model = (parent.getString("model").equals("false")) ? false
					: parent.getString("model");
			Object res_id = (parent.getInt("res_id") == 0) ? false : parent
					.getInt("res_id");

			JSONObject jContext = new JSONObject();
			jContext.put("default_model", res_model);
			jContext.put("default_res_id", res_id);
			jContext.put("default_parent_id", parent.getInt("id"));
			jContext.put("mail_post_autofollow", true);
			jContext.put("mail_post_autofollow_partner_ids", new JSONArray());
			JSONObject kwargs = new JSONObject();
			kwargs.put("context", jContext);
			kwargs.put("subject", mail.getString("subject"));
			kwargs.put("body", mail.getString("body"));
			kwargs.put("parent_id", parent.getInt("id"));
			kwargs.put("attachment_ids", new JSONArray());
			kwargs.put("partner_ids", new JSONArray().put(partner_ids));

			OArguments args = new OArguments();
			args.add(new JSONArray().put(res_id));
			Integer messageId = (Integer) helper.callMethod(model, method,
					args, null, kwargs);

			// ArrayList<ContentProviderOperation> batch = new
			// ArrayList<ContentProviderOperation>();
			ContentProviderOperation.Builder batch = ContentProviderOperation
					.newUpdate(mails
							.uri()
							.buildUpon()
							.appendPath(
									Integer.toString(mail
											.getInt(OColumn.ROW_ID))).build());
			batch.withValue("id", messageId);
			ArrayList<ContentProviderOperation> batches = new ArrayList<ContentProviderOperation>();
			batches.add(batch.build());
			context.getContentResolver().applyBatch(mails.authority(), batches);
			// OValues vals = new OValues();
			// vals.put(OColumn.ROW_ID, mail.getInt(OColumn.ROW_ID));
			// vals.put("id", messageId);
			// mails.update(vals, mail.getInt(OColumn.ROW_ID));

		} catch (Exception e) {
			Log.e(TAG, "sendReply() : " + e.getMessage());
		}
	}

	private Boolean updateOldMessages(Context context, OUser user,
			List<Integer> ids) {
		try {
			JSONArray ids_array = new JSONArray();
			for (int id : ids)
				ids_array.put(id);
			ODomain domain = new ODomain();
			domain.add("message_id", "in", ids);
			MailNotification mailNotification = new MailNotification(context);
			MailMessage message = new MailMessage(context);
			OSyncHelper helper = message.getSyncHelper();
			if (mailNotification.getSyncHelper().syncWithServer(domain, false)) {
				for (Integer id : ids) {
					int row_id = message.selectRowId(id);
					List<ODataRow> notifications = mailNotification.select(
							"message_id = ?", new Object[] { row_id });
					if (notifications.size() > 0) {
						OValues vals = new OValues();
						ODataRow noti = notifications.get(0);
						vals.put(OColumn.ROW_ID, row_id);
						vals.put("notification_ids",
								noti.getInt(OColumn.ROW_ID));
						message.update(vals, row_id);
					}
					// updateMailVotes(message, helper, user, ids_array);
				}
				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	// =====================================================================================================
	// private void updateMailVotes(MailMessage db, OSyncHelper os, OUser user,
	// JSONArray ids_array) {
	// try {
	//
	// JSONObject vote_fields = new JSONObject();
	// vote_fields.accumulate("fields", "vote_user_ids");
	// Object vote_detail = os.callMethod("mail.message", "search_read",
	// null, null, vote_fields);
	// OLog.log("Call Method Result ==" + vote_detail);
	// os.search_read("mail.message", vote_fields, domain.get(), 0, 0,
	// null, null);
	// } catch (Exception e) {
	// e.printStackTrace();
	// }
	// }
	// =====================================================================================================
	@Override
	public OSyncAdapter performSync(SyncResult syncResult) {
		App app = (App) getApplicationContext();
		OLog.log(syncResult.stats.numEntries + " numEntries");
		OLog.log(syncResult.stats.numDeletes + " numDeletes");
		OLog.log(syncResult.stats.numInserts + " numInserts");
		OLog.log(syncResult.stats.numUpdates + " numUpdates");
		if (!app.appOnTop() && syncResult.stats.numInserts > 0) {
			int newTotal = (int) syncResult.stats.numInserts;
			ONotificationHelper mNotification = new ONotificationHelper();
			Context context = getApplicationContext();
			Intent mainActiivty = new Intent(context, MainActivity.class);
			mNotification.setResultIntent(mainActiivty, context);
			mNotification.showNotification(context, newTotal
					+ " unread messages", newTotal
					+ " new message received (Odoo)", MailProvider.AUTHORITY,
					R.drawable.ic_odoo_o);
		}
		return null;
	}

}

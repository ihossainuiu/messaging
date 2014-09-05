package com.odoo.addons.mail.models;

import java.util.ArrayList;
import java.util.List;

import odoo.OArguments;
import odoo.ODomain;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import com.odoo.addons.mail.providers.mail.MailProvider;
import com.odoo.base.ir.IrAttachment;
import com.odoo.base.res.ResPartner;
import com.odoo.base.res.ResUsers;
import com.odoo.orm.OColumn;
import com.odoo.orm.OColumn.RelationType;
import com.odoo.orm.ODataRow;
import com.odoo.orm.OModel;
import com.odoo.orm.OValues;
import com.odoo.orm.annotations.Odoo;
import com.odoo.orm.types.OBoolean;
import com.odoo.orm.types.ODateTime;
import com.odoo.orm.types.OHtml;
import com.odoo.orm.types.OInteger;
import com.odoo.orm.types.OText;
import com.odoo.orm.types.OVarchar;
import com.odoo.support.provider.OContentProvider;
import com.odoo.util.ODate;
import com.odoo.util.StringUtils;
import com.openerp.R;

public class MailMessage extends OModel {
	private Context mContext = null;

	OColumn type = new OColumn("Type", OInteger.class).setDefault("email");
	OColumn email_from = new OColumn("Email", OVarchar.class, 64)
			.setDefault("false");
	OColumn author_id = new OColumn("Author", ResPartner.class,
			RelationType.ManyToOne);
	OColumn partner_ids = new OColumn("To", ResPartner.class,
			RelationType.ManyToMany).setRequired(true).setRecordSyncLimit(25);
	OColumn notified_partner_ids = new OColumn("Notified Partners",
			ResPartner.class, RelationType.ManyToMany).setRecordSyncLimit(25);
	OColumn attachment_ids = new OColumn("Attachments", IrAttachment.class,
			RelationType.ManyToMany);
	OColumn parent_id = new OColumn("Parent", MailMessage.class,
			RelationType.ManyToOne).setDefault(0);
	OColumn child_ids = new OColumn("Childs", MailMessage.class,
			RelationType.OneToMany).setRelatedColumn("parent_id");
	OColumn model = new OColumn("Model", OVarchar.class, 64)
			.setDefault("false");
	OColumn res_id = new OColumn("Resource ID", OInteger.class).setDefault(0);
	OColumn record_name = new OColumn("Record name", OText.class)
			.setDefault("false");
	OColumn notification_ids = new OColumn("Notifications",
			MailNotification.class, RelationType.OneToMany)
			.setRelatedColumn("message_id");
	OColumn subject = new OColumn("Subject", OVarchar.class, 100).setDefault(
			"false").setRequired(true);
	OColumn date = new OColumn("Date", ODateTime.class).setParsePattern(
			ODate.DEFAULT_FORMAT).setDefault(
			ODate.getUTCDate(ODate.DEFAULT_FORMAT));
	OColumn body = new OColumn("Body", OHtml.class).setDefault("").setRequired(
			true);
	OColumn vote_user_ids = new OColumn("Voters", ResUsers.class,
			RelationType.ManyToMany);

	@Odoo.Functional(method = "getToRead", store = true, depends = { "notification_ids" })
	OColumn to_read = new OColumn("To Read", OBoolean.class).setDefault(true);
	@Odoo.Functional(method = "getStarred", store = true, depends = { "notification_ids" })
	OColumn starred = new OColumn("Starred", OBoolean.class).setDefault(false);

	@Odoo.Functional(method = "storeAuthorName", store = true, depends = {
			"author_id", "email_from" })
	OColumn author_name = new OColumn("Author Name", OVarchar.class)
			.setLocalColumn();

	@Odoo.Functional(method = "storeShortBody", store = true, depends = { "body" })
	OColumn short_body = new OColumn("Short Body", OVarchar.class)
			.setLocalColumn();
	// Functional Fields
	@Odoo.Functional(method = "setMessageTitle", store = true, depends = {
			"record_name", "subject" }, checkRowId = false)
	OColumn message_title = new OColumn("Title", OVarchar.class)
			.setLocalColumn();
	@Odoo.Functional(method = "hasVoted")
	OColumn has_voted = new OColumn("Has voted", OVarchar.class);
	@Odoo.Functional(method = "getVoteCounter")
	OColumn vote_counter = new OColumn("Votes", OInteger.class);
	@Odoo.Functional(method = "getPartnersName")
	OColumn partners_name = new OColumn("Partners", OVarchar.class);

	@Odoo.Functional(method = "getReplies", depends = { "child_ids" }, store = true)
	OColumn total_childs = new OColumn("Replies", OVarchar.class)
			.setLocalColumn();

	private List<Integer> mNewCreateIds = new ArrayList<Integer>();
	private MailNotification notification = null;

	public MailMessage(Context context) {
		super(context, "mail.message");
		mContext = context;
		notification = new MailNotification(mContext);
		write_date.setDefault(false);
		create_date.setDefault(false);
		to_read.setLocalColumn(false);
		starred.setLocalColumn(false);
	}

	public String getReplies(OValues values) {
		JSONArray childs = (JSONArray) values.get("child_ids");
		if (childs.length() > 0)
			return childs.length() + " replies";
		return "";
	}

	public Integer author_id() {
		return new ResPartner(mContext).selectRowId(user().getPartner_id());
	}

	@Override
	public Boolean checkForWriteDate() {
		return false;
	}

	public Boolean getValueofReadUnReadField(int id) {
		boolean read = false;
		ODataRow row = select(id);
		read = row.getBoolean("to_read");
		return read;
	}

	@Override
	public Boolean checkForLocalUpdate() {
		return false;
	}

	@Override
	public Boolean checkForLocalLatestUpdate() {
		return false;
	}

	@Override
	public Boolean canCreateOnServer() {
		return false;
	}

	@Override
	public Boolean canDeleteFromLocal() {
		return false;
	}

	@Override
	public Boolean canDeleteFromServer() {
		return false;
	}

	@Override
	public Boolean canUpdateToServer() {
		return false;
	}

	public Boolean getToRead(OValues vals) {
		try {
			JSONArray ids = (JSONArray) vals.get("notification_ids");
			ODataRow noti = notification.select(ids.getInt(0));
			return (noti.contains("is_read")) ? !noti.getBoolean("is_read")
					: !noti.getBoolean("read");
		} catch (Exception e) {

		}
		return vals.getBoolean("to_read");
	}

	public Boolean getStarred(OValues vals) {
		try {
			JSONArray ids = (JSONArray) vals.get("notification_ids");
			ODataRow noti = notification.select(ids.getInt(0));
			return noti.getBoolean("starred");
		} catch (Exception e) {

		}
		return vals.getBoolean("starred");
	}

	public boolean markAsTodo(Cursor c, Boolean todo_state) {
		try {
			OArguments args = new OArguments();
			args.add(new JSONArray().put(c.getInt(c.getColumnIndex("id"))));
			args.add(todo_state);
			args.add(true);
			getSyncHelper().callMethod("set_message_starred", args, null);
			OValues values = new OValues();
			// updating local record
			if (todo_state == true) {
				values.put("starred", "1");
				update(values, c.getInt(c.getColumnIndex(OColumn.ROW_ID)));
			} else {
				values.put("starred", "0");
				update(values, c.getInt(c.getColumnIndex(OColumn.ROW_ID)));
			}
			// Fix me
			// update Notification

		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public int create(OValues values) {
		int newId = super.create(values);
		if (values.contains("to_read") && values.getBoolean("to_read"))
			mNewCreateIds.add(newId);
		return newId;
	}

	public List<Integer> newMessageIds() {
		return mNewCreateIds;
	}

	public Integer sendQuickReply(String subject, String body, Integer parent_id) {
		body += mContext.getResources().getString(R.string.mail_watermark);
		ContentProviderOperation.Builder batch = ContentProviderOperation
				.newInsert(uri());
		ArrayList<ContentProviderOperation> batches = new ArrayList<ContentProviderOperation>();
		batch.withValue("subject", subject);
		batch.withValue("body", body);
		batch.withValue("parent_id", parent_id);
		batch.withValue("author_id", author_id());
		batch.withValue("date", ODate.getUTCDate(ODate.DEFAULT_FORMAT));
		List<Integer> p_ids = new ArrayList<Integer>();
		for (ODataRow partner : select(parent_id).getM2MRecord("partner_ids")
				.browseEach()) {
			p_ids.add(partner.getInt(OColumn.ROW_ID));
		}
		batch.withValue("partner_ids", p_ids.toString());
		batches.add(batch.build());
		try {
			mContext.getContentResolver().applyBatch(authority(), batches);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}

	public boolean markAsRead(ODataRow row, Boolean is_read) {
		try {
			List<Integer> mIds = new ArrayList<Integer>();
			List<ODataRow> childs = new ArrayList<ODataRow>();
			Object default_model = false;
			Object default_res_id = false;
			ODataRow parent = ((Integer) row.getM2ORecord("parent_id").getId() == 0) ? row
					: row.getM2ORecord("parent_id").browse();
			default_model = parent.get("model");
			default_res_id = parent.get("res_id");
			mIds.add(parent.getInt("id"));
			childs.addAll(parent.getO2MRecord("child_ids").browseEach());
			for (ODataRow child : childs) {
				mIds.add(child.getInt("id"));
			}
			JSONObject newContext = new JSONObject();
			newContext.put("default_parent_id", parent.getInt("id"));
			newContext.put("default_model", default_model);
			newContext.put("default_res_id", default_res_id);

			OArguments args = new OArguments();
			args.add(new JSONArray(mIds.toString()));
			args.add(is_read);
			args.add(true);
			args.add(newContext);
			Integer updated = (Integer) getSyncHelper().callMethod(
					"set_message_read", args, null);
			if (updated > 0) {
				OValues values = new OValues();
				values.put("to_read", !is_read);
				// updating local record
				for (Integer id : mIds)
					update(values, selectRowId(id));
				// updating mail notification
				values = new OValues();
				if (notification.getColumn("is_read") != null)
					values.put("is_read", is_read);
				else
					values.put("read", is_read);
				for (Integer id : mIds) {
					int message_id = selectRowId(id);
					String where = "message_id = ?";
					Object[] selection_args = new Object[] { message_id };
					if (notification.count(where, selection_args) > 0)
						notification.update(values, where, selection_args);
					else {
						values.put("message_id", message_id);
						values.put("partner_id", author_id());
						notification.create(values);
					}
				}
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	public String getPartnersName(ODataRow row) {
		String partners = "to ";
		List<String> partners_name = new ArrayList<String>();
		for (ODataRow p : row.getM2MRecord("partner_ids").browseEach()) {
			partners_name.add(p.getString("name"));
		}
		return partners + TextUtils.join(", ", partners_name);
	}

	public String getVoteCounter(ODataRow row) {
		int votes = row.getM2MRecord(vote_user_ids.getName()).getRelIds()
				.size();
		if (votes > 0)
			return votes + "";
		return "";
	}

	public Boolean hasVoted(ODataRow row) {
		for (Integer id : row.getM2MRecord("vote_user_ids").getRelIds()) {
			if (id == author_id()) {
				return true;
			}
		}
		return false;
	}

	public Boolean hasAttachment(ODataRow row) {
		if (row.getM2MRecord("attachment_ids").getRelIds().size() > 0)
			return true;
		return false;
	}

	public String setMessageTitle(OValues row) {
		String title = "false";
		if (!row.getString("record_name").equals("false"))
			title = row.getString("record_name");
		if (!title.equals("false") && !row.getString("subject").equals("false"))
			title += ": " + row.getString("subject");
		if (title.equals("false") && !row.getString("subject").equals("false"))
			title = row.getString("subject");
		if (title.equals("false"))
			title = "comment";
		return title;
	}

	public String getChildCount(ODataRow row) {
		int childs = row.getO2MRecord("child_ids").getIds(this).size();
		return (childs > 0) ? childs + " replies" : " ";
	}

	public String storeAuthorName(OValues row) {
		try {
			if (row.getString("author_id").equals("false"))
				return row.getString("email_from");
			JSONArray author_id = (JSONArray) row.get("author_id");
			return author_id.getString(1);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return row.getString("email_from");
	}

	public String storeShortBody(OValues row) {
		String body = StringUtils.htmlToString(row.getString("body"));
		int end = (body.length() > 100) ? 100 : body.length();
		return body.substring(0, end);
	}

	@Override
	public ODomain defaultDomain() {
		Integer user_id = user().getUser_id();
		ODomain domain = new ODomain();
		domain.add("|");
		domain.add("partner_ids.user_ids", "in", new JSONArray().put(user_id));
		domain.add("|");
		domain.add("notification_ids.partner_id.user_ids", "in",
				new JSONArray().put(user_id));
		domain.add("author_id.user_ids", "in", new JSONArray().put(user_id));
		return domain;
	}

	@Override
	public OContentProvider getContentProvider() {
		return new MailProvider();
	}

	public Uri mailUri() {
		Uri.Builder uriBuilder = new Uri.Builder();
		uriBuilder.authority(authority());
		uriBuilder.path(path() + "/inbox");
		uriBuilder.scheme("content");
		return uriBuilder.build();
	}

	public Uri mailDetailUri() {
		Uri.Builder uriBuilder = new Uri.Builder();
		uriBuilder.authority(authority());
		uriBuilder.path(path() + "/details");
		uriBuilder.scheme("content");
		return uriBuilder.build();
	}
}

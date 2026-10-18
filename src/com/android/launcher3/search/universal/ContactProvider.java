package com.android.launcher3.search.universal;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.provider.ContactsContract;

import com.android.launcher3.LauncherPrefs;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ContactProvider implements SearchProvider {

    private static String loadNumber(Context context, long contactId) {
        try (Cursor c = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                new String[]{String.valueOf(contactId)}, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // WhatsApp adds a data row of this type to contacts that use it; viewing it opens the chat.
    private static final String WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile";
    private static final String WHATSAPP = "com.whatsapp";

    private static Intent whatsappChat(Context context, long contactId) {
        try (Cursor c = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID},
                ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE
                        + "=?",
                new String[]{String.valueOf(contactId), WHATSAPP_PROFILE}, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            return new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ContentUris.withAppendedId(
                            ContactsContract.Data.CONTENT_URI, c.getLong(0)), WHATSAPP_PROFILE)
                    .setPackage(WHATSAPP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Drawable loadPhoto(Context context, Uri contactUri) {
        try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(
                context.getContentResolver(), contactUri, true)) {
            if (in == null) {
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final String[] PROJECTION = {
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
    };

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_CONTACT;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_CONTACTS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.isEmpty()) {
            return out;
        }
        if (!hasPermission(context)) {
            return SearchProvider.permissionRequest(context, getSource(),
                    com.android.launcher3.R.string.search_permission_contacts);
        }
        Uri uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query));
        try (Cursor c = context.getContentResolver().query(
                uri, PROJECTION, null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC")) {
            if (c == null) {
                return out;
            }
            int idIndex = c.getColumnIndex(ContactsContract.Contacts._ID);
            int lookupIndex = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
            int nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
            while (c.moveToNext() && out.size() < max) {
                String name = nameIndex < 0 ? null : c.getString(nameIndex);
                String lookup = lookupIndex < 0 ? null : c.getString(lookupIndex);
                if (name == null || lookup == null) {
                    continue;
                }
                Uri contactUri = ContactsContract.Contacts.getLookupUri(
                        idIndex < 0 ? 0 : c.getLong(idIndex), lookup);
                Intent intent = new Intent(Intent.ACTION_VIEW, contactUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_CONTACT,
                        lookup,
                        name,
                        null,
                        intent,
                        Process.myUserHandle(),
                        ShortcutProvider.score(query.toLowerCase(), name));
                result.icon = loadPhoto(context, contactUri);
                result.thumbnail = result.icon != null;
                int phoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER);
                if (phoneIndex >= 0 && c.getInt(phoneIndex) > 0) {
                    result.phoneNumber = loadNumber(context, c.getLong(idIndex));
                    result.whatsapp = whatsappChat(context, c.getLong(idIndex));
                }
                out.add(result);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return out;
        }
        return out;
    }
}

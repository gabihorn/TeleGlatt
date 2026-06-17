package org.telegram.messenger.askan;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Shared UI helpers for Askan access-request flow.
 * Called from ChatActivity (when user tries to open a blocked chat) and
 * AskanBlockedChatsActivity (from the blocked-chats list screen).
 */
public class AskanUiHelper {

    /**
     * Shows the "request access" note dialog and fires AskanFilter.sendAccessRequest().
     *
     * @param ctx          Android context (parent activity)
     * @param account      Telegram account index (for UserConfig)
     * @param chatUsername username or numeric-id string of the target chat/bot
     * @param chatName     display name of the target chat/bot
     * @param subject      "ערוץ" / "קבוצה" / "בוט"
     * @param onSent       callback fired on UI thread after successful send (e.g. mark button disabled)
     */
    public static void showAccessRequestNoteDialog(
            Context ctx, int account,
            String chatUsername, String chatName, String subject,
            Runnable onSent) {

        android.widget.EditText noteInput = new android.widget.EditText(ctx);
        noteInput.setHint("הסבר קצר (אופציונלי)");
        noteInput.setPadding(40, 20, 40, 20);
        noteInput.setMaxLines(3);

        AlertDialog.Builder noteBuilder = new AlertDialog.Builder(ctx);
        noteBuilder.setTitle("בקשת גישה ל" + subject);
        noteBuilder.setMessage("מדוע אתה צריך גישה? (ניתן להשאיר ריק)");
        noteBuilder.setView(noteInput);
        noteBuilder.setPositiveButton("שלח בקשה", (d, w) -> {
            TLRPC.User selfUser = UserConfig.getInstance(account).getCurrentUser();
            String phone = selfUser != null ? selfUser.phone : null;

            if (phone == null || phone.isEmpty()) {
                Toast.makeText(ctx, "לא ניתן לשלוח בקשה כרגע", Toast.LENGTH_SHORT).show();
                return;
            }

            String note = noteInput.getText().toString().trim();

            AskanFilter.getInstance().sendAccessRequest(
                    phone, chatUsername, chatName, note,
                    () -> {
                        Toast.makeText(ctx, "בקשתך נשלחה ותיבדק על ידי המנהל",
                                Toast.LENGTH_LONG).show();
                        if (onSent != null) onSent.run();
                    },
                    () -> Toast.makeText(ctx, "הבקשה לא התקבלה, נסה שוב מאוחר יותר",
                            Toast.LENGTH_LONG).show()
            );
        });
        noteBuilder.setNegativeButton("ביטול", null);
        noteBuilder.show();
    }
}

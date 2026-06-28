package org.telegram.messenger.askan;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

/**
 * Shared UI helpers for Askan access-request flow.
 * Called from ChatActivity (when user tries to open a blocked chat) and
 * AskanBlockedChatsActivity (from the blocked-chats list screen).
 */
public class AskanUiHelper {

    private static final int BRAND_NAVY = 0xFF1A1A6E;

    /**
     * Single shared "blocked content" bottom sheet. Used by both ChatActivity
     * (opening a blocked chat) and LaunchActivity (opening a blocked link),
     * so the design and wording stay identical everywhere.
     */
    public static void showBlockedSheet(Context ctx, int account, TLRPC.Chat chat, TLRPC.User user) {
        if (ctx == null) return;

        String name = chat != null ? chat.title
                : (user != null && user.first_name != null ? user.first_name : "");
        boolean isBot = user != null && user.bot;
        String subject = isBot ? "בוט"
                : (chat != null && ChatObject.isMegagroup(chat) ? "קבוצה" : "ערוץ");

        final String identifier;
        if (chat != null) {
            identifier = (chat.username != null && !chat.username.isEmpty())
                    ? chat.username : String.valueOf(chat.id);
        } else if (user != null) {
            identifier = (user.username != null && !user.username.isEmpty())
                    ? user.username : String.valueOf(user.id);
        } else {
            identifier = "";
        }
        final String chatName = name.isEmpty() ? subject : name;

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(20));

        // Avatar
        BackupImageView avatarView = new BackupImageView(ctx);
        avatarView.setRoundRadius(dp(32));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        } else if (user != null) {
            avatarDrawable.setInfo(account, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        }
        content.addView(avatarView, new LinearLayout.LayoutParams(dp(64), dp(64)));

        // Title
        TextView titleView = new TextView(ctx);
        titleView.setText(chatName);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(12);
        content.addView(titleView, titleLp);

        // Subtitle
        TextView subtitleView = new TextView(ctx);
        subtitleView.setText(subject + " זה דורש אישור מראש");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        content.addView(subtitleView, subLp);

        // Info box
        LinearLayout infoBox = new LinearLayout(ctx);
        infoBox.setOrientation(LinearLayout.HORIZONTAL);
        infoBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        infoBg.setCornerRadius(dp(10));
        infoBox.setBackground(infoBg);
        infoBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(16);

        TextView infoIcon = new TextView(ctx);
        infoIcon.setText("🔒");
        infoIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        infoBox.addView(infoIcon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View iconSpacer = new View(ctx);
        infoBox.addView(iconSpacer, new LinearLayout.LayoutParams(dp(8), 1));
        TextView infoText = new TextView(ctx);
        infoText.setText("ניתן להגיש בקשת גישה. הבקשה תיבדק על ידי הצוות ותטופל בהקדם.");
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        infoText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        infoText.setLineSpacing(dp(2), 1f);
        infoBox.addView(infoText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(infoBox, infoLp);

        // Request button
        final boolean alreadyPending = isLocallyPending(ctx, identifier);
        TextView requestBtn = new TextView(ctx);
        requestBtn.setText(alreadyPending ? "בקשה ממתינה לאישור ⏳" : "בקשת גישה");
        requestBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        requestBtn.setTypeface(Typeface.DEFAULT_BOLD);
        requestBtn.setGravity(Gravity.CENTER);
        requestBtn.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(12));
        if (alreadyPending) {
            int orange = Theme.getColor(Theme.key_color_orange);
            requestBtn.setTextColor(orange);
            btnBg.setColor((orange & 0x00FFFFFF) | 0x1F000000);
            btnBg.setStroke(dp(1), (orange & 0x00FFFFFF) | 0x55000000);
        } else {
            requestBtn.setTextColor(Color.WHITE);
            btnBg.setColor(BRAND_NAVY);
        }
        requestBtn.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(16);
        content.addView(requestBtn, btnLp);

        // Close link
        TextView closeBtn = new TextView(ctx);
        closeBtn.setText("סגור");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(0, dp(12), 0, dp(4));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(4);
        content.addView(closeBtn, closeLp);

        BottomSheet sheet = new BottomSheet.Builder(ctx, false)
                .setCustomView(content)
                .create();
        sheet.show();

        requestBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (!alreadyPending) {
                showAccessRequestNoteDialog(ctx, account, identifier, chatName, subject, null);
            }
        });
        closeBtn.setOnClickListener(v -> sheet.dismiss());
    }

    /**
     * Shows the "request access" note sheet and fires AskanFilter.sendAccessRequest().
     *
     * @param ctx          Android context (parent activity)
     * @param account      Telegram account index (for UserConfig)
     * @param chatUsername username or numeric-id string of the target chat/bot
     * @param chatName     display name of the target chat/bot
     * @param subject      "ערוץ" / "קבוצה" / "בוט"
     * @param onSent       callback fired on UI thread after successful send
     */
    public static void showAccessRequestNoteDialog(
            Context ctx, int account,
            String chatUsername, String chatName, String subject,
            Runnable onSent) {

        // ── Sheet content ───────────────────────────────────────────────────
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(18));

        // Title
        TextView titleView = new TextView(ctx);
        titleView.setText("בקשת גישה — " + chatName);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setMaxLines(2);
        content.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Subtitle
        TextView subtitleView = new TextView(ctx);
        subtitleView.setText("מדוע אתה צריך גישה? ניתן להשאיר ריק");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        content.addView(subtitleView, subLp);

        // Note input — rounded field, theme-aware
        EditText noteInput = new EditText(ctx);
        noteInput.setHint("הסבר קצר (אופציונלי)");
        noteInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        noteInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        noteInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        noteInput.setMaxLines(3);
        noteInput.setFocusable(true);
        noteInput.setFocusableInTouchMode(true);
        noteInput.setBackground(null);
        noteInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fieldBg.setCornerRadius(dp(12));
        fieldBg.setStroke(dp(1), Theme.getColor(Theme.key_divider));
        noteInput.setBackground(fieldBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(16);
        content.addView(noteInput, inputLp);

        // Primary button — brand navy
        TextView sendBtn = new TextView(ctx);
        sendBtn.setText("שלח בקשה");
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(BRAND_NAVY);
        sendBg.setCornerRadius(dp(12));
        sendBtn.setBackground(sendBg);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sendLp.topMargin = dp(18);
        content.addView(sendBtn, sendLp);

        // Cancel link
        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("ביטול");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancelBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(0, dp(12), 0, dp(4));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = dp(4);
        content.addView(cancelBtn, cancelLp);

        // ── Show ────────────────────────────────────────────────────────────
        BottomSheet sheet = new BottomSheet.Builder(ctx, false)
                .setCustomView(content)
                .create();
        sheet.setFocusable(true); // allow the sheet window to receive keyboard input
        sheet.show();

        // Auto-focus the note field and pop the keyboard once the sheet is open
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            noteInput.requestFocus();
            org.telegram.messenger.AndroidUtilities.showKeyboard(noteInput);
        }, 150);

        cancelBtn.setOnClickListener(v -> sheet.dismiss());
        sendBtn.setOnClickListener(v -> {
            TLRPC.User selfUser = UserConfig.getInstance(account).getCurrentUser();
            String phone = selfUser != null ? selfUser.phone : null;
            if (phone == null || phone.isEmpty()) {
                Toast.makeText(ctx, "לא ניתן לשלוח בקשה כרגע", Toast.LENGTH_SHORT).show();
                return;
            }

            // Persist subject type so notification text can be entity-specific
            ctx.getSharedPreferences("askan_req_statuses", Context.MODE_PRIVATE)
               .edit().putString("subj_" + chatUsername, subject).apply();

            sendBtn.setText("שולח...");
            sendBtn.setEnabled(false);
            sendBtn.setAlpha(0.7f);

            String note = noteInput.getText().toString().trim();
            AskanFilter.getInstance().sendAccessRequest(
                    phone, chatUsername, chatName, note,
                    status -> {
                        String msg;
                        switch (status) {
                            case "pending":
                                markPending(ctx, chatUsername);
                                Toast.makeText(ctx, "בקשתך נשלחה ותיבדק על ידי הצוות בהקדם", Toast.LENGTH_LONG).show();
                                sheet.dismiss();
                                if (onSent != null) onSent.run();
                                return;
                            case "already_pending":
                                markPending(ctx, chatUsername);
                                Toast.makeText(ctx, "בקשה קיימת כבר ממתינה לאישור", Toast.LENGTH_LONG).show();
                                sheet.dismiss();
                                if (onSent != null) onSent.run();
                                return;
                            case "globally_blocked":
                                msg = "תוכן זה אינו זמין";
                                break;
                            case "blocked":
                                msg = "הגעת למקסימום הניסיונות";
                                break;
                            case "rejected":
                                msg = "הבקשה נדחתה";
                                break;
                            default:
                                msg = "הבקשה לא התקבלה, נסה שוב מאוחר יותר";
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                        sendBtn.setText("שלח בקשה");
                        sendBtn.setEnabled(true);
                        sendBtn.setAlpha(1f);
                    }
            );
        });
    }

    /** Persists a local "request pending" flag so the blocked dialog can reflect it on reopen. */
    private static void markPending(Context ctx, String chatUsername) {
        if (chatUsername == null || chatUsername.isEmpty()) return;
        ctx.getSharedPreferences("askan_req_statuses", Context.MODE_PRIVATE)
           .edit().putBoolean("pending_" + chatUsername, true).apply();
    }

    /** True if a request for this chat is locally marked as pending (cleared on approve/reject). */
    public static boolean isLocallyPending(Context ctx, String chatUsername) {
        if (chatUsername == null || chatUsername.isEmpty()) return false;
        return ctx.getSharedPreferences("askan_req_statuses", Context.MODE_PRIVATE)
                  .getBoolean("pending_" + chatUsername, false);
    }
}

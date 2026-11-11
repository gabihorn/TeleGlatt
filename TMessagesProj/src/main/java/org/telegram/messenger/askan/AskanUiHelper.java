package org.telegram.messenger.askan;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
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

    private static long identityDialogShownAt;

    /**
     * Shown on the chat list when the server refuses this number because the user
     * opened a new Telegram account on it. At most once every few hours.
     */
    public static void maybeShowIdentityDialog(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        int account = fragment.getCurrentAccount();
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        if (me == null || !AskanFilter.getInstance().hasIdentityMismatch(me.phone)) return;
        long now = System.currentTimeMillis();
        if (now - identityDialogShownAt < 6 * 60 * 60 * 1000L) return;
        identityDialogShownAt = now;
        showIdentityDialog(fragment.getParentActivity(), account);
    }

    public static void showIdentityDialog(Context ctx, int account) {
        new AlertDialog.Builder(ctx)
                .setTitle("חשבון הטלגרם השתנה")
                .setMessage("נראה שנפתח חשבון טלגרם חדש על המספר הזה, ולכן שרת הסינון לא מזהה אותו. "
                        + "עד שהחשבון יאומת, בקשות לא נשלחות וההרשאות לא מתעדכנות.\n\n"
                        + "האימות אוטומטי: TeleGlatt ישלח את המספר שלך לבוט הרשמי @TeleGlatt_bot, "
                        + "וטלגרם תאשר שהוא שייך לחשבון הזה.")
                .setPositiveButton("אמת עכשיו", (d, w) -> {
                    Toast.makeText(ctx, "מאמת את החשבון...", Toast.LENGTH_SHORT).show();
                    AskanFilter.getInstance().verifyIdentity(account, ok ->
                            Toast.makeText(ctx, ok
                                    ? "החשבון אומת. הכל חזר לעבוד"
                                    : "האימות לא הצליח. אפשר לשלוח דוח לתמיכה מאזור השליטה",
                                    Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("לא עכשיו", null)
                .show();
    }

    /**
     * Single shared "blocked content" bottom sheet. Used by both ChatActivity
     * (opening a blocked chat) and LaunchActivity (opening a blocked link),
     * so the design and wording stay identical everywhere.
     */
    // ─── Blocked chat sheet ───────────────────────────────────────────────────
    //
    // Three states, one quiet layout in the spirit of Telegram's own sheets:
    //   NEW      — not approved yet: reason chips + optional note + "request access"
    //   PENDING  — already asked: when, and what was written; no way to ask again
    //   BLOCKED  — the team blocked it: one plain sentence, no request button
    // A small badge on the avatar (lock / clock / x) carries the state.

    private static final String[] REQUEST_REASONS = {"לימודים", "עבודה", "משפחה", "חדשות", "אחר"};
    private static final int BADGE_LOCK = 0, BADGE_CLOCK = 1, BADGE_X = 2;

    public static void showBlockedSheet(Context ctx, int account, TLRPC.Chat chat, TLRPC.User user) {
        if (ctx == null) return;

        String name = chat != null ? chat.title
                : (user != null && user.first_name != null ? user.first_name : "");
        boolean isBot = user != null && user.bot;
        boolean isGroup = chat != null && ChatObject.isMegagroup(chat);
        String subject = isBot ? "בוט" : (isGroup ? "קבוצה" : "ערוץ");

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

        TLRPC.ChatFull chatFull = chat != null
                ? org.telegram.messenger.MessagesController.getInstance(account).getChatFull(chat.id) : null;
        AskanFilter.BlockReason reason = (chat != null)
                ? AskanFilter.getInstance().getChatBlockReason(chat, chatFull)
                : AskanFilter.getInstance().getUserBlockReason(user);
        // Explicitly blocked (by the team, for everyone / the company / this user): the
        // server answers any request with "blocked", so don't offer one.
        final boolean explicitlyBlocked = reason == AskanFilter.BlockReason.EXPLICIT;
        final boolean pending = !explicitlyBlocked && isLocallyPending(ctx, identifier);

        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(16), 0, dp(12));

        // ── Avatar with state badge ──
        FrameLayout avatarFrame = new FrameLayout(ctx);
        BackupImageView avatarView = new BackupImageView(ctx);
        avatarView.setRoundRadius(dp(40));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        } else if (user != null) {
            avatarDrawable.setInfo(account, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        }
        if (explicitlyBlocked) avatarView.setAlpha(0.6f);
        avatarFrame.addView(avatarView, new FrameLayout.LayoutParams(dp(80), dp(80)));
        int badgeColor = explicitlyBlocked ? 0xFF8A8F98 : (pending ? 0xFFFBB244 : BRAND_NAVY);
        int badgeKind = explicitlyBlocked ? BADGE_X : (pending ? BADGE_CLOCK : BADGE_LOCK);
        StateBadge badge = new StateBadge(ctx, badgeKind, badgeColor,
                Theme.getColor(Theme.key_dialogBackground));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(28), dp(28));
        badgeLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        avatarFrame.addView(badge, badgeLp);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(dp(80), dp(80));
        frameLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(avatarFrame, frameLp);

        // ── Title ──
        TextView titleView = new TextView(ctx);
        titleView.setText(chatName);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        titleView.setTextColor(textColor);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(24), 0, dp(24), 0);
        content.addView(titleView, matchWrap(12));

        // ── Subtitle: what it is (+ members), or the state with a small dot ──
        TextView subView = new TextView(ctx);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subView.setTextColor(grayColor);
        subView.setGravity(Gravity.CENTER);
        if (explicitlyBlocked || pending) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("  ");
            sb.setSpan(new DotSpan(explicitlyBlocked ? 0xFFC0392B : 0xFFFBB244), 0, 1, 0);
            sb.append(explicitlyBlocked ? "לא אושר" : "ממתין לאישור");
            subView.setText(sb);
        } else {
            subView.setText(subjectLine(subject, isGroup, isBot, chat, chatFull));
        }
        content.addView(subView, matchWrap(4));

        BottomSheet[] sheetRef = new BottomSheet[1];

        if (explicitlyBlocked) {
            content.addView(bodyText(ctx, "צוות הסינון בדק את ה" + subject + " הזה ולא אישר אותו, ולכן אי אפשר לבקש אותו שוב.",
                    textColor), matchWrap(14));
            content.addView(divider(ctx), dividerLp(18));
            content.addView(flatButton(ctx, "הבקשות שלי", BRAND_NAVY_TEXT(), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                openMyRequests();
            }), matchWrap(4));
            content.addView(flatButton(ctx, "סגירה", grayColor, v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
            }), matchWrap(0));
        } else if (pending) {
            content.addView(divider(ctx), dividerLp(18));
            long sentAt = AskanFilter.reqStatusPrefs(ctx).getLong("pending_at_" + identifier, 0);
            content.addView(infoRow(ctx, sentAt > 0 ? "נשלחה " + sentWhen(sentAt) : "הבקשה נשלחה",
                    "נעדכן אותך כשתהיה תשובה", textColor, grayColor), matchWrap(0));
            String sentNote = AskanFilter.reqStatusPrefs(ctx).getString("pending_note_" + identifier, "");
            if (sentNote != null && !sentNote.isEmpty()) {
                content.addView(infoRow(ctx, sentNote, "מה שכתבת בבקשה", textColor, grayColor), matchWrap(0));
            }
            content.addView(divider(ctx), dividerLp(10));
            content.addView(flatButton(ctx, "הבקשות שלי", BRAND_NAVY_TEXT(), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                openMyRequests();
            }), matchWrap(4));
        } else {
            content.addView(bodyText(ctx, "ה" + subject + " הזה עדיין לא נבדק על ידי הסינון.", textColor),
                    matchWrap(14));
            content.addView(divider(ctx), dividerLp(18));

            TextView askLabel = new TextView(ctx);
            askLabel.setText("בשביל מה?");
            askLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            askLabel.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            askLabel.setTextColor(BRAND_NAVY_TEXT());
            askLabel.setPadding(dp(20), 0, dp(20), 0);
            content.addView(askLabel, matchWrap(14));

            // reason chips — one may be selected; tapping it again clears it
            final String[] chosen = {null};
            android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(ctx);
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(dp(20), 0, dp(20), 0);
            TextView[] chipViews = new TextView[REQUEST_REASONS.length];
            for (int i = 0; i < REQUEST_REASONS.length; i++) {
                final String r = REQUEST_REASONS[i];
                TextView c = new TextView(ctx);
                c.setText(r);
                c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                c.setPadding(dp(12), dp(6), dp(12), dp(6));
                chipViews[i] = c;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = dp(6);
                chips.addView(c, clp);
                c.setOnClickListener(v -> {
                    chosen[0] = r.equals(chosen[0]) ? null : r;
                    styleChips(chipViews, chosen[0]);
                });
            }
            styleChips(chipViews, null);
            chipScroll.addView(chips);
            content.addView(chipScroll, matchWrap(8));

            EditText noteInput = new EditText(ctx);
            noteInput.setHint("הסבר קצר (לא חובה)");
            noteInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            noteInput.setTextColor(textColor);
            noteInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            noteInput.setMaxLines(3);
            noteInput.setBackground(Theme.createEditTextDrawable(ctx, false));
            noteInput.setPadding(0, dp(6), 0, dp(10));
            LinearLayout.LayoutParams noteLp = matchWrap(10);
            noteLp.leftMargin = dp(20);
            noteLp.rightMargin = dp(20);
            content.addView(noteInput, noteLp);

            TextView sendBtn = new TextView(ctx);
            sendBtn.setText("בקשת גישה");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            sendBtn.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            sendBtn.setTextColor(Color.WHITE);
            sendBtn.setGravity(Gravity.CENTER);
            sendBtn.setPadding(0, dp(13), 0, dp(13));
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setCornerRadius(dp(8));
            btnBg.setColor(BRAND_NAVY);
            sendBtn.setBackground(btnBg);
            LinearLayout.LayoutParams btnLp = matchWrap(18);
            btnLp.leftMargin = dp(16);
            btnLp.rightMargin = dp(16);
            content.addView(sendBtn, btnLp);

            sendBtn.setOnClickListener(v -> {
                String typed = noteInput.getText().toString().trim();
                String note = chosen[0] == null ? typed
                        : (typed.isEmpty() ? chosen[0] : chosen[0] + " · " + typed);
                submitRequest(ctx, account, identifier, chatName, subject, note, sendBtn, "בקשת גישה",
                        () -> { if (sheetRef[0] != null) sheetRef[0].dismiss(); }, null);
            });
        }

        BottomSheet sheet = new BottomSheet.Builder(ctx, false)
                .setCustomView(content)
                .create();
        sheetRef[0] = sheet;
        if (!explicitlyBlocked && !pending) sheet.setFocusable(true); // note field needs the keyboard
        sheet.show();
    }

    private static int BRAND_NAVY_TEXT() {
        // navy reads poorly on a dark theme; fall back to the theme's link colour there
        return Theme.isCurrentThemeDark() ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : BRAND_NAVY;
    }

    private static String subjectLine(String subject, boolean isGroup, boolean isBot,
                                      TLRPC.Chat chat, TLRPC.ChatFull chatFull) {
        if (isBot || chat == null) return subject;
        int count = chatFull != null && chatFull.participants_count > 0
                ? chatFull.participants_count : chat.participants_count;
        if (count <= 0) return subject;
        return subject + " · " + String.format(java.util.Locale.US, "%,d", count)
                + (isGroup ? " חברים" : " מנויים");
    }

    /** "היום ב־14:05" / "אתמול ב־09:30" / "ב־5.11 ב־18:20" */
    public static String sentWhen(long at) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        String hm = String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        int dayDiff = (now.get(java.util.Calendar.YEAR) - c.get(java.util.Calendar.YEAR)) * 366
                + now.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR);
        if (dayDiff == 0) return "היום ב־" + hm;
        if (dayDiff == 1) return "אתמול ב־" + hm;
        return "ב־" + c.get(java.util.Calendar.DAY_OF_MONTH) + "." + (c.get(java.util.Calendar.MONTH) + 1)
                + " ב־" + hm;
    }

    private static void openMyRequests() {
        org.telegram.ui.ActionBar.BaseFragment f = org.telegram.ui.LaunchActivity.getSafeLastFragment();
        if (f != null) f.presentFragment(new org.telegram.ui.AskanBlockedChatsActivity());
    }

    private static void styleChips(TextView[] chips, String chosen) {
        int on = BRAND_NAVY;
        int offBg = Theme.getColor(Theme.key_windowBackgroundGray);
        int offText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (TextView c : chips) {
            boolean sel = c.getText().toString().equals(chosen);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(sel ? on : offBg);
            c.setBackground(bg);
            c.setTextColor(sel ? Color.WHITE : offText);
        }
    }

    private static LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private static View divider(Context ctx) {
        View d = new View(ctx);
        d.setBackgroundColor(Theme.getColor(Theme.key_divider));
        return d;
    }

    private static LinearLayout.LayoutParams dividerLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private static TextView bodyText(Context ctx, String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(24), 0, dp(24), 0);
        return t;
    }

    private static View infoRow(Context ctx, String main, String sub, int textColor, int grayColor) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView m = new TextView(ctx);
        m.setText(main);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        m.setTextColor(textColor);
        m.setMaxLines(3);
        m.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(m);
        TextView s = new TextView(ctx);
        s.setText(sub);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setTextColor(grayColor);
        row.addView(s);
        return row;
    }

    private static TextView flatButton(Context ctx, String text, int color, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(12), 0, dp(12));
        b.setBackground(Theme.getSelectorDrawable(false));
        b.setOnClickListener(l);
        return b;
    }

    /** Small coloured dot in front of a status line. */
    private static class DotSpan extends android.text.style.ReplacementSpan {
        private final int color;
        DotSpan(int color) { this.color = color; }
        @Override public int getSize(android.graphics.Paint p, CharSequence t, int s, int e,
                                     android.graphics.Paint.FontMetricsInt fm) { return dp(9); }
        @Override public void draw(android.graphics.Canvas c, CharSequence t, int s, int e, float x,
                                   int top, int y, int bottom, android.graphics.Paint p) {
            int old = p.getColor();
            p.setColor(color);
            float cy = (top + bottom) / 2f;
            c.drawCircle(x + dp(3.5f), cy, dp(3.5f), p);
            p.setColor(old);
        }
    }

    /** Round badge drawn over the avatar corner: lock, clock or x. */
    private static class StateBadge extends View {
        private final int kind, color, ring;
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        StateBadge(Context ctx, int kind, int color, int ring) {
            super(ctx);
            this.kind = kind; this.color = color; this.ring = ring;
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            stroke.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(dp(1.8f));
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f, r = w / 2f;
            fill.setColor(ring);
            c.drawCircle(cx, cy, r, fill);
            fill.setColor(color);
            c.drawCircle(cx, cy, r - dp(2.5f), fill);
            float u = dp(1); // icon drawn on a ~12dp grid around the centre
            if (kind == BADGE_LOCK) {
                fill.setColor(Color.WHITE);
                rect.set(cx - 4.5f * u, cy - 1 * u, cx + 4.5f * u, cy + 5 * u);
                c.drawRoundRect(rect, 1.5f * u, 1.5f * u, fill);
                rect.set(cx - 3 * u, cy - 6 * u, cx + 3 * u, cy + 1 * u);
                c.drawArc(rect, 180, 180, false, stroke);
                c.drawLine(cx - 3 * u, cy - 2.5f * u, cx - 3 * u, cy - 1 * u, stroke);
                c.drawLine(cx + 3 * u, cy - 2.5f * u, cx + 3 * u, cy - 1 * u, stroke);
            } else if (kind == BADGE_CLOCK) {
                c.drawCircle(cx, cy, 5 * u, stroke);
                c.drawLine(cx, cy - 3 * u, cx, cy, stroke);
                c.drawLine(cx, cy, cx + 2.2f * u, cy + 1.4f * u, stroke);
            } else {
                c.drawLine(cx - 3.5f * u, cy - 3.5f * u, cx + 3.5f * u, cy + 3.5f * u, stroke);
                c.drawLine(cx + 3.5f * u, cy - 3.5f * u, cx - 3.5f * u, cy + 3.5f * u, stroke);
            }
        }
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
        sendBtn.setOnClickListener(v -> submitRequest(ctx, account, chatUsername, chatName, subject,
                noteInput.getText().toString().trim(), sendBtn, "שלח בקשה", sheet::dismiss, onSent));
    }

    /**
     * Sends an access request and reports the outcome. Shared by the blocked sheet and
     * the note dialog; {@code dismiss} closes whichever sheet asked.
     */
    private static void submitRequest(Context ctx, int account, String chatUsername, String chatName,
                                      String subject, String note, TextView sendBtn, String btnText,
                                      Runnable dismiss, Runnable onSent) {
            TLRPC.User selfUser = UserConfig.getInstance(account).getCurrentUser();
            String phone = selfUser != null ? selfUser.phone : null;
            if (phone == null || phone.isEmpty()) {
                Toast.makeText(ctx, "לא ניתן לשלוח בקשה כרגע", Toast.LENGTH_SHORT).show();
                return;
            }

            // Persist subject type so notification text can be entity-specific
            AskanFilter.reqStatusPrefs(ctx)
               .edit().putString("subj_" + chatUsername, subject).apply();

            sendBtn.setText("שולח...");
            sendBtn.setEnabled(false);
            sendBtn.setAlpha(0.7f);

            AskanFilter.getInstance().sendAccessRequest(
                    phone, chatUsername, chatName, note,
                    (status, serverMessage) -> {
                        String msg;
                        switch (status) {
                            case "pending":
                                markPending(ctx, chatUsername, note);
                                Toast.makeText(ctx, "בקשתך נשלחה ותיבדק על ידי הצוות בהקדם", Toast.LENGTH_LONG).show();
                                dismiss.run();
                                if (onSent != null) onSent.run();
                                return;
                            case "queued":
                                markPending(ctx, chatUsername, note);
                                Toast.makeText(ctx, "אין חיבור לשרת כרגע. הבקשה נשמרה ותישלח אוטומטית", Toast.LENGTH_LONG).show();
                                dismiss.run();
                                if (onSent != null) onSent.run();
                                return;
                            case "rate_limited":
                                msg = "שלחת הרבה בקשות בזמן קצר. נסה שוב בעוד כמה דקות";
                                break;
                            case "approved": {
                                // The server already allows it, so the local lists are stale.
                                // Refresh now instead of waiting for the 5-minute throttle.
                                AskanFilter.lastPermissionsFetch = 0;
                                AskanFilter.getInstance().fetchPermissions(phone,
                                        UserConfig.getInstance(account).getClientUserId());
                                Toast.makeText(ctx, "הגישה כבר מאושרת. ההרשאות מתעדכנות, נסה לפתוח שוב בעוד רגע", Toast.LENGTH_LONG).show();
                                dismiss.run();
                                return;
                            }
                            case "already_pending":
                                markPending(ctx, chatUsername, note);
                                Toast.makeText(ctx, "בקשה קיימת כבר ממתינה לאישור", Toast.LENGTH_LONG).show();
                                dismiss.run();
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
                        // The server's own wording is more specific ("rejected recently,
                        // try tomorrow", "daily limit reached") — prefer it when given.
                        if (serverMessage != null && !serverMessage.isEmpty() && !"rate_limited".equals(status)) {
                            msg = serverMessage;
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                        sendBtn.setText(btnText);
                        sendBtn.setEnabled(true);
                        sendBtn.setAlpha(1f);
                    }
            );
    }

    /** Persists a local "request pending" flag so the blocked dialog can reflect it on reopen. */
    private static void markPending(Context ctx, String chatUsername, String note) {
        if (chatUsername == null || chatUsername.isEmpty()) return;
        SharedPreferences.Editor e = AskanFilter.reqStatusPrefs(ctx).edit()
                .putBoolean("pending_" + chatUsername, true);
        // when and what was asked — shown on the sheet while the request waits
        if (!AskanFilter.reqStatusPrefs(ctx).getBoolean("pending_" + chatUsername, false)) {
            e.putLong("pending_at_" + chatUsername, System.currentTimeMillis());
            e.putString("pending_note_" + chatUsername, note != null ? note : "");
        }
        e.apply();
    }

    /** True if a request for this chat is locally marked as pending (cleared on approve/reject). */
    public static boolean isLocallyPending(Context ctx, String chatUsername) {
        if (chatUsername == null || chatUsername.isEmpty()) return false;
        if (!AskanFilter.reqStatusPrefs(ctx).getBoolean("pending_" + chatUsername, false)) {
            return false;
        }
        // A chat that is already allowed cannot still be pending. The flag is normally
        // cleared by checkRequestStatusChanges, but that only fires when it catches the
        // pending -> approved transition; if the app was killed in between, the flag
        // would otherwise stick forever and keep showing "under review" on a chat the
        // user can already open. Drop it here instead of trusting the transition.
        if (AskanFilter.getInstance().isExplicitlyAllowed(chatUsername, chatUsername)) {
            AskanFilter.reqStatusPrefs(ctx).edit().remove("pending_" + chatUsername)
                    .remove("pending_at_" + chatUsername).remove("pending_note_" + chatUsername).apply();
            return false;
        }
        return true;
    }

    // ─── Battery optimisation ─────────────────────────────────────────────────

    /**
     * Asks the user once to exempt TeleGlatt from battery optimisation.
     *
     * The keep-alive foreground service is our only way to deliver messages while
     * the app is closed — Telegram cannot push to our Firebase project — and the
     * aggressive power managers on Samsung and Xiaomi kill even a well-behaved
     * foreground service unless the app is exempt.
     *
     * Deliberately opens the system list (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
     * rather than the one-tap ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.
     * The one-tap version needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission,
     * which Play treats as sensitive and gates behind a declaration review. A couple
     * of extra taps is not worth risking the listing over.
     *
     * Shown at most once; "later" is remembered as a no.
     */
    public static void maybeAskBatteryExemption(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        SharedPreferences prefs = ctx.getSharedPreferences("askan_filter", Context.MODE_PRIVATE);
        if (prefs.getBoolean("battery_prompt_shown", false)) return;

        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                // Already exempt — nothing to ask, and don't ask later either.
                prefs.edit().putBoolean("battery_prompt_shown", true).apply();
                return;
            }
        } catch (Exception ignored) {
            return;
        }

        prefs.edit().putBoolean("battery_prompt_shown", true).apply();

        new AlertDialog.Builder(ctx)
                .setTitle("כדי לקבל הודעות בזמן")
                .setMessage("אנדרואיד עלולה לעצור את TeleGlatt ברקע, ואז הודעות יגיעו רק כשתפתחו את האפליקציה.\n\n"
                        + "במסך שייפתח: בחרו \"הכל\" ברשימה למעלה, אתרו את TeleGlatt, ובחרו \"ללא אופטימיזציה\".")
                .setPositiveButton("פתיחת ההגדרות", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    } catch (Exception e) {
                        FileLog.e("AskanUiHelper: battery settings unavailable", e);
                    }
                })
                .setNegativeButton("לא עכשיו", null)
                .show();
    }
}

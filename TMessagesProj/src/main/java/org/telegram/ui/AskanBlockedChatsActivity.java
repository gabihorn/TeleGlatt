package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.askan.AskanFilter;
import org.telegram.messenger.askan.AskanFilterLevels;
import org.telegram.messenger.askan.AskanUiHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "תוכן חסום" — the customer's own filter page, built from Telegram's own cells so it
 * reads like a Telegram settings screen:
 *
 *   הבקשות שלי      requests waiting, plus recent answers (until acted on)
 *   חסום כרגע       chats in the dialog list the filter holds back (first few + "show all")
 *   הגדרות הסינון שלי → AskanFilterSettingsActivity
 *   עזרה ותמיכה     → a sheet with the support actions
 */
public class AskanBlockedChatsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SHADOW = 1;
    private static final int TYPE_INFO   = 2;
    private static final int TYPE_ROW    = 3; // avatar row: a request or a blocked chat
    private static final int TYPE_LINK   = 4;

    private static final int LINK_SHOW_ALL = 1;
    private static final int LINK_FILTER   = 2;
    private static final int LINK_SUPPORT  = 3;
    private static final int LINK_VERIFY   = 4;

    private static final int MAX_DECIDED = 5;
    private static final int BLOCKED_PREVIEW = 5;

    private static class Item {
        final int type;
        String text;
        // links
        int linkId; String value; int icon;
        // request rows
        AskanFilter.RequestInfo request; boolean queued; String queuedUsername, queuedName, queuedKind, queuedTarget;
        // blocked rows
        TLRPC.Chat chat; TLRPC.User user;
        boolean divider;

        Item(int type) { this.type = type; }
        static Item header(String t) { Item i = new Item(TYPE_HEADER); i.text = t; return i; }
        static Item shadow() { return new Item(TYPE_SHADOW); }
        static Item info(String t) { Item i = new Item(TYPE_INFO); i.text = t; return i; }
        static Item link(int id, String t, String value, int icon) {
            Item i = new Item(TYPE_LINK); i.linkId = id; i.text = t; i.value = value; i.icon = icon; return i;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private List<AskanFilter.RequestInfo> myRequests = new ArrayList<>();
    private boolean showAllBlocked;
    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public boolean onFragmentCreate() {
        buildItems();
        loadRequests();
        return super.onFragmentCreate();
    }

    private void loadRequests() {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me == null) return;
        AskanFilter.getInstance().fetchMyRequests(me.phone,
                UserConfig.getInstance(currentAccount).getClientUserId(), requests -> {
                    myRequests = requests;
                    refresh();
                });
    }

    private void refresh() {
        buildItems();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        // back from the blocked sheet / filter settings / an opened chat
        refresh();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("תוכן חסום");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) return;
            Item item = items.get(position);
            if (item.type == TYPE_LINK) onLink(item.linkId);
            else if (item.type == TYPE_ROW) onRowClick(item);
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private void buildItems() {
        items.clear();

        // ── My requests ──
        List<Item> reqRows = new ArrayList<>();
        Map<String, AskanFilter.RequestInfo> deduped = new LinkedHashMap<>();
        for (AskanFilter.RequestInfo r : myRequests) {
            String key = (r.chatUsername != null && !r.chatUsername.isEmpty()) ? r.chatUsername
                    : (r.privacyTarget != null ? "opt_" + r.privacyTarget : "id_" + r.id);
            AskanFilter.RequestInfo existing = deduped.get(key);
            if (existing == null || r.id > existing.id) deduped.put(key, r);
        }
        android.content.SharedPreferences seen = AskanFilter.reqStatusPrefs(ApplicationLoader.applicationContext);
        int decided = 0;
        for (AskanFilter.RequestInfo r : deduped.values()) {
            boolean pending = "pending".equals(r.status);
            // Answered requests stay only until the user acts on them (open / dismiss)
            // or the server's window runs out (approved 3 days, rejected 7).
            boolean answered = ("approved".equals(r.status) || "rejected".equals(r.status) || "blocked".equals(r.status))
                    && r.id > 0 && !seen.getBoolean("done_" + r.id, false) && decided < MAX_DECIDED;
            if (!pending && !answered) continue;
            if (answered) decided++;
            Item i = new Item(TYPE_ROW);
            i.request = r;
            reqRows.add(i);
        }
        // requests saved on the device while the server was unreachable
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        for (org.json.JSONObject q : AskanFilter.getInstance().getQueuedRequests(me != null ? me.phone : null)) {
            Item i = new Item(TYPE_ROW);
            i.queued = true;
            i.queuedUsername = q.optString("chat_username");
            i.queuedName = q.optString("chat_name");
            i.queuedKind = q.optString("kind");
            i.queuedTarget = q.optString("privacy_target");
            reqRows.add(i);
        }
        if (!reqRows.isEmpty()) {
            items.add(Item.header("הבקשות שלי"));
            addRows(reqRows);
            items.add(Item.shadow());
        }

        // ── Blocked right now ──
        List<Item> blocked = buildBlockedItems();
        if (!blocked.isEmpty()) {
            items.add(Item.header("חסום כרגע"));
            boolean cut = !showAllBlocked && blocked.size() > BLOCKED_PREVIEW + 1;
            addRows(cut ? blocked.subList(0, BLOCKED_PREVIEW) : blocked);
            if (cut) {
                if (!items.isEmpty()) items.get(items.size() - 1).divider = true;
                items.add(Item.link(LINK_SHOW_ALL, "הצגת כל ה־" + blocked.size(), null, 0));
            }
            items.add(Item.info("ערוצים, קבוצות ובוטים מרשימת הצ'אטים שלך שעדיין לא אושרו. "
                    + "אפשר לבקש גישה, והצוות יבדוק."));
        } else if (reqRows.isEmpty()) {
            items.add(Item.info("אין כרגע תוכן חסום ברשימת הצ'אטים שלך."));
        }

        // ── Settings & help ──
        if (me != null && AskanFilter.getInstance().hasIdentityMismatch(me.phone)) {
            Item v = Item.link(LINK_VERIFY, "אימות חשבון טלגרם", null, R.drawable.msg_info);
            v.divider = true;
            items.add(v);
        }
        int restricted = 0;
        for (String opt : AskanFilterLevels.OPTIONS.keySet()) {
            if (AskanFilterLevels.restricts(opt, AskanFilter.getInstance().getFilterOption(opt))) restricted++;
        }
        Item f = Item.link(LINK_FILTER, "הגדרות הסינון שלי",
                restricted == 0 ? "ללא הגבלות" : restricted + " מוגבלים", R.drawable.msg_permissions);
        f.divider = true;
        items.add(f);
        items.add(Item.link(LINK_SUPPORT, "עזרה ותמיכה", null, R.drawable.msg_help));
        items.add(Item.shadow());
    }

    private void addRows(List<Item> rows) {
        for (int k = 0; k < rows.size(); k++) {
            Item i = rows.get(k);
            i.divider = k < rows.size() - 1;
            items.add(i);
        }
    }

    private List<Item> buildBlockedItems() {
        List<Item> result = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        AskanFilter filter = AskanFilter.getInstance();
        for (TLRPC.Dialog dialog : mc.getAllDialogs()) {
            long did = dialog.id;
            Item i = null;
            if (did < 0) {
                TLRPC.Chat chat = mc.getChat(-did);
                if (filter.isChatBlocked(chat, mc.getChatFull(-did))) { i = new Item(TYPE_ROW); i.chat = chat; }
            } else if (did > 0) {
                TLRPC.User user = mc.getUser(did);
                if (filter.isUserBlocked(user)) { i = new Item(TYPE_ROW); i.user = user; }
            }
            if (i != null) result.add(i);
        }
        return result;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void onLink(int id) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        switch (id) {
            case LINK_SHOW_ALL:
                showAllBlocked = true;
                refresh();
                break;
            case LINK_FILTER:
                presentFragment(new AskanFilterSettingsActivity());
                break;
            case LINK_VERIFY:
                AskanUiHelper.showIdentityDialog(ctx, currentAccount);
                break;
            case LINK_SUPPORT:
                showSupport(ctx);
                break;
        }
    }

    private void showSupport(Context ctx) {
        AskanFilter filter = AskanFilter.getInstance();
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setTitle("עזרה ותמיכה");
        b.setItems(new CharSequence[]{"העתקת פרטי מצב", "שליחת דוח לתמיכה", "רענון החיבור לשרת"},
                new int[]{R.drawable.msg_copy, R.drawable.msg_share, R.drawable.msg_retry}, (d, which) -> {
                    if (which == 0) {
                        AndroidUtilities.addToClipboard(filter.buildStatusReport(currentAccount));
                        android.widget.Toast.makeText(ctx, "פרטי המצב הועתקו", android.widget.Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(android.content.Intent.EXTRA_TEXT, filter.buildStatusReport(currentAccount));
                        try {
                            ctx.startActivity(android.content.Intent.createChooser(send, "שליחת דוח לתמיכה"));
                        } catch (Exception e) {
                            android.widget.Toast.makeText(ctx, "לא נמצאה אפליקציה לשליחה", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        confirmReconnect(ctx);
                    }
                });
        showDialog(b.create());
    }

    private void confirmReconnect(Context ctx) {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle("רענון החיבור לשרת")
                .setMessage("החיבור של המכשיר לשרת הסינון ייווצר מחדש וההרשאות יסונכרנו שוב. "
                        + "החשבון בטלגרם, הצ'אטים וההגדרות לא ישתנו.")
                .setPositiveButton("רענון", (d, w) -> AskanFilter.getInstance().resetConnection(me.phone,
                        UserConfig.getInstance(currentAccount).getClientUserId(), ok -> {
                            Context c = getParentActivity();
                            if (c == null) return;
                            android.widget.Toast.makeText(c, ok
                                    ? "החיבור חודש. ההרשאות מתעדכנות"
                                    : "לא ניתן להתחבר לשרת כרגע. נסו שוב בעוד כמה דקות",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }))
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void onRowClick(Item item) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        if (item.chat != null || item.user != null) {
            AskanUiHelper.showBlockedSheet(ctx, currentAccount, item.chat, item.user);
            return;
        }
        AskanFilter.RequestInfo r = item.request;
        if (r == null) return;
        if ("approved".equals(r.status)) {
            if (isFilterKind(r.kind)) markDone(r);
            else { markDone(r); openApproved(r.chatUsername); }
        } else if ("rejected".equals(r.status) || "blocked".equals(r.status)) {
            showRejected(ctx, r);
        }
    }

    private void onRowButton(Item item) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        if (item.chat != null || item.user != null) {
            AskanUiHelper.showBlockedSheet(ctx, currentAccount, item.chat, item.user);
            return;
        }
        AskanFilter.RequestInfo r = item.request;
        if (r == null) return;
        if ("approved".equals(r.status)) {
            markDone(r);
            if (!isFilterKind(r.kind)) openApproved(r.chatUsername);
        } else if ("blocked".equals(r.status)) {
            markDone(r);
        } else if ("rejected".equals(r.status)) {
            askAgain(ctx, r);
        }
    }

    private void showRejected(Context ctx, AskanFilter.RequestInfo r) {
        String why = AskanFilter.rejectReasonText(r.rejectReason);
        boolean canAsk = "rejected".equals(r.status);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle("הבקשה לא אושרה")
                .setMessage(why != null ? why
                        : (canAsk ? "הצוות לא אישר את הבקשה." : "הצוות בדק וחסם את זה, ולכן אי אפשר לבקש שוב."));
        if (canAsk) {
            b.setPositiveButton("בקשה חוזרת", (d, w) -> askAgain(ctx, r));
            b.setNegativeButton("סגירה", (d, w) -> markDone(r));
        } else {
            b.setPositiveButton("הבנתי", (d, w) -> markDone(r));
        }
        b.show();
    }

    private void askAgain(Context ctx, AskanFilter.RequestInfo r) {
        if (isFilterKind(r.kind)) {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (me == null) return;
            String loosest = AskanFilterLevels.BASIC.get(r.privacyTarget);
            AskanFilter.getInstance().sendFilterRequest(me.phone,
                    UserConfig.getInstance(currentAccount).getClientUserId(),
                    r.privacyTarget, loosest != null ? loosest : "show",
                    (st, until) -> {
                        if ("pending".equals(st) || "already_pending".equals(st)) {
                            markDone(r);
                            loadRequests();
                        } else {
                            AskanFilterSettingsActivity.showRequestResult(getParentActivity(), st, until);
                        }
                    });
        } else {
            String name = (r.chatName != null && !r.chatName.isEmpty()) ? r.chatName : r.chatUsername;
            String subject = AskanFilter.reqStatusPrefs(ctx).getString("subj_" + r.chatUsername, "ערוץ");
            AskanUiHelper.showAccessRequestNoteDialog(ctx, currentAccount, r.chatUsername, name, subject,
                    () -> { markDone(r); loadRequests(); });
        }
    }

    /** The user acted on an answered request — drop it from the list for good. */
    private void markDone(AskanFilter.RequestInfo r) {
        if (r.id > 0) {
            AskanFilter.reqStatusPrefs(ApplicationLoader.applicationContext).edit()
                    .putBoolean("done_" + r.id, true).apply();
        }
        refresh();
    }

    private static boolean isFilterKind(String kind) {
        return "privacy".equals(kind) || "filter".equals(kind);
    }

    /** Opens the channel/group/bot of an approved request. */
    private void openApproved(String target) {
        if (target == null || target.isEmpty()) return;
        AskanFilter.getInstance().allowLocally(target);
        String t = target.startsWith("@") ? target.substring(1) : target;
        if (t.matches("-?\\d+")) {
            // Stored by numeric id (no public username) — open it if the app knows it.
            long id = Long.parseLong(t);
            MessagesController mc = MessagesController.getInstance(currentAccount);
            android.os.Bundle args = new android.os.Bundle();
            if (id > 0 && mc.getUser(id) != null) {
                args.putLong("user_id", id);
            } else {
                long chatId = Math.abs(id);
                if (chatId > 1000000000000L) chatId -= 1000000000000L; // -100… form
                if (mc.getChat(chatId) == null) {
                    android.widget.Toast.makeText(getParentActivity(), "אושר. אפשר למצוא אותו ברשימת הצ'אטים",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                args.putLong("chat_id", chatId);
            }
            presentFragment(new ChatActivity(args));
        } else {
            MessagesController.getInstance(currentAccount).openByUserName(t, this, 0);
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        ListAdapter(Context c) { ctx = c; }

        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int pos) { return items.get(pos).type; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder h) {
            int t = h.getItemViewType();
            return t == TYPE_LINK || t == TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case TYPE_HEADER:
                    v = new HeaderCell(ctx);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SHADOW:
                    v = new ShadowSectionCell(ctx);
                    break;
                case TYPE_INFO:
                    v = new TextInfoPrivacyCell(ctx);
                    v.setBackground(Theme.getThemedDrawableByKey(ctx, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_LINK:
                    v = new TextCell(ctx);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    v = new RowCell(ctx);
                    break;
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            switch (item.type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(item.text);
                    break;
                case TYPE_LINK: {
                    TextCell c = (TextCell) holder.itemView;
                    if (item.icon != 0) {
                        c.setTextAndValueAndIcon(item.text, item.value, item.icon, item.divider);
                    } else {
                        c.setText(item.text, item.divider);
                        c.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                    }
                    if (item.icon != 0) {
                        c.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                    }
                    break;
                }
                case TYPE_ROW:
                    ((RowCell) holder.itemView).bind(item);
                    break;
            }
        }
    }

    // ── Avatar row: same proportions as Telegram's UserCell ───────────────────

    private class RowCell extends FrameLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameView, statusView, button;
        private boolean needDivider;

        // Absolute sides from the app language (as Telegram's own cells do): START/END followed the
        // device language, and the 112dp kept for the button was narrower than "בקשת גישה".
        private final boolean RTL = org.telegram.messenger.LocaleController.isRTL;
        private static final int TEXT_END = 150;

        RowCell(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(23));
            addView(avatar, LayoutHelper.createFrame(46, 46, LayoutHelper.getAbsoluteGravityStart() | Gravity.CENTER_VERTICAL, 13, 0, 13, 0));

            nameView = new TextView(ctx);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setGravity(LayoutHelper.getAbsoluteGravityStart());
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.getAbsoluteGravityStart() | Gravity.TOP, RTL ? TEXT_END : 72, 10, RTL ? 72 : TEXT_END, 0));

            statusView = new TextView(ctx);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            statusView.setSingleLine(true);
            statusView.setEllipsize(TextUtils.TruncateAt.END);
            statusView.setGravity(LayoutHelper.getAbsoluteGravityStart());
            addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.getAbsoluteGravityStart() | Gravity.TOP, RTL ? TEXT_END : 72, 33, RTL ? 72 : TEXT_END, 0));

            button = new TextView(ctx);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setTypeface(AndroidUtilities.bold());
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(14), 0, dp(14), 0);
            addView(button, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28,
                    (RTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, RTL ? 14 : 0, 0, RTL ? 0 : 14, 0));
        }

        void bind(Item item) {
            needDivider = item.divider;
            button.setOnClickListener(v -> onRowButton(item));
            if (item.chat != null || item.user != null) bindBlocked(item);
            else bindRequest(item);
            invalidate();
        }

        private void bindBlocked(Item item) {
            String name;
            String sub;
            if (item.chat != null) {
                avatarDrawable.setInfo(currentAccount, item.chat);
                avatar.setForUserOrChat(item.chat, avatarDrawable);
                name = item.chat.title;
                boolean group = ChatObject.isMegagroup(item.chat);
                sub = group ? "קבוצה" : "ערוץ";
                if (item.chat.participants_count > 0) {
                    sub += " · " + String.format(java.util.Locale.US, "%,d", item.chat.participants_count)
                            + (group ? " חברים" : " מנויים");
                }
            } else {
                avatarDrawable.setInfo(currentAccount, item.user);
                avatar.setForUserOrChat(item.user, avatarDrawable);
                name = item.user.first_name;
                sub = item.user.bot ? "בוט" : "משתמש";
            }
            nameView.setText(name != null ? name : "");
            String id = item.chat != null
                    ? (!TextUtils.isEmpty(item.chat.username) ? item.chat.username : String.valueOf(item.chat.id))
                    : (!TextUtils.isEmpty(item.user.username) ? item.user.username : String.valueOf(item.user.id));
            if (AskanUiHelper.isLocallyPending(getContext(), id)) {
                setStatus("ממתינה לאישור", Theme.key_windowBackgroundWhiteGrayText);
                setButton(null, false);
            } else {
                setStatus(sub, Theme.key_windowBackgroundWhiteGrayText);
                setButton("בקשת גישה", true);
            }
        }

        private void bindRequest(Item item) {
            String kind = item.queued ? item.queuedKind : item.request.kind;
            String username = item.queued ? item.queuedUsername : item.request.chatUsername;
            String chatName = item.queued ? item.queuedName : item.request.chatName;
            String target = item.queued ? item.queuedTarget : item.request.privacyTarget;
            boolean filterKind = isFilterKind(kind);

            String name = filterKind ? AskanFilterLevels.optionLabel(target)
                    : (!TextUtils.isEmpty(chatName) ? chatName : username);
            nameView.setText(name != null ? name : "");

            // the real avatar when the app already knows the chat, else a coloured letter
            TLObject known = !filterKind && !TextUtils.isEmpty(username) && !username.matches("-?\\d+")
                    ? MessagesController.getInstance(currentAccount).getUserOrChat(username) : null;
            if (known instanceof TLRPC.Chat) {
                avatarDrawable.setInfo(currentAccount, (TLRPC.Chat) known);
                avatar.setForUserOrChat(known, avatarDrawable);
            } else if (known instanceof TLRPC.User) {
                avatarDrawable.setInfo(currentAccount, (TLRPC.User) known);
                avatar.setForUserOrChat(known, avatarDrawable);
            } else {
                avatarDrawable.setInfo(name != null ? name.hashCode() : 0, name, null);
                avatar.setImageDrawable(avatarDrawable);
            }

            if (item.queued) {
                setStatus("תישלח כשיהיה חיבור", Theme.key_windowBackgroundWhiteGrayText);
                setButton(null, false);
                return;
            }
            AskanFilter.RequestInfo r = item.request;
            String when = r.reviewedAt > 0 ? AskanUiHelper.sentWhen(r.reviewedAt)
                    : (r.createdAt > 0 ? AskanUiHelper.sentWhen(r.createdAt) : null);
            String sentWhen = r.createdAt > 0 ? AskanUiHelper.sentWhen(r.createdAt) : null;
            switch (r.status) {
                case "pending":
                    setStatus(sentWhen != null ? "ממתינה · נשלחה " + sentWhen : "ממתינה לאישור",
                            Theme.key_windowBackgroundWhiteGrayText);
                    setButton(null, false);
                    break;
                case "approved":
                    setStatus(when != null ? "אושרה · " + when : "אושרה", Theme.key_windowBackgroundWhiteBlueText);
                    setButton(filterKind ? "הבנתי" : "פתיחה", true);
                    break;
                case "blocked":
                    setStatus("לא אושרה", Theme.key_text_RedRegular);
                    setButton("הבנתי", false);
                    break;
                default:
                    setStatus(AskanFilter.rejectReasonText(r.rejectReason) != null ? "לא אושרה · למה?" : "לא אושרה",
                            Theme.key_text_RedRegular);
                    setButton("בקשה חוזרת", false);
                    break;
            }
        }

        private void setStatus(String text, int colorKey) {
            statusView.setText(text);
            statusView.setTextColor(Theme.getColor(colorKey));
        }

        /** primary = Telegram's filled "Add" pill; otherwise a quiet text button. */
        private void setButton(String text, boolean primary) {
            if (text == null) {
                button.setVisibility(View.GONE);
                return;
            }
            button.setVisibility(View.VISIBLE);
            button.setText(text);
            if (primary) {
                button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                button.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 14));
            } else {
                button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (needDivider) {
                float start = dp(72);
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL || org.telegram.messenger.LocaleController.isRTL) {
                    canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth() - start, getMeasuredHeight() - 1, Theme.dividerPaint);
                } else {
                    canvas.drawLine(start, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
                }
            }
        }
    }
}

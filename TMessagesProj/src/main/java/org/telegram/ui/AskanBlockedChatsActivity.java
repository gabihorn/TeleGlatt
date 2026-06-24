package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.askan.AskanFilter;
import org.telegram.messenger.askan.AskanUiHelper;

import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

public class AskanBlockedChatsActivity extends BaseFragment {

    // ── Item types ────────────────────────────────────────────────────────────

    private static final int TYPE_SECTION_HEADER = 0;
    private static final int TYPE_REQUEST        = 1;
    private static final int TYPE_PRIVACY_TOGGLE = 2;
    private static final int TYPE_BLOCKED_CHAT   = 3;

    // ── Data model ────────────────────────────────────────────────────────────

    private static class ListItem {
        final int viewType;

        // Section header
        String headerTitle;

        // Request
        int    requestId;
        String chatUsername, chatName, requestStatus;
        String kind;         // "access" or "privacy"
        String privacyTarget; // "profile_photos" or "stories"; null for access
        boolean retrySent;

        // Privacy toggle
        boolean isProfilePhotos; // true = profile photos, false = stories
        boolean toggleValue;     // current show_* value

        // Blocked chat
        TLRPC.Chat chat;
        TLRPC.User user;
        boolean requestSent;

        private ListItem(int type) { this.viewType = type; }

        static ListItem header(String title) {
            ListItem i = new ListItem(TYPE_SECTION_HEADER);
            i.headerTitle = title;
            return i;
        }

        static ListItem request(int id, String username, String name, String status,
                                String kind, String privacyTarget) {
            ListItem i = new ListItem(TYPE_REQUEST);
            i.requestId = id;
            i.chatUsername = username;
            i.chatName = name;
            i.requestStatus = status;
            i.kind = kind;
            i.privacyTarget = privacyTarget;
            return i;
        }

        static ListItem privacy(boolean isPhotos, boolean value) {
            ListItem i = new ListItem(TYPE_PRIVACY_TOGGLE);
            i.isProfilePhotos = isPhotos;
            i.toggleValue = value;
            return i;
        }

        static ListItem blockedChat(TLRPC.Chat c) {
            ListItem i = new ListItem(TYPE_BLOCKED_CHAT);
            i.chat = c;
            return i;
        }

        static ListItem blockedUser(TLRPC.User u) {
            ListItem i = new ListItem(TYPE_BLOCKED_CHAT);
            i.user = u;
            return i;
        }

        String displayName() {
            if (chat != null) return chat.title != null ? chat.title : "";
            if (user != null && user.first_name != null) return user.first_name;
            if (chatName != null && !chatName.isEmpty()) return chatName;
            return chatUsername != null ? chatUsername : "";
        }

        String typeLabel() {
            if (user != null && user.bot) return "בוט";
            if (chat != null && ChatObject.isMegagroup(chat)) return "קבוצה";
            return "ערוץ";
        }

        String resolvedUsername() {
            if (chat != null)
                return (chat.username != null && !chat.username.isEmpty())
                        ? chat.username : String.valueOf(chat.id);
            if (user != null)
                return (user.username != null && !user.username.isEmpty())
                        ? user.username : String.valueOf(user.id);
            return chatUsername != null ? chatUsername : "";
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final ArrayList<ListItem> items = new ArrayList<>();
    private List<AskanFilter.RequestInfo> myRequests = new ArrayList<>();

    // ── Views ─────────────────────────────────────────────────────────────────

    private RecyclerListView listView;
    private ListAdapter adapter;

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public boolean onFragmentCreate() {
        buildItems();

        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me != null) {
            AskanFilter.getInstance().fetchMyRequests(
                    me.phone,
                    UserConfig.getInstance(currentAccount).getClientUserId(),
                    requests -> {
                        myRequests = requests;
                        buildItems();
                        if (adapter != null) adapter.notifyDataSetChanged();
                    });
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("אזור שליטה");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(
                context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        frame.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    // ── Build items ───────────────────────────────────────────────────────────

    private void buildItems() {
        items.clear();

        // 1. Requests section — only pending requests (rejected are not shown here).
        // Dedup by chatUsername (keep highest id = most recent).
        if (!myRequests.isEmpty()) {
            Map<String, AskanFilter.RequestInfo> deduped = new LinkedHashMap<>();
            for (AskanFilter.RequestInfo r : myRequests) {
                String key = (r.chatUsername != null && !r.chatUsername.isEmpty())
                        ? r.chatUsername : ("id_" + r.id);
                AskanFilter.RequestInfo existing = deduped.get(key);
                if (existing == null || r.id > existing.id) {
                    deduped.put(key, r);
                }
            }
            List<AskanFilter.RequestInfo> pending = new ArrayList<>();
            for (AskanFilter.RequestInfo r : deduped.values()) {
                if ("pending".equals(r.status)) pending.add(r);
            }
            if (!pending.isEmpty()) {
                items.add(ListItem.header("בקשות"));
                for (AskanFilter.RequestInfo r : pending) {
                    items.add(ListItem.request(r.id, r.chatUsername, r.chatName, r.status,
                            r.kind, r.privacyTarget));
                }
            }
        }

        // 2. Privacy section (always visible)
        items.add(ListItem.header("הגדרות פרטיות"));
        items.add(ListItem.privacy(true,  AskanFilter.getInstance().shouldShowProfilePhotos()));
        items.add(ListItem.privacy(false, AskanFilter.getInstance().shouldShowStories()));

        // 3. Blocked chats section (hidden when empty)
        List<ListItem> blocked = buildBlockedItems();
        if (!blocked.isEmpty()) {
            items.add(ListItem.header("תוכן חסום"));
            items.addAll(blocked);
        }
    }

    private List<ListItem> buildBlockedItems() {
        List<ListItem> result = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        AskanFilter filter = AskanFilter.getInstance();
        for (TLRPC.Dialog dialog : dialogs) {
            long did = dialog.id;
            if (did < 0) {
                TLRPC.Chat chat = mc.getChat(-did);
                TLRPC.ChatFull full = mc.getChatFull(-did);
                if (filter.isChatBlocked(chat, full)) result.add(ListItem.blockedChat(chat));
            } else if (did > 0) {
                TLRPC.User user = mc.getUser(did);
                if (filter.isUserBlocked(user)) result.add(ListItem.blockedUser(user));
            }
        }
        return result;
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context ctx;

        ListAdapter(Context c) { ctx = c; }

        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int pos) { return items.get(pos).viewType; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder h) { return false; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View cell;
            switch (viewType) {
                case TYPE_SECTION_HEADER: cell = new SectionHeaderCell(ctx); break;
                case TYPE_REQUEST:        cell = new RequestCell(ctx);       break;
                case TYPE_PRIVACY_TOGGLE: cell = new PrivacyToggleCell(ctx); break;
                default:                  cell = new BlockedChatCell(ctx);   break;
            }
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ListItem item = items.get(position);
            switch (item.viewType) {
                case TYPE_SECTION_HEADER:
                    ((SectionHeaderCell) holder.itemView).bind(item.headerTitle);
                    break;
                case TYPE_REQUEST:
                    ((RequestCell) holder.itemView).bind(item, position);
                    break;
                case TYPE_PRIVACY_TOGGLE:
                    ((PrivacyToggleCell) holder.itemView).bind(item, position);
                    break;
                case TYPE_BLOCKED_CHAT:
                    boolean nextIsChat = (position + 1 < items.size())
                            && items.get(position + 1).viewType == TYPE_BLOCKED_CHAT;
                    ((BlockedChatCell) holder.itemView).bind(item, position, nextIsChat);
                    break;
            }
        }
    }

    // ── SectionHeaderCell ─────────────────────────────────────────────────────

    private static class SectionHeaderCell extends TextView {
        SectionHeaderCell(Context ctx) {
            super(ctx);
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            setTextSize(12);
            setTypeface(Typeface.DEFAULT_BOLD);
            setAllCaps(true);
            setPadding(dp(16), 0, dp(16), 0);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }

        void bind(String title) { setText(title); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY));
        }
    }

    // ── RequestCell ───────────────────────────────────────────────────────────

    private class RequestCell extends FrameLayout {
        private final TextView nameView;
        private final TextView statusView;
        private final TextView retryButton;
        private final View    divider;

        RequestCell(Context ctx) {
            super(ctx);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            nameView = new TextView(ctx);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(15);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 16, 12, 120, 0));

            statusView = new TextView(ctx);
            statusView.setTextSize(12);
            addView(statusView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 16, 0, 120, 12));

            retryButton = new TextView(ctx);
            retryButton.setTextSize(13);
            retryButton.setTypeface(Typeface.DEFAULT_BOLD);
            retryButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            retryButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(retryButton, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            divider = new View(ctx);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM | Gravity.START, 16, 0, 0, 0));
        }

        void bind(ListItem item, int position) {
            boolean isPrivacy = "privacy".equals(item.kind);

            // Display name
            if (isPrivacy) {
                nameView.setText("profile_photos".equals(item.privacyTarget)
                        ? "תמונות פרופיל" : "סטוריז");
            } else {
                String name = (item.chatName != null && !item.chatName.isEmpty())
                        ? item.chatName : item.chatUsername;
                nameView.setText(name);
            }

            boolean isPending = "pending".equals(item.requestStatus);
            if (isPending) {
                statusView.setText("ממתין לאישור ⏳");
                statusView.setTextColor(Theme.getColor(Theme.key_color_orange));
                retryButton.setVisibility(View.GONE);
            } else {
                statusView.setText("נדחה ❌");
                statusView.setTextColor(Theme.getColor(Theme.key_text_RedBold));

                retryButton.setVisibility(View.VISIBLE);
                if (item.retrySent) {
                    retryButton.setText("נשלח ✓");
                    retryButton.setAlpha(0.5f);
                    retryButton.setEnabled(false);
                    retryButton.setOnClickListener(null);
                } else {
                    retryButton.setText("בקש שוב");
                    retryButton.setAlpha(1f);
                    retryButton.setEnabled(true);
                    retryButton.setOnClickListener(v -> {
                        if (isPrivacy) {
                            item.retrySent = true;
                            retryButton.setText("נשלח ✓");
                            retryButton.setAlpha(0.5f);
                            retryButton.setEnabled(false);
                            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
                            if (me == null) return;
                            AskanFilter.getInstance().sendPrivacyRestoreRequest(
                                    me.phone,
                                    UserConfig.getInstance(currentAccount).getClientUserId(),
                                    item.privacyTarget,
                                    () -> { if (adapter != null) adapter.notifyItemChanged(position); },
                                    () -> {
                                        item.retrySent = false;
                                        if (adapter != null) adapter.notifyItemChanged(position);
                                    });
                        } else {
                            Context parentCtx = getParentActivity();
                            if (parentCtx == null) return;
                            String chatName2 = (item.chatName != null && !item.chatName.isEmpty())
                                    ? item.chatName : item.chatUsername;
                            AskanUiHelper.showAccessRequestNoteDialog(
                                    parentCtx, currentAccount,
                                    item.chatUsername, chatName2, "ערוץ",
                                    () -> {
                                        item.retrySent = true;
                                        if (adapter != null) adapter.notifyItemChanged(position);
                                    });
                        }
                    });
                }
            }

            boolean nextIsRequest = (position + 1 < items.size())
                    && items.get(position + 1).viewType == TYPE_REQUEST;
            divider.setVisibility(nextIsRequest ? View.VISIBLE : View.INVISIBLE);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }

    // ── PrivacyToggleCell ─────────────────────────────────────────────────────

    private class PrivacyToggleCell extends FrameLayout {
        private final TextView labelView;
        private final TextView hintView;
        private final Switch   toggle;
        private final TextView requestButton;
        private final View     divider;
        private CompoundButton.OnCheckedChangeListener activeListener;

        PrivacyToggleCell(Context ctx) {
            super(ctx);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            labelView = new TextView(ctx);
            labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            labelView.setTextSize(15);
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            addView(labelView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 16, 14, 140, 0));

            hintView = new TextView(ctx);
            hintView.setTextSize(12);
            addView(hintView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 16, 0, 140, 14));

            toggle = new Switch(ctx);
            addView(toggle, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            requestButton = new TextView(ctx);
            requestButton.setTextSize(13);
            requestButton.setTypeface(Typeface.DEFAULT_BOLD);
            requestButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            requestButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(requestButton, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            divider = new View(ctx);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM | Gravity.START, 16, 0, 0, 0));
        }

        void bind(ListItem item, int position) {
            labelView.setText(item.isProfilePhotos ? "תמונות פרופיל" : "סטוריז");

            // Detach listener before updating checked state
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(item.toggleValue);

            if (item.toggleValue) {
                // Currently visible — user can self-hide
                toggle.setVisibility(View.VISIBLE);
                requestButton.setVisibility(View.GONE);
                toggle.setEnabled(true);
                hintView.setText("מוצג");
                hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));

                activeListener = (btn, isChecked) -> {
                    if (isChecked) return;
                    // Revert toggle immediately while awaiting confirmation
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(true);
                    toggle.setOnCheckedChangeListener(activeListener);

                    Context parentCtx = getParentActivity();
                    if (parentCtx == null) return;
                    new AlertDialog.Builder(parentCtx)
                            .setTitle("אזהרה")
                            .setMessage("לאחר ההסתרה לא תוכל לבטל אלא באישור המנהל. להמשיך?")
                            .setPositiveButton("המשך", (dialog, which) -> {
                                toggle.setEnabled(false);
                                hintView.setText("מסתיר...");
                                hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));

                                TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
                                if (me == null) {
                                    revertToggle(item, toggle);
                                    return;
                                }
                                AskanFilter.getInstance().selfHidePrivacy(
                                        me.phone,
                                        UserConfig.getInstance(currentAccount).getClientUserId(),
                                        item.isProfilePhotos,
                                        !item.isProfilePhotos,
                                        () -> {
                                            item.toggleValue = false;
                                            if (adapter != null) adapter.notifyItemChanged(position);
                                        },
                                        () -> {
                                            revertToggle(item, toggle);
                                            Context ctx2 = getParentActivity();
                                            if (ctx2 != null) {
                                                new AlertDialog.Builder(ctx2)
                                                        .setMessage("שגיאה בעדכון ההגדרה. נסה שוב.")
                                                        .setPositiveButton("סגור", null)
                                                        .show();
                                            }
                                        });
                            })
                            .setNegativeButton("ביטול", null)
                            .show();
                };
                toggle.setOnCheckedChangeListener(activeListener);

            } else {
                // Hidden — check if there's already a pending privacy restore request (server truth)
                String targetStr = item.isProfilePhotos ? "profile_photos" : "stories";
                boolean hasPending = false;
                for (AskanFilter.RequestInfo r : myRequests) {
                    if ("privacy".equals(r.kind) && targetStr.equals(r.privacyTarget)
                            && "pending".equals(r.status)) {
                        hasPending = true;
                        break;
                    }
                }

                toggle.setVisibility(View.GONE);
                activeListener = null;

                if (hasPending) {
                    // Waiting for admin approval — show state, no action button
                    requestButton.setVisibility(View.GONE);
                    hintView.setText("ממתין לאישור ⏳");
                    hintView.setTextColor(Theme.getColor(Theme.key_color_orange));
                } else {
                    // Hidden, no pending request — offer "בקש להציג"
                    requestButton.setVisibility(View.VISIBLE);
                    requestButton.setText("בקש להציג");
                    requestButton.setAlpha(1f);
                    requestButton.setEnabled(true);
                    hintView.setText("מוסתר");
                    hintView.setTextColor(Theme.getColor(Theme.key_color_orange));

                    requestButton.setOnClickListener(v -> {
                        requestButton.setEnabled(false);
                        requestButton.setText("שולח...");
                        requestButton.setAlpha(0.6f);

                        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
                        if (me == null) {
                            requestButton.setEnabled(true);
                            requestButton.setText("בקש להציג");
                            requestButton.setAlpha(1f);
                            return;
                        }
                        AskanFilter.getInstance().sendPrivacyRestoreRequest(
                                me.phone,
                                UserConfig.getInstance(currentAccount).getClientUserId(),
                                targetStr,
                                () -> {
                                    // Optimistically add pending entry so UI reflects state immediately
                                    List<AskanFilter.RequestInfo> updated = new ArrayList<>(myRequests);
                                    updated.add(new AskanFilter.RequestInfo(
                                            0, "", "", "pending", "privacy", targetStr));
                                    myRequests = updated;
                                    buildItems();
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                },
                                () -> {
                                    requestButton.setEnabled(true);
                                    requestButton.setText("בקש להציג");
                                    requestButton.setAlpha(1f);
                                    Context parentCtx = getParentActivity();
                                    if (parentCtx != null) {
                                        new AlertDialog.Builder(parentCtx)
                                                .setMessage("שגיאה בשליחת הבקשה. נסה שוב.")
                                                .setPositiveButton("סגור", null)
                                                .show();
                                    }
                                });
                    });
                }
            }

            boolean nextIsPrivacy = (position + 1 < items.size())
                    && items.get(position + 1).viewType == TYPE_PRIVACY_TOGGLE;
            divider.setVisibility(nextIsPrivacy ? View.VISIBLE : View.INVISIBLE);
        }

        private void revertToggle(ListItem item, Switch sw) {
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(true);
            sw.setEnabled(true);
            hintView.setText("מוצג");
            hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            if (activeListener != null) sw.setOnCheckedChangeListener(activeListener);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
        }
    }

    // ── BlockedChatCell ───────────────────────────────────────────────────────

    private class BlockedChatCell extends FrameLayout {
        private final BackupImageView avatarView;
        private final AvatarDrawable  avatarDrawable;
        private final TextView        nameView;
        private final TextView        typeView;
        private final TextView        requestButton;
        private final View            divider;

        BlockedChatCell(Context ctx) {
            super(ctx);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(ctx);
            avatarView.setRoundRadius(dp(20));
            addView(avatarView, LayoutHelper.createFrame(40, 40,
                    Gravity.START | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            nameView = new TextView(ctx);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(15);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 68, 10, 100, 0));

            typeView = new TextView(ctx);
            typeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            typeView.setTextSize(12);
            addView(typeView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 68, 0, 0, 10));

            requestButton = new TextView(ctx);
            requestButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            requestButton.setTextSize(13);
            requestButton.setTypeface(Typeface.DEFAULT_BOLD);
            requestButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(requestButton, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            divider = new View(ctx);
            addView(divider, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, 1,
                    Gravity.BOTTOM | Gravity.START, 68, 0, 0, 0));
        }

        void bind(ListItem item, int position, boolean showDivider) {
            if (item.chat != null) {
                avatarDrawable.setInfo(currentAccount, item.chat);
                avatarView.setForUserOrChat(item.chat, avatarDrawable);
            } else if (item.user != null) {
                avatarDrawable.setInfo(currentAccount, item.user);
                avatarView.setForUserOrChat(item.user, avatarDrawable);
            }

            nameView.setText(item.displayName());
            typeView.setText(item.typeLabel());
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            divider.setVisibility(showDivider ? View.VISIBLE : View.INVISIBLE);

            if (item.requestSent) {
                requestButton.setText("נשלח ✓");
                requestButton.setAlpha(0.5f);
                requestButton.setEnabled(false);
                requestButton.setOnClickListener(null);
            } else {
                requestButton.setText("בקש גישה");
                requestButton.setAlpha(1f);
                requestButton.setEnabled(true);
                requestButton.setOnClickListener(v -> {
                    Context parentCtx = getParentActivity();
                    if (parentCtx == null) return;
                    AskanUiHelper.showAccessRequestNoteDialog(
                            parentCtx, currentAccount,
                            item.resolvedUsername(), item.displayName(), item.typeLabel(),
                            () -> {
                                item.requestSent = true;
                                if (adapter != null) adapter.notifyItemChanged(position);
                            });
                });
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }
}

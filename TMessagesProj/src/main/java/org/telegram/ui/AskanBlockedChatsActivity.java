package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

public class AskanBlockedChatsActivity extends BaseFragment {

    private static final int TYPE_SECTION_HEADER = 0;
    private static final int TYPE_REQUEST        = 1;
    private static final int TYPE_PRIVACY_TOGGLE = 2;
    private static final int TYPE_BLOCKED_CHAT   = 3;

    private static class ListItem {
        final int viewType;
        String headerTitle;
        int    requestId;
        String chatUsername, chatName, requestStatus;
        String kind;
        String privacyTarget;
        boolean retrySent;
        boolean isProfilePhotos;
        boolean toggleValue;
        TLRPC.Chat chat;
        TLRPC.User user;
        boolean requestSent;

        private ListItem(int type) { this.viewType = type; }

        static ListItem header(String title) {
            ListItem i = new ListItem(TYPE_SECTION_HEADER); i.headerTitle = title; return i;
        }
        static ListItem request(int id, String username, String name, String status,
                                String kind, String privacyTarget) {
            ListItem i = new ListItem(TYPE_REQUEST);
            i.requestId = id; i.chatUsername = username; i.chatName = name;
            i.requestStatus = status; i.kind = kind; i.privacyTarget = privacyTarget;
            return i;
        }
        static ListItem privacy(boolean isPhotos, boolean value) {
            ListItem i = new ListItem(TYPE_PRIVACY_TOGGLE);
            i.isProfilePhotos = isPhotos; i.toggleValue = value; return i;
        }
        static ListItem blockedChat(TLRPC.Chat c) {
            ListItem i = new ListItem(TYPE_BLOCKED_CHAT); i.chat = c; return i;
        }
        static ListItem blockedUser(TLRPC.User u) {
            ListItem i = new ListItem(TYPE_BLOCKED_CHAT); i.user = u; return i;
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
            if (chat != null) return (chat.username != null && !chat.username.isEmpty()) ? chat.username : String.valueOf(chat.id);
            if (user != null) return (user.username != null && !user.username.isEmpty()) ? user.username : String.valueOf(user.id);
            return chatUsername != null ? chatUsername : "";
        }
        String initials() {
            String name = displayName();
            if (name.isEmpty()) return "?";
            String[] parts = name.trim().split("\\s+");
            if (parts.length >= 2) return String.valueOf(parts[0].charAt(0)).toUpperCase() + String.valueOf(parts[1].charAt(0)).toUpperCase();
            return name.length() >= 2 ? name.substring(0, 2).toUpperCase() : name.substring(0, 1).toUpperCase();
        }
    }

    private final ArrayList<ListItem> items = new ArrayList<>();
    private List<AskanFilter.RequestInfo> myRequests = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView statPendingNum;
    private TextView statBlockedNum;

    // ── Avatar color palette (matches Telegram's approach) ────────────────────
    private static final int[] AVATAR_COLORS = {
        0xFF3B5BDB, 0xFF2F9E44, 0xFFC92A2A, 0xFF0C8599,
        0xFF7048E8, 0xFFD6336C, 0xFF2B8A3E, 0xFF1864AB
    };

    private static int avatarColor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        return AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
    }

    @Override
    public boolean onFragmentCreate() {
        buildItems();
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me != null) {
            AskanFilter.getInstance().fetchMyRequests(me.phone,
                    UserConfig.getInstance(currentAccount).getClientUserId(), requests -> {
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
        actionBar.setBackgroundColor(0xFF0d0d14);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setItemsBackgroundColor(0x33FFFFFF, false);
        actionBar.setTitle("");
        actionBar.setOccupyStatusBar(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        // ── Hero ──────────────────────────────────────────────────────────────
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackgroundColor(0xFF0d0d14);
        hero.setPadding(dp(18), dp(8), dp(18), dp(20));

        // Page label
        TextView pageLabel = new TextView(context);
        pageLabel.setText("אזור שליטה");
        pageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        pageLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setPadding(0, 0, 0, dp(16));
        hero.addView(pageLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Stats row
        LinearLayout statsRow = new LinearLayout(context);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER);

        statPendingNum = new TextView(context);
        statBlockedNum = new TextView(context);

        statsRow.addView(makeStatCard(context, statPendingNum, "בקשות\nממתינות לטיפול", 0xFFF59E0B), makeStatLp());
        View statGap = new View(context);
        statsRow.addView(statGap, new LinearLayout.LayoutParams(dp(10), 1));
        statsRow.addView(makeStatCard(context, statBlockedNum, "פריטים\nחסומים", 0xFFEF4444), makeStatLp());

        hero.addView(statsRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── List (added before hero so hero draws on top when scrolling) ─────
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView.setPadding(0, dp(14), 0, dp(20));
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.addItemDecoration(new CardGroupDecoration());
        root.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Hero added after list → draws on top; elevation keeps it above scroll
        root.addView(hero, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        hero.setElevation(dp(4));

        // Push list content below hero once hero height is known
        root.post(() -> {
            int heroH = hero.getHeight();
            listView.setPadding(0, heroH + dp(8), 0, dp(20));
        });

        updateStats();
        return fragmentView;
    }

    private LinearLayout.LayoutParams makeStatLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private FrameLayout makeStatCard(Context ctx, TextView numView, String label, int dotColor) {
        FrameLayout card = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x0FFFFFFF);
        bg.setStroke(1, 0x1AFFFFFF);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.VERTICAL);

        // Number + dot row
        LinearLayout numRow = new LinearLayout(ctx);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.CENTER_VERTICAL);

        numView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        numView.setTypeface(Typeface.DEFAULT_BOLD);
        numView.setTextColor(Color.WHITE);
        numView.setText("0");
        numRow.addView(numView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View dot = new View(ctx);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(dotColor);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.setMarginStart(dp(6));
        numRow.addView(dot, dotLp);

        inner.addView(numRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        labelView.setTextColor(0x66FFFFFF);
        labelView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(5);
        inner.addView(labelView, labelLp);

        card.addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private void updateStats() {
        if (statPendingNum == null || statBlockedNum == null) return;
        long pending = myRequests.stream().filter(r -> "pending".equals(r.status)).count();
        long blocked = buildBlockedItems().size();
        statPendingNum.setText(String.valueOf(pending));
        statBlockedNum.setText(String.valueOf(blocked));
    }

    private void buildItems() {
        items.clear();
        if (!myRequests.isEmpty()) {
            Map<String, AskanFilter.RequestInfo> deduped = new LinkedHashMap<>();
            for (AskanFilter.RequestInfo r : myRequests) {
                String key = (r.chatUsername != null && !r.chatUsername.isEmpty()) ? r.chatUsername : ("id_" + r.id);
                AskanFilter.RequestInfo existing = deduped.get(key);
                if (existing == null || r.id > existing.id) deduped.put(key, r);
            }
            List<AskanFilter.RequestInfo> pending = new ArrayList<>();
            for (AskanFilter.RequestInfo r : deduped.values()) { if ("pending".equals(r.status)) pending.add(r); }
            if (!pending.isEmpty()) {
                items.add(ListItem.header("בקשות"));
                for (AskanFilter.RequestInfo r : pending)
                    items.add(ListItem.request(r.id, r.chatUsername, r.chatName, r.status, r.kind, r.privacyTarget));
            }
        }
        items.add(ListItem.header("פרטיות"));
        items.add(ListItem.privacy(true,  AskanFilter.getInstance().shouldShowProfilePhotos()));
        items.add(ListItem.privacy(false, AskanFilter.getInstance().shouldShowStories()));
        List<ListItem> blocked = buildBlockedItems();
        if (!blocked.isEmpty()) {
            items.add(ListItem.header("תוכן חסום"));
            items.addAll(blocked);
        }
        updateStats();
    }

    private List<ListItem> buildBlockedItems() {
        List<ListItem> result = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        AskanFilter filter = AskanFilter.getInstance();
        for (TLRPC.Dialog dialog : mc.getAllDialogs()) {
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
                case TYPE_REQUEST:        cell = new RequestCell(ctx);        break;
                case TYPE_PRIVACY_TOGGLE: cell = new PrivacyToggleCell(ctx);  break;
                default:                  cell = new BlockedChatCell(ctx);    break;
            }
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ListItem item = items.get(position);
            switch (item.viewType) {
                case TYPE_SECTION_HEADER: ((SectionHeaderCell) holder.itemView).bind(item.headerTitle); break;
                case TYPE_REQUEST:        ((RequestCell) holder.itemView).bind(item, position); break;
                case TYPE_PRIVACY_TOGGLE: ((PrivacyToggleCell) holder.itemView).bind(item, position); break;
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            setTypeface(Typeface.DEFAULT_BOLD);
            setAllCaps(true);
            setLetterSpacing(0.08f);
            setPadding(dp(18), dp(16), dp(18), dp(6));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        }
        void bind(String title) { setText(title); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(38), MeasureSpec.EXACTLY));
        }
    }

    // ── Shared card background ─────────────────────────────────────────────────

    private static android.graphics.drawable.GradientDrawable cardBg(Context ctx) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        d.setCornerRadius(dp(18));
        return d;
    }

    // ── Shared avatar (circle with initials fallback) ─────────────────────────

    private static TextView makeInitialsAvatar(Context ctx, String name) {
        TextView av = new TextView(ctx);
        av.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        av.setTextColor(Color.WHITE);
        av.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
        avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avBg.setColor(avatarColor(name));
        av.setBackground(avBg);
        av.setText(initialsOf(name));
        return av;
    }

    private static String initialsOf(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) return (parts[0].charAt(0) + "" + parts[1].charAt(0)).toUpperCase();
        return (name.length() >= 2 ? name.substring(0, 2) : name.substring(0, 1)).toUpperCase();
    }

    // ── Shared action button — solid accent fill + white text ──────────────────
    // Filled (not outline) so the label is high-contrast and readable in both
    // light and dark mode (outline-on-dark was nearly invisible).
    private static TextView makeOutlineBtn(Context ctx, String text) {
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(7), dp(14), dp(7));
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(accent);
        btnBg.setCornerRadius(dp(10));
        btn.setBackground(btnBg);
        return btn;
    }

    // ── RequestCell ───────────────────────────────────────────────────────────

    private class RequestCell extends FrameLayout {
        private final BackupImageView avatarImg;
        private final AvatarDrawable  avatarDrawable;
        private final TextView        initialsView;
        private final TextView        nameView;
        private final TextView        statusView;
        private final TextView        retryButton;
        private final View            divider;

        RequestCell(Context ctx) {
            super(ctx);
            setBackground(cardBg(ctx));

            avatarDrawable = new AvatarDrawable();
            avatarImg = new BackupImageView(ctx);
            avatarImg.setRoundRadius(dp(21));
            addView(avatarImg, LayoutHelper.createFrame(42, 42, Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            initialsView = makeInitialsAvatar(ctx, "");
            addView(initialsView, LayoutHelper.createFrame(42, 42, Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            nameView = new TextView(ctx);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 68, 11, 110, 0));

            statusView = new TextView(ctx);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 68, 0, 110, 11));

            retryButton = makeOutlineBtn(ctx, "בקש שוב");
            addView(retryButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            divider = new View(ctx);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.5f,
                    Gravity.BOTTOM | Gravity.START, 68, 0, 0, 0));
        }

        void bind(ListItem item, int position) {
            boolean isPrivacy = "privacy".equals(item.kind);
            String name;
            if (isPrivacy) {
                name = "profile_photos".equals(item.privacyTarget) ? "תמונות פרופיל" : "סטוריז";
            } else {
                name = (item.chatName != null && !item.chatName.isEmpty()) ? item.chatName : item.chatUsername;
            }
            nameView.setText(name != null ? name : "");

            avatarImg.setVisibility(View.GONE);
            initialsView.setVisibility(View.VISIBLE);
            android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
            avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (isPrivacy) {
                // Distinct icon for privacy items — purple background with symbol
                avBg.setColor(0xFF7048E8);
                initialsView.setText("profile_photos".equals(item.privacyTarget) ? "📷" : "▶");
                initialsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            } else {
                String displayName = name != null ? name : "";
                avBg.setColor(avatarColor(displayName));
                initialsView.setText(initialsOf(displayName));
                initialsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            }
            initialsView.setBackground(avBg);

            boolean isPending = "pending".equals(item.requestStatus);
            if (isPending) {
                // Pill badge
                statusView.setText("ממתינה לטיפול ⏳");
                statusView.setTextColor(Theme.getColor(Theme.key_color_orange));
                statusView.setTypeface(Typeface.DEFAULT_BOLD);
                retryButton.setVisibility(View.GONE);
            } else {
                statusView.setText("לא אושרה");
                statusView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                statusView.setTypeface(Typeface.DEFAULT_BOLD);
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
                            AskanFilter.getInstance().sendPrivacyRestoreRequest(me.phone,
                                    UserConfig.getInstance(currentAccount).getClientUserId(),
                                    item.privacyTarget,
                                    () -> { if (adapter != null) adapter.notifyItemChanged(position); },
                                    () -> { item.retrySent = false; if (adapter != null) adapter.notifyItemChanged(position); });
                        } else {
                            Context parentCtx = getParentActivity();
                            if (parentCtx == null) return;
                            String chatName2 = (item.chatName != null && !item.chatName.isEmpty()) ? item.chatName : item.chatUsername;
                            String savedSubject = parentCtx.getSharedPreferences("askan_req_statuses", Context.MODE_PRIVATE)
                                    .getString("subj_" + item.chatUsername, "ערוץ");
                            AskanUiHelper.showAccessRequestNoteDialog(parentCtx, currentAccount,
                                    item.chatUsername, chatName2, savedSubject,
                                    () -> { item.retrySent = true; if (adapter != null) adapter.notifyItemChanged(position); });
                        }
                    });
                }
            }

            boolean nextIsRequest = (position + 1 < items.size())
                    && items.get(position + 1).viewType == TYPE_REQUEST;
            divider.setVisibility(nextIsRequest ? View.VISIBLE : View.GONE);
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
            setBackground(cardBg(ctx));

            labelView = new TextView(ctx);
            labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            addView(labelView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 16, 14, 140, 0));

            hintView = new TextView(ctx);
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hintView.setTypeface(Typeface.DEFAULT_BOLD);
            addView(hintView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 16, 0, 140, 14));

            toggle = new Switch(ctx);
            addView(toggle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            requestButton = makeOutlineBtn(ctx, "בקש להציג");
            addView(requestButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            divider = new View(ctx);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.5f,
                    Gravity.BOTTOM | Gravity.START, 16, 0, 0, 0));
        }

        void bind(ListItem item, int position) {
            labelView.setText(item.isProfilePhotos ? "תמונות פרופיל" : "סטוריז");
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(item.toggleValue);

            if (item.toggleValue) {
                toggle.setVisibility(View.VISIBLE);
                requestButton.setVisibility(View.GONE);
                toggle.setEnabled(true);
                hintView.setText("מוצג");
                hintView.setTextColor(Theme.getColor(Theme.key_color_green));

                activeListener = (btn, isChecked) -> {
                    if (isChecked) return;
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
                                hintView.setTextColor(Theme.getColor(Theme.key_color_orange));
                                TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
                                if (me == null) { revertToggle(item); return; }
                                AskanFilter.getInstance().selfHidePrivacy(me.phone,
                                        UserConfig.getInstance(currentAccount).getClientUserId(),
                                        item.isProfilePhotos, !item.isProfilePhotos,
                                        () -> { item.toggleValue = false; if (adapter != null) adapter.notifyItemChanged(position); },
                                        () -> {
                                            revertToggle(item);
                                            Context ctx2 = getParentActivity();
                                            if (ctx2 != null) new AlertDialog.Builder(ctx2).setMessage("שגיאה בעדכון ההגדרה. נסה שוב.").setPositiveButton("סגור", null).show();
                                        });
                            })
                            .setNegativeButton("ביטול", null).show();
                };
                toggle.setOnCheckedChangeListener(activeListener);
            } else {
                String targetStr = item.isProfilePhotos ? "profile_photos" : "stories";
                boolean hasPending = false;
                for (AskanFilter.RequestInfo r : myRequests) {
                    if ("privacy".equals(r.kind) && targetStr.equals(r.privacyTarget) && "pending".equals(r.status)) {
                        hasPending = true; break;
                    }
                }
                toggle.setVisibility(View.GONE);
                activeListener = null;
                if (hasPending) {
                    requestButton.setVisibility(View.GONE);
                    hintView.setText("ממתין לאישור ⏳");
                    hintView.setTextColor(Theme.getColor(Theme.key_color_orange));
                } else {
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
                        if (me == null) { requestButton.setEnabled(true); requestButton.setText("בקש להציג"); requestButton.setAlpha(1f); return; }
                        AskanFilter.getInstance().sendPrivacyRestoreRequest(me.phone,
                                UserConfig.getInstance(currentAccount).getClientUserId(), targetStr,
                                () -> {
                                    List<AskanFilter.RequestInfo> updated = new ArrayList<>(myRequests);
                                    updated.add(new AskanFilter.RequestInfo(0, "", "", "pending", "privacy", targetStr));
                                    myRequests = updated;
                                    buildItems();
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                },
                                () -> {
                                    requestButton.setEnabled(true);
                                    requestButton.setText("בקש להציג");
                                    requestButton.setAlpha(1f);
                                    Context parentCtx = getParentActivity();
                                    if (parentCtx != null) new AlertDialog.Builder(parentCtx).setMessage("שגיאה בשליחת הבקשה. נסה שוב.").setPositiveButton("סגור", null).show();
                                });
                    });
                }
            }
            boolean nextIsPrivacy = (position + 1 < items.size())
                    && items.get(position + 1).viewType == TYPE_PRIVACY_TOGGLE;
            divider.setVisibility(nextIsPrivacy ? View.VISIBLE : View.GONE);
        }

        private void revertToggle(ListItem item) {
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(true);
            toggle.setEnabled(true);
            hintView.setText("מוצג");
            hintView.setTextColor(Theme.getColor(Theme.key_color_green));
            if (activeListener != null) toggle.setOnCheckedChangeListener(activeListener);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }

    // ── BlockedChatCell ───────────────────────────────────────────────────────

    private class BlockedChatCell extends FrameLayout {
        private final BackupImageView avatarImg;
        private final AvatarDrawable  avatarDrawable;
        private final TextView        nameView;
        private final TextView        typeView;
        private final TextView        requestButton;
        private final View            divider;

        BlockedChatCell(Context ctx) {
            super(ctx);
            setBackground(cardBg(ctx));

            avatarDrawable = new AvatarDrawable();
            avatarImg = new BackupImageView(ctx);
            avatarImg.setRoundRadius(dp(21));
            addView(avatarImg, LayoutHelper.createFrame(42, 42,
                    Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            nameView = new TextView(ctx);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 68, 11, 110, 0));

            typeView = new TextView(ctx);
            typeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            typeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            addView(typeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 68, 0, 0, 11));

            requestButton = makeOutlineBtn(ctx, "בקשת גישה");
            addView(requestButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            divider = new View(ctx);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.5f,
                    Gravity.BOTTOM | Gravity.START, 68, 0, 0, 0));
        }

        void bind(ListItem item, int position, boolean showDivider) {
            if (item.chat != null) {
                avatarDrawable.setInfo(currentAccount, item.chat);
                avatarImg.setForUserOrChat(item.chat, avatarDrawable);
            } else if (item.user != null) {
                avatarDrawable.setInfo(currentAccount, item.user);
                avatarImg.setForUserOrChat(item.user, avatarDrawable);
            }

            nameView.setText(item.displayName());
            typeView.setText(item.typeLabel() + " · דורש אישור מראש");
            divider.setVisibility(showDivider ? View.VISIBLE : View.GONE);

            boolean pending = item.requestSent
                    || AskanUiHelper.isLocallyPending(getContext(), item.resolvedUsername());
            if (pending) {
                requestButton.setText("ממתינה ⏳");
                requestButton.setAlpha(0.5f);
                requestButton.setEnabled(false);
                requestButton.setOnClickListener(null);
            } else {
                requestButton.setText("בקשת גישה");
                requestButton.setAlpha(1f);
                requestButton.setEnabled(true);
                requestButton.setOnClickListener(v -> {
                    Context parentCtx = getParentActivity();
                    if (parentCtx == null) return;
                    AskanUiHelper.showAccessRequestNoteDialog(parentCtx, currentAccount,
                            item.resolvedUsername(), item.displayName(), item.typeLabel(),
                            () -> { item.requestSent = true; if (adapter != null) adapter.notifyItemChanged(position); });
                });
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }

    private class CardGroupDecoration extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0 || pos >= items.size()) return;
            ListItem item = items.get(pos);
            if (item.viewType == TYPE_SECTION_HEADER) {
                out.set(0, 0, 0, 0);
                return;
            }
            boolean isFirst = (pos == 0) || items.get(pos - 1).viewType == TYPE_SECTION_HEADER;
            boolean isLast  = (pos == items.size() - 1) || items.get(pos + 1).viewType == TYPE_SECTION_HEADER;
            out.left  = dp(14);
            out.right = dp(14);
            out.top    = isFirst ? 0 : 0;
            out.bottom = isLast  ? dp(2) : 0;
        }
    }
}

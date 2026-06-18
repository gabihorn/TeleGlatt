package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.askan.AskanFilter;
import org.telegram.messenger.askan.AskanUiHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class AskanBlockedChatsActivity extends BaseFragment {

    // ── Data ──────────────────────────────────────────────────────────────────

    private static final int ITEM_CHAT = 0;
    private static final int ITEM_USER = 1;

    private static class Item {
        final int type;           // ITEM_CHAT or ITEM_USER
        final TLRPC.Chat chat;
        final TLRPC.User user;
        boolean requestSent = false;

        Item(TLRPC.Chat c) { type = ITEM_CHAT; chat = c; user = null; }
        Item(TLRPC.User u) { type = ITEM_USER; user = u; chat = null; }

        String displayName() {
            return chat != null ? chat.title
                    : (user != null && user.first_name != null ? user.first_name : "");
        }

        String typeLabel() {
            if (user != null && user.bot) return "בוט";
            if (chat != null && ChatObject.isMegagroup(chat)) return "קבוצה";
            return "ערוץ";
        }

        String chatUsername() {
            if (chat != null)
                return (chat.username != null && !chat.username.isEmpty())
                        ? chat.username : String.valueOf(chat.id);
            if (user != null)
                return (user.username != null && !user.username.isEmpty())
                        ? user.username : String.valueOf(user.id);
            return "";
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();

    // ── Views ─────────────────────────────────────────────────────────────────

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public boolean onFragmentCreate() {
        buildList();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        // Action bar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("תוכן חסום");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Empty state
        emptyView = new TextView(context);
        emptyView.setText("אין תוכן חסום");
        emptyView.setTextSize(16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyView.setGravity(Gravity.CENTER);
        frame.addView(emptyView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // List
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context,
                LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        frame.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    // ── Data builder ──────────────────────────────────────────────────────────

    private void buildList() {
        items.clear();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        AskanFilter filter = AskanFilter.getInstance();

        for (TLRPC.Dialog dialog : dialogs) {
            long did = dialog.id;
            if (did < 0) {
                TLRPC.Chat chat = mc.getChat(-did);
                TLRPC.ChatFull full = mc.getChatFull(-did);
                if (filter.isChatBlocked(chat, full)) {
                    items.add(new Item(chat));
                }
            } else if (did > 0) {
                TLRPC.User user = mc.getUser(did);
                if (filter.isUserBlocked(user)) {
                    items.add(new Item(user));
                }
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context ctx) { this.context = ctx; }

        @Override public int getItemCount() { return items.size(); }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return false; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new BlockedChatCell(context));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((BlockedChatCell) holder.itemView).bind(items.get(position), position);
        }
    }

    // ── Row cell ──────────────────────────────────────────────────────────────

    private class BlockedChatCell extends FrameLayout {

        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable;
        private final TextView nameView;
        private final TextView typeView;
        private final TextView requestButton;
        private final View divider;

        private final Paint dividerPaint = new Paint();

        BlockedChatCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            dividerPaint.setColor(Theme.getColor(Theme.key_divider));
            dividerPaint.setStrokeWidth(1);

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(20));
            addView(avatarView, LayoutHelper.createFrame(40, 40,
                    Gravity.START | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            nameView = new TextView(context);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(15);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP, 68, 10, 100, 0));

            typeView = new TextView(context);
            typeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            typeView.setTextSize(12);
            addView(typeView, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.BOTTOM, 68, 0, 0, 10));

            requestButton = new TextView(context);
            requestButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            requestButton.setTextSize(13);
            requestButton.setTypeface(Typeface.DEFAULT_BOLD);
            requestButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(requestButton, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            divider = new View(context);
            addView(divider, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, 1,
                    Gravity.BOTTOM | Gravity.START, 68, 0, 0, 0));
        }

        void bind(Item item, int position) {
            // Avatar
            if (item.type == ITEM_CHAT) {
                avatarDrawable.setInfo(currentAccount, item.chat);
                avatarView.setForUserOrChat(item.chat, avatarDrawable);
            } else {
                avatarDrawable.setInfo(currentAccount, item.user);
                avatarView.setForUserOrChat(item.user, avatarDrawable);
            }

            nameView.setText(item.displayName());
            typeView.setText(item.typeLabel());

            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            divider.setVisibility(position < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);

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
                    Context ctx = getParentActivity();
                    if (ctx == null) return;
                    AskanUiHelper.showAccessRequestNoteDialog(
                            ctx, currentAccount,
                            item.chatUsername(), item.displayName(), item.typeLabel(),
                            () -> {
                                item.requestSent = true;
                                adapter.notifyItemChanged(position);
                            });
                });
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }
}

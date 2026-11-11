package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.askan.AskanFilter;
import org.telegram.messenger.askan.AskanFilterLevels;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * "הגדרות הסינון שלי" — every filter option as a plain Telegram settings row.
 * Tapping a row lists the other values: a stricter one applies at once (after a
 * warning), a looser one sends a request to the team.
 */
public class AskanFilterSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0, TYPE_OPTION = 1, TYPE_INFO = 2, TYPE_SHADOW = 3;

    private static final String[][] GROUPS = {
            {"מדיה", AskanFilterLevels.MEDIA_BLUR, AskanFilterLevels.VIDEOS, AskanFilterLevels.PRIVATE_MEDIA,
                    AskanFilterLevels.GROUP_MEDIA, AskanFilterLevels.FORWARDED_MEDIA},
            {"פרופילים וסטוריז", AskanFilterLevels.STORIES, AskanFilterLevels.PROFILE_PHOTOS},
            {"קבוצות ובוטים", AskanFilterLevels.BASIC_GROUPS, AskanFilterLevels.MINI_APPS,
                    AskanFilterLevels.FOREIGN_IFRAMES},
    };

    private static class Row {
        final int type; final String text; boolean divider;
        Row(int type, String text) { this.type = type; this.text = text; }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private List<AskanFilter.RequestInfo> myRequests = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;

    @Override
    public boolean onFragmentCreate() {
        buildRows();
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me != null) {
            AskanFilter.getInstance().fetchMyRequests(me.phone,
                    UserConfig.getInstance(currentAccount).getClientUserId(), requests -> {
                        myRequests = requests;
                        if (adapter != null) adapter.notifyDataSetChanged();
                    });
        }
        return super.onFragmentCreate();
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(TYPE_INFO, "אפשר להחמיר מיד. כדי להקל שולחים בקשה לצוות."));
        for (String[] g : GROUPS) {
            rows.add(new Row(TYPE_HEADER, g[0]));
            for (int i = 1; i < g.length; i++) {
                Row r = new Row(TYPE_OPTION, g[i]);
                r.divider = i < g.length - 1;
                rows.add(r);
            }
            rows.add(new Row(TYPE_SHADOW, null));
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("הגדרות הסינון שלי");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size() && rows.get(position).type == TYPE_OPTION) {
                showChooser(rows.get(position).text);
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private boolean hasPendingRequest(String option) {
        for (AskanFilter.RequestInfo r : myRequests) {
            if (("privacy".equals(r.kind) || "filter".equals(r.kind))
                    && option.equals(r.privacyTarget) && "pending".equals(r.status)) return true;
        }
        return false;
    }

    private String valueText(String option) {
        if (hasPendingRequest(option)) return "ממתין לאישור";
        return AskanFilterLevels.valueLabel(AskanFilter.getInstance().getFilterOption(option));
    }

    /** Lists every value: stricter ones apply at once, looser ones send a request. */
    private void showChooser(String option) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        if (hasPendingRequest(option)) {
            new AlertDialog.Builder(ctx).setTitle(AskanFilterLevels.optionLabel(option))
                    .setMessage("כבר נשלחה בקשה לשינוי ההגדרה הזו, והיא ממתינה לאישור.")
                    .setPositiveButton("סגירה", null).show();
            return;
        }
        String current = AskanFilter.getInstance().getFilterOption(option);
        List<String> shown = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (String v : AskanFilterLevels.OPTIONS.get(option).keySet()) {
            if (v.equals(current)) continue;
            boolean stricter = AskanFilterLevels.isStricter(option, v, current);
            boolean looser = AskanFilterLevels.isStricter(option, current, v);
            if (!stricter && !looser) continue; // sideways (e.g. contacts ↔ photos): not offered
            shown.add(v);
            labels.add(AskanFilterLevels.valueLabel(v) + (stricter ? "" : "  (בקשה לצוות)"));
        }
        if (shown.isEmpty()) return;
        String title = AskanFilterLevels.optionLabel(option);
        String[] lock = AskanFilter.getInstance().getFilterLock(option);
        String note = null;
        if ("cooldown".equals(lock[0])) {
            String d = AskanFilter.shortDate(lock[1]);
            if (!d.isEmpty()) note = "בקשה להקלה אפשרית החל מ־" + d;
        } else if ("admin_only".equals(lock[0])) {
            note = "רק מנהל יכול להקל את ההגדרה הזו";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(note != null ? title + "\n" + note : title)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    String v = shown.get(which);
                    if (AskanFilterLevels.isStricter(option, v, current)) tighten(option, v);
                    else requestLoosen(option, v);
                })
                .setNegativeButton("ביטול", null);
        showDialog(b.create());
    }

    private void tighten(String option, String value) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle(AskanFilterLevels.optionLabel(option))
                .setMessage("לשנות ל\"" + AskanFilterLevels.valueLabel(value) + "\"?\n\n"
                        + "כדי לחזור אחר כך צריך לשלוח בקשה לצוות.")
                .setPositiveButton("שינוי", (d, w) -> {
                    TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
                    if (me == null) return;
                    AskanFilter.getInstance().sendFilterTighten(me.phone,
                            UserConfig.getInstance(currentAccount).getClientUserId(), option, value,
                            () -> { if (adapter != null) adapter.notifyDataSetChanged(); },
                            () -> {
                                Context c2 = getParentActivity();
                                if (c2 != null) new AlertDialog.Builder(c2).setMessage("לא הצלחנו לעדכן את ההגדרה. נסו שוב.")
                                        .setPositiveButton("סגירה", null).show();
                            });
                })
                .setNegativeButton("ביטול", null).show();
    }

    private void requestLoosen(String option, String value) {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me == null) return;
        AskanFilter.getInstance().sendFilterRequest(me.phone,
                UserConfig.getInstance(currentAccount).getClientUserId(), option, value,
                (status, until) -> {
                    if ("pending".equals(status) || "already_pending".equals(status)) {
                        List<AskanFilter.RequestInfo> updated = new ArrayList<>(myRequests);
                        updated.add(new AskanFilter.RequestInfo(0, "", "", "pending", "filter", option));
                        myRequests = updated;
                        if (adapter != null) adapter.notifyDataSetChanged();
                        Context ctx = getParentActivity();
                        if (ctx != null) new AlertDialog.Builder(ctx).setMessage("הבקשה נשלחה לצוות.")
                                .setPositiveButton("סגירה", null).show();
                    } else {
                        showRequestResult(getParentActivity(), status, until);
                    }
                });
    }

    /** Explains a loosening request that was not sent (cooldown / admin only / error). */
    static void showRequestResult(Context ctx, String status, String until) {
        if (ctx == null) return;
        String msg;
        if ("cooldown".equals(status)) {
            String d = AskanFilter.shortDate(until);
            msg = "אפשר יהיה לבקש את זה" + (d.isEmpty() ? " בעוד כמה ימים." : " החל מ־" + d + ".");
        } else if ("admin_only".equals(status)) {
            msg = "ההגדרה הזו נפתחה ונסגרה כמה פעמים לאחרונה, ולכן רק מנהל יכול לפתוח אותה. אפשר לפנות לתמיכה.";
        } else if ("already_open".equals(status)) {
            msg = "ההגדרה כבר פתוחה.";
        } else {
            msg = "לא הצלחנו לשלוח את הבקשה. נסו שוב.";
        }
        new AlertDialog.Builder(ctx).setMessage(msg).setPositiveButton("סגירה", null).show();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;
        Adapter(Context c) { ctx = c; }

        @Override public int getItemCount() { return rows.size(); }
        @Override public int getItemViewType(int p) { return rows.get(p).type; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder h) { return h.getItemViewType() == TYPE_OPTION; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case TYPE_HEADER:
                    v = new HeaderCell(ctx);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    v = new TextInfoPrivacyCell(ctx);
                    break;
                case TYPE_SHADOW:
                    v = new ShadowSectionCell(ctx);
                    break;
                default:
                    v = new TextSettingsCell(ctx);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row r = rows.get(position);
            switch (r.type) {
                case TYPE_HEADER: ((HeaderCell) holder.itemView).setText(r.text); break;
                case TYPE_INFO: ((TextInfoPrivacyCell) holder.itemView).setText(r.text); break;
                case TYPE_OPTION:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            AskanFilterLevels.optionLabel(r.text), valueText(r.text), r.divider);
                    break;
            }
        }
    }
}

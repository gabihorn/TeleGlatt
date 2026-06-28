package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Static, offline FAQ screen for TeleGlatt. Accessible from the main menu.
 * Each item is a card: tap the question to expand/collapse the answer.
 */
public class AskanFaqActivity extends BaseFragment {

    private static final String[][] FAQ = {
        {"מה זה TeleGlatt?",
         "אפליקציית מסרים המבוססת על טלגרם, עם שכבת סינון מבית עסקן. כל היכולות של טלגרם — שיחות, קבוצות, קבצים — אבל ערוצים, קבוצות ובוטים עם תוכן בעייתי חסומים אוטומטית."},
        {"האם השיחות שלי פרטיות?",
         "כן. השיחות האישיות שלך פתוחות ופרטיות לחלוטין, בדיוק כמו בטלגרם. איננו קוראים, מוכרים או שומרים את התוכן שלך."},
        {"איך מתחברים?",
         "מזינים את מספר הטלפון. קוד האימות יגיע לאפליקציית הטלגרם הקיימת שלכם במכשיר (כמו בכל התחברות למכשיר חדש), מזינים אותו — והחשבון מוכן."},
        {"נתקלתי בערוץ או קבוצה חסומים — מה לעשות?",
         "אם אתם זקוקים לגישה, לחצו על \"בקשת גישה\", הוסיפו הסבר קצר (לא חובה) ושלחו. הבקשה תיבדק, ותקבלו התראה אוטומטית ברגע שתאושר."},
        {"מי מחליט מה חסום?",
         "הסינון נקבע לפי מדיניות הצוות או חברת הסינון שלכם. בקשות גישה נבדקות ומאושרות בהתאם."},
        {"איך מסתירים תמונת פרופיל או סטטוס (סטוריז)?",
         "מהתפריט בחרו ב\"תוכן חסום\" ← הגדרות פרטיות, והפעילו את ההסתרה בלחיצה."},
        {"איפה מנהלים את הבקשות וההגדרות שלי?",
         "מהתפריט ← \"תוכן חסום\". שם מרוכזים: בקשות ממתינות, הגדרות פרטיות, ורשימת התוכן החסום עם אפשרות לבקש גישה."},
        {"איך מעדכנים את האפליקציה?",
         "כשתצא גרסה חדשה תקבלו הצעה לעדכן ישירות מתוך האפליקציה — בלחיצה אחת."},
        {"האם זה מחליף את הטלגרם הרגיל?",
         "כן. זה אותו חשבון טלגרם, עם חוויה מסוננת ובטוחה. אפשר להשתמש ב-TeleGlatt כאפליקציית הטלגרם היחידה שלכם."},
        {"נתקלתי בבעיה — למי פונים?",
         "עדכונים ותמיכה מתפרסמים בערוץ העדכונים (זמין מהתפריט)."},
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("שאלות נפוצות");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(list, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        for (String[] qa : FAQ) {
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            list.addView(makeItem(context, qa[0], qa[1]), lp);
        }

        fragmentView = scroll;
        return fragmentView;
    }

    private View makeItem(Context ctx, String q, String a) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView question = new TextView(ctx);
        question.setText(q);
        question.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        question.setTypeface(Typeface.DEFAULT_BOLD);
        question.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        question.setGravity(Gravity.RIGHT);
        card.addView(question, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView answer = new TextView(ctx);
        answer.setText(a);
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        answer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        answer.setGravity(Gravity.RIGHT);
        answer.setLineSpacing(dp(3), 1f);
        answer.setVisibility(View.GONE);
        LinearLayout.LayoutParams aLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        aLp.topMargin = dp(8);
        card.addView(answer, aLp);

        card.setOnClickListener(v ->
                answer.setVisibility(answer.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        return card;
    }
}

package de.smb1display;

import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Shared app surfaces: dialogs, choices, dropdowns and transient messages. */
final class AppDialog extends Dialog {
    final LinearLayout body,footer;
    private final Context context;
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    private int dp(int n){return dp(context,n);}
    static GradientDrawable surface(Context c,int color,int radius,boolean border){GradientDrawable d=new GradientDrawable();d.setColor(c.getColor(color));d.setCornerRadius(dp(c,radius));if(border)d.setStroke(dp(c,1),c.getColor(R.color.line));return d;}
    static android.graphics.drawable.Drawable interactive(Context c,int color,int radius,boolean border){int accent=c.getColor(R.color.accent);int ripple=(accent&0x00ffffff)|0x25000000;return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple),surface(c,color,radius,border),surface(c,R.color.surface,radius,false));}
    static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(c.getColor(color));t.setFontFeatureSettings("tnum");t.setLineSpacing(dp(c,3),1);return t;}
    AppDialog(Context c,String label,String title,String subtitle,int icon){
        super(c);context=c;requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=new LinearLayout(c);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackground(surface(c,R.color.surface,28,true));shell.setPadding(dp(22),dp(22),dp(22),dp(18));shell.setClipToOutline(true);
        LinearLayout heading=new LinearLayout(c);heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image=new ImageView(c);image.setImageResource(icon);image.setImageTintList(android.content.res.ColorStateList.valueOf(c.getColor(R.color.accent)));image.setBackground(surface(c,R.color.surface_alt,16,false));image.setPadding(dp(12),dp(12),dp(12),dp(12));heading.addView(image,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titles=new LinearLayout(c);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(14),0,0,0);TextView eyebrow=text(c,label.toUpperCase(java.util.Locale.GERMANY),11,R.color.accent);eyebrow.setLetterSpacing(.12f);eyebrow.setTypeface(null,android.graphics.Typeface.BOLD);titles.addView(eyebrow);
        TextView name=text(c,title,24,R.color.ink);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));name.setAccessibilityHeading(true);titles.addView(name);heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));shell.addView(heading);
        if(!subtitle.isEmpty()){TextView sub=text(c,subtitle,14,R.color.muted);sub.setPadding(0,dp(12),0,dp(16));shell.addView(sub);}else heading.setPadding(0,0,0,dp(16));
        ScrollView scroll=new ScrollView(c){@Override protected void onMeasure(int width,int height){int h=getResources().getDisplayMetrics().heightPixels;int cap=Math.max(dp(60),Math.min((int)(h*.56f),h-dp(260)));super.onMeasure(width,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));}};scroll.setClipToPadding(false);scroll.setFillViewport(false);
        body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,0,0,dp(4));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        footer=new LinearLayout(c);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(18),0,0);shell.addView(footer);setContentView(shell);setCanceledOnTouchOutside(true);
        Window w=getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.6f);w.setGravity(Gravity.CENTER);
    }
    @Override protected void onStart(){super.onStart();int width=Math.min(getContext().getResources().getDisplayMetrics().widthPixels-dp(32),dp(560));getWindow().setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);}
    TextView paragraph(String value){TextView t=text(context,value,15,R.color.muted);t.setPadding(0,dp(6),0,dp(8));body.addView(t);return t;}
    LinearLayout group(String title){LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(14),dp(16),dp(14));box.setBackground(surface(context,R.color.surface_alt,18,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);body.addView(box,lp);if(!title.isEmpty()){TextView heading=text(context,title,15,R.color.ink);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setAccessibilityHeading(true);heading.setPadding(0,0,0,dp(8));box.addView(heading);}return box;}
    void row(LinearLayout box,String label,String value){LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(5),0,dp(5));TextView l=text(context,label,14,R.color.muted),v=text(context,value,15,R.color.ink);v.setGravity(Gravity.END);v.setTextIsSelectable(true);row.addView(l,new LinearLayout.LayoutParams(0,-2,1));row.addView(v,new LinearLayout.LayoutParams(0,-2,1));box.addView(row);}
    Button action(String label,boolean primary,Runnable action){Button b=new Button(context);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));b.setTextColor(context.getColor(primary?R.color.accent_ink:R.color.ink));b.setBackground(interactive(context,primary?R.color.accent:R.color.surface_alt,14,false));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(footer.getChildCount()>0)lp.leftMargin=dp(10);footer.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    static Spinner spinner(Context c,String[] labels){Spinner spinner=new Spinner(c,Spinner.MODE_DROPDOWN);ArrayAdapter<String> adapter=new ArrayAdapter<>(c,R.layout.choice_selected,labels);adapter.setDropDownViewResource(R.layout.choice_dropdown);spinner.setAdapter(adapter);spinner.setPopupBackgroundDrawable(surface(c,R.color.surface,18,true));spinner.setBackground(surface(c,R.color.surface_alt,14,false));spinner.setPadding(dp(c,8),dp(c,2),dp(c,8),dp(c,2));spinner.setDropDownVerticalOffset(dp(c,48));return spinner;}
    static void notice(Activity activity,String message){
        FrameLayout root=activity.findViewById(android.R.id.content);View previous=root.findViewWithTag("appNotice");if(previous!=null)root.removeView(previous);
        LinearLayout card=new LinearLayout(activity);card.setTag("appNotice");card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(activity,16),dp(activity,12),dp(activity,8),dp(activity,12));card.setBackground(surface(activity,R.color.surface,20,true));card.setElevation(dp(activity,12));TextView text=text(activity,message,15,R.color.ink);card.addView(text,new LinearLayout.LayoutParams(0,-2,1));Button close=new Button(activity);close.setText("×");close.setTextSize(24);close.setTextColor(activity.getColor(R.color.muted));close.setContentDescription("Meldung schließen");close.setBackgroundColor(Color.TRANSPARENT);card.addView(close,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));close.setOnClickListener(v->root.removeView(card));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.setMargins(dp(activity,16),0,dp(activity,16),dp(activity,88));root.addView(card,lp);card.announceForAccessibility(message);card.postDelayed(()->root.removeView(card),6000);
    }
}

package app.wraith.client;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import static app.wraith.client.Ui.*;

/**
 * Material 3 dialogs behind the app's small builder API: action lists become bottom sheets,
 * everything else is an M3 alert dialog. Buttons created with a null listener can be taken over
 * in the show listener (validation keeps the dialog open), as with a platform AlertDialog.
 */
final class InlinePanel implements DialogInterface {
    final Activity activity;final Builder spec;private android.app.Dialog dialog;OnShowListener onShow;
    InlinePanel(Builder b){spec=b;activity=b.activity;}
    public void dismiss(){if(dialog!=null&&dialog.isShowing())try{dialog.dismiss();}catch(Exception ignored){}}
    public void cancel(){dismiss();}
    public void setOnShowListener(OnShowListener l){onShow=l;}
    public Button getButton(int which){return dialog instanceof AlertDialog?((AlertDialog)dialog).getButton(which):null;}
    /** Dialogs handle the back key themselves. */
    static boolean dismissTop(Activity activity){return false;}

    void show(){
        if(activity.isFinishing())return;
        if(spec.items!=null&&spec.checks==null&&!spec.single){dialog=sheet();}
        else{
            MaterialAlertDialogBuilder b=new MaterialAlertDialogBuilder(activity);
            if(!spec.title.isEmpty())b.setTitle(spec.title);
            if(spec.message!=null)b.setMessage(spec.message);
            if(spec.view!=null){
                if(spec.view.getParent()!=null)((ViewGroup)spec.view.getParent()).removeView(spec.view);
                FrameLayout holder=new FrameLayout(activity);pad(holder,24,8,24,0);holder.addView(spec.view);
                ScrollView scroll=new ScrollView(activity);scroll.addView(holder);b.setView(scroll);
            }
            if(spec.items!=null){
                if(spec.checks!=null)b.setMultiChoiceItems(spec.items,spec.checks,(d,i,on)->{spec.checks[i]=on;if(spec.multi!=null)spec.multi.onClick(this,i,on);});
                else b.setSingleChoiceItems(spec.items,spec.selected,(d,i)->spec.choice.onClick(this,i));
            }
            for(Map.Entry<Integer,String> e:spec.labels.entrySet()){
                int which=e.getKey();OnClickListener listener=spec.listeners.get(which);
                DialogInterface.OnClickListener wrapped=(d,w)->{if(listener!=null)listener.onClick(this,which);};
                if(which==BUTTON_POSITIVE)b.setPositiveButton(e.getValue(),wrapped);
                else if(which==BUTTON_NEGATIVE)b.setNegativeButton(e.getValue(),wrapped);
                else b.setNeutralButton(e.getValue(),wrapped);
            }
            AlertDialog alert=b.create();dialog=alert;
            alert.setOnShowListener(d->{if(spec.view!=null&&spec.view instanceof ViewGroup)focusFirstField((ViewGroup)spec.view,alert);if(onShow!=null)onShow.onShow(this);});
        }
        dialog.show();
    }
    private static void focusFirstField(ViewGroup group,AlertDialog alert){
        for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);
            if(child instanceof EditText){child.requestFocus();if(alert.getWindow()!=null)alert.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);return;}
            if(child instanceof ViewGroup)focusFirstField((ViewGroup)child,alert);
        }
    }
    /** M3 modal bottom sheet with a drag handle, title and one-line actions. */
    private android.app.Dialog sheet(){
        BottomSheetDialog sheet=new BottomSheetDialog(activity);
        LinearLayout content=column(activity);pad(content,0,8,0,24);
        View handle=new View(activity);handle.setBackground(bg(withAlpha(MUTED,.4f),dp(activity,2)));LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(dp(activity,32),dp(activity,4));hl.gravity=Gravity.CENTER_HORIZONTAL;hl.bottomMargin=dp(activity,12);content.addView(handle,hl);
        if(!spec.title.isEmpty()){TextView t=title(activity,spec.title,16,TEXT);t.setMaxLines(2);pad(t,24,4,24,12);content.addView(t);}
        if(spec.message!=null){TextView m=text(activity,spec.message,14,MUTED);pad(m,24,0,24,12);content.addView(m);}
        for(int i=0;i<spec.items.length;i++){final int index=i;
            LinearLayout item=row(activity);pad(item,24,0,24,0);item.setMinimumHeight(dp(activity,56));item.setBackground(ripple(bg(0x00000000,0),0));
            String icon=spec.icons==null||i>=spec.icons.length?null:spec.icons[i];
            boolean danger=spec.danger!=null&&i<spec.danger.length&&spec.danger[i];
            if(icon!=null){Icon glyph=new Icon(activity,icon,danger?RED:MUTED);LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(dp(activity,24),dp(activity,24));il.rightMargin=dp(activity,16);item.addView(glyph,il);}
            TextView label=text(activity,spec.items[i],16,danger?RED:TEXT);item.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            item.setOnClickListener(v->{sheet.dismiss();spec.choice.onClick(this,index);});
            content.addView(item,new LinearLayout.LayoutParams(-1,-2));
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        sheet.setContentView(scroll);
        sheet.setOnShowListener(d->{if(onShow!=null)onShow.onShow(this);});
        return sheet;
    }
    static final class Builder {
        final Activity activity;String title="",message;View view;String[] items,icons;boolean[] checks,danger;boolean single,compact,centered;int selected;OnClickListener choice;OnMultiChoiceClickListener multi;
        final Map<Integer,String> labels=new LinkedHashMap<>();final Map<Integer,OnClickListener> listeners=new HashMap<>();
        Builder(Activity a){activity=a;}Builder setTitle(String t){title=t==null?"":t;return this;}Builder setMessage(String m){message=m;return this;}Builder setView(View v){view=v;return this;}Builder setCompact(boolean value){compact=value;return this;}Builder setCentered(boolean value){centered=value;return this;}
        Builder button(int id,String label,OnClickListener l){labels.put(id,label);listeners.put(id,l);return this;}
        Builder setPositiveButton(String s,OnClickListener l){return button(BUTTON_POSITIVE,s,l);}Builder setNegativeButton(String s,OnClickListener l){return button(BUTTON_NEGATIVE,s,l);}Builder setNeutralButton(String s,OnClickListener l){return button(BUTTON_NEUTRAL,s,l);}
        Builder setItems(String[] a,OnClickListener l){items=a;choice=l;return this;}
        /** Bottom-sheet actions with leading icons; flagged entries are drawn in the error colour. */
        Builder setActions(String[] a,String[] glyphs,boolean[] destructive,OnClickListener l){items=a;icons=glyphs;danger=destructive;choice=l;return this;}
        Builder setSingleChoiceItems(String[] a,int i,OnClickListener l){items=a;selected=i;choice=l;single=true;return this;}Builder setMultiChoiceItems(String[] a,boolean[] c,OnMultiChoiceClickListener l){items=a;checks=c;multi=l;return this;}
        InlinePanel create(){return new InlinePanel(this);}InlinePanel show(){InlinePanel p=create();p.show();return p;}
    }
}

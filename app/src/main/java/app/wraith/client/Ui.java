package app.wraith.client;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.*;
import com.google.android.material.color.MaterialColors;

/**
 * Material 3 tokens and small view helpers. Colours are read from the activity theme, so the
 * wallpaper-based dynamic palette (Android 12+) and light/dark mode apply to every screen.
 * The short legacy names stay because the whole UI is built in code with them.
 */
final class Ui {
    /** BG=surface, CARD=surfaceContainer, CARD2=surfaceContainerHigh, LINE=outlineVariant, TEXT=onSurface, MUTED=onSurfaceVariant, MINT=primary, LILAC=tertiary, RED=error. */
    static int BG,CARD,CARD2,LINE,TEXT,MUTED,MINT,LILAC,RED;
    static int ON_PRIMARY,PRIMARY_CONTAINER,ON_PRIMARY_CONTAINER,SECONDARY_CONTAINER,ON_SECONDARY_CONTAINER,TERTIARY_CONTAINER,ON_TERTIARY_CONTAINER,ERROR_CONTAINER,ON_ERROR_CONTAINER,SURFACE_LOW,SURFACE_HIGHEST,OUTLINE;
    static boolean DARK=true;
    static float FONT=1f;
    static{fallback(true);}

    static void applyTheme(Context c,float fontScale){
        FONT=fontScale;
        DARK=(c.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BG=attr(c,com.google.android.material.R.attr.colorSurface,BG);
        CARD=attr(c,com.google.android.material.R.attr.colorSurfaceContainer,CARD);
        CARD2=attr(c,com.google.android.material.R.attr.colorSurfaceContainerHigh,CARD2);
        SURFACE_LOW=attr(c,com.google.android.material.R.attr.colorSurfaceContainerLow,SURFACE_LOW);
        SURFACE_HIGHEST=attr(c,com.google.android.material.R.attr.colorSurfaceContainerHighest,SURFACE_HIGHEST);
        LINE=attr(c,com.google.android.material.R.attr.colorOutlineVariant,LINE);
        OUTLINE=attr(c,com.google.android.material.R.attr.colorOutline,OUTLINE);
        TEXT=attr(c,com.google.android.material.R.attr.colorOnSurface,TEXT);
        MUTED=attr(c,com.google.android.material.R.attr.colorOnSurfaceVariant,MUTED);
        MINT=attr(c,androidx.appcompat.R.attr.colorPrimary,MINT);
        ON_PRIMARY=attr(c,com.google.android.material.R.attr.colorOnPrimary,ON_PRIMARY);
        PRIMARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorPrimaryContainer,PRIMARY_CONTAINER);
        ON_PRIMARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorOnPrimaryContainer,ON_PRIMARY_CONTAINER);
        SECONDARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorSecondaryContainer,SECONDARY_CONTAINER);
        ON_SECONDARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorOnSecondaryContainer,ON_SECONDARY_CONTAINER);
        LILAC=attr(c,com.google.android.material.R.attr.colorTertiary,LILAC);
        TERTIARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorTertiaryContainer,TERTIARY_CONTAINER);
        ON_TERTIARY_CONTAINER=attr(c,com.google.android.material.R.attr.colorOnTertiaryContainer,ON_TERTIARY_CONTAINER);
        RED=attr(c,androidx.appcompat.R.attr.colorError,RED);
        ERROR_CONTAINER=attr(c,com.google.android.material.R.attr.colorErrorContainer,ERROR_CONTAINER);
        ON_ERROR_CONTAINER=attr(c,com.google.android.material.R.attr.colorOnErrorContainer,ON_ERROR_CONTAINER);
    }
    private static int attr(Context c,int id,int fallback){return MaterialColors.getColor(c,id,fallback);}
    /** Used only before a themed context exists (and by JVM tests). */
    private static void fallback(boolean dark){
        DARK=dark;
        BG=Color.rgb(14,21,19);CARD=Color.rgb(27,33,31);CARD2=Color.rgb(37,43,41);SURFACE_LOW=Color.rgb(23,29,27);SURFACE_HIGHEST=Color.rgb(48,54,52);
        LINE=Color.rgb(63,73,69);OUTLINE=Color.rgb(137,147,143);TEXT=Color.rgb(222,228,224);MUTED=Color.rgb(191,201,196);
        MINT=Color.rgb(130,213,192);ON_PRIMARY=Color.rgb(0,56,46);PRIMARY_CONTAINER=Color.rgb(0,81,68);ON_PRIMARY_CONTAINER=Color.rgb(158,242,220);
        SECONDARY_CONTAINER=Color.rgb(51,75,69);ON_SECONDARY_CONTAINER=Color.rgb(205,232,223);
        LILAC=Color.rgb(170,203,228);TERTIARY_CONTAINER=Color.rgb(42,74,95);ON_TERTIARY_CONTAINER=Color.rgb(200,230,255);
        RED=Color.rgb(255,180,171);ERROR_CONTAINER=Color.rgb(147,0,10);ON_ERROR_CONTAINER=Color.rgb(255,218,214);
    }
    /** "system" | "light" | "dark" → AppCompat night mode. */
    static int nightMode(String mode){
        return mode.equals("light")?androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO:mode.equals("system")?androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
    }
    static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static GradientDrawable corners(int color,float top,float bottom){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadii(new float[]{top,top,top,top,bottom,bottom,bottom,bottom});return d;}
    /** Rounded surface with an M3 state layer (ripple) when the view is clickable. */
    static Drawable glass(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT,.12f)),bg(color,radius),bg(Color.WHITE,radius));}
    static Drawable ripple(Drawable content,int radius){return new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT,.12f)),content,bg(Color.WHITE,radius));}
    static Drawable backdrop(){return new ColorDrawable(BG);}
    static int withAlpha(int color,float alpha){return Color.argb((int)(255*alpha),Color.red(color),Color.green(color),Color.blue(color));}
    static int blend(int a,int b,float amount){return Color.rgb((int)(Color.red(a)*(1-amount)+Color.red(b)*amount),(int)(Color.green(a)*(1-amount)+Color.green(b)*amount),(int)(Color.blue(a)*(1-amount)+Color.blue(b)*amount));}
    static GradientDrawable border(Context c,int color,int radius,int stroke){GradientDrawable d=bg(color,dp(c,radius));d.setStroke(dp(c,1),stroke);return d;}
    static TextView text(Context c,String s,int size,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(size*FONT);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(0,1.12f);return t;}
    /** M3 "title"/"headline" weight: medium, not bold. */
    static TextView title(Context c,String s,int size,int color){TextView t=text(c,s,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static void pad(View v,int l,int t,int r,int b){Context c=v.getContext();v.setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b));}

    /** Line icons drawn on a 24×24 grid, shared by views and drawables. */
    static void drawGlyph(Canvas c,String name,Paint p,int background){
        Path path=new Path();
        switch(name){
            case "more":p.setStrokeWidth(3);c.drawPoint(12,5,p);c.drawPoint(12,12,p);c.drawPoint(12,19,p);break;
            case "logs":c.drawRoundRect(4,2,20,22,2,2,p);c.drawLine(8,7,16,7,p);c.drawLine(8,12,16,12,p);c.drawLine(8,17,13,17,p);break;
            case "home":path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,21);path.lineTo(10,21);path.lineTo(10,15);path.lineTo(14,15);path.lineTo(14,21);path.lineTo(19,21);path.lineTo(19,9);c.drawPath(path,p);break;
            case "servers":c.drawRoundRect(3,3,21,10,2,2,p);c.drawRoundRect(3,14,21,21,2,2,p);c.drawPoint(7,6.5f,p);c.drawPoint(7,17.5f,p);c.drawLine(13,6.5f,17,6.5f,p);c.drawLine(13,17.5f,17,17.5f,p);break;
            case "route":c.drawCircle(6,5,2,p);c.drawCircle(18,19,2,p);path.moveTo(6,7);path.lineTo(6,15);path.quadTo(6,19,10,19);path.lineTo(16,19);path.moveTo(6,11);path.lineTo(15,11);path.quadTo(18,11,18,8);path.lineTo(18,3);c.drawPath(path,p);break;
            case "settings":c.drawLine(4,6,20,6,p);c.drawLine(4,12,20,12,p);c.drawLine(4,18,20,18,p);p.setStyle(Paint.Style.FILL);int color=p.getColor();p.setColor(background);c.drawCircle(9,6,3,p);c.drawCircle(16,12,3,p);c.drawCircle(9,18,3,p);p.setStyle(Paint.Style.STROKE);p.setColor(color);c.drawCircle(9,6,2.7f,p);c.drawCircle(16,12,2.7f,p);c.drawCircle(9,18,2.7f,p);break;
            case "plus":c.drawLine(5,12,19,12,p);c.drawLine(12,5,12,19,p);break;
            case "arrow":c.drawLine(9,6,15,12,p);c.drawLine(15,12,9,18,p);break;
            case "back":c.drawLine(19,12,5,12,p);c.drawLine(11,6,5,12,p);c.drawLine(5,12,11,18,p);break;
            case "close":c.drawLine(6,6,18,18,p);c.drawLine(18,6,6,18,p);break;
            case "down":c.drawLine(12,4,12,20,p);c.drawLine(6,14,12,20,p);c.drawLine(12,20,18,14,p);break;
            case "up":c.drawLine(12,20,12,4,p);c.drawLine(6,10,12,4,p);c.drawLine(12,4,18,10,p);break;
            case "expand":c.drawLine(6,9,12,15,p);c.drawLine(12,15,18,9,p);break;
            case "collapse":c.drawLine(6,15,12,9,p);c.drawLine(12,9,18,15,p);break;
            case "refresh":c.drawArc(new RectF(4,4,20,20),35,285,false,p);c.drawLine(20,5,20,10,p);c.drawLine(20,10,15,10,p);break;
            case "check":path.moveTo(5,12);path.lineTo(10,17);path.lineTo(20,6);c.drawPath(path,p);break;
            case "pulse":path.moveTo(2,12);path.lineTo(6,12);path.lineTo(9,5);path.lineTo(14,19);path.lineTo(17,12);path.lineTo(22,12);c.drawPath(path,p);break;
            case "qr":c.drawRect(3,3,9,9,p);c.drawRect(15,3,21,9,p);c.drawRect(3,15,9,21,p);c.drawLine(15,15,21,15,p);c.drawLine(15,15,15,21,p);c.drawPoint(20,20,p);break;
            case "shield":path.moveTo(12,2);path.lineTo(21,6);path.lineTo(20,14);path.quadTo(18,19,12,22);path.quadTo(6,19,4,14);path.lineTo(3,6);path.close();c.drawPath(path,p);break;
            case "search":c.drawCircle(10.5f,10.5f,6.5f,p);c.drawLine(15.5f,15.5f,20.5f,20.5f,p);break;
            case "star":path.moveTo(12,3);path.lineTo(14.6f,8.9f);path.lineTo(21,9.5f);path.lineTo(16.2f,13.7f);path.lineTo(17.6f,20);path.lineTo(12,16.7f);path.lineTo(6.4f,20);path.lineTo(7.8f,13.7f);path.lineTo(3,9.5f);path.lineTo(9.4f,8.9f);path.close();c.drawPath(path,p);break;
            case "edit":path.moveTo(4,20);path.lineTo(4,16);path.lineTo(15,5);path.lineTo(19,9);path.lineTo(8,20);path.close();c.drawPath(path,p);c.drawLine(13,7,17,11,p);break;
            case "delete":c.drawLine(4,6,20,6,p);path.moveTo(6,6);path.lineTo(7,21);path.lineTo(17,21);path.lineTo(18,6);c.drawPath(path,p);c.drawLine(9,6,10,3,p);c.drawLine(10,3,14,3,p);c.drawLine(14,3,15,6,p);break;
            case "share":c.drawCircle(18,5,2.5f,p);c.drawCircle(6,12,2.5f,p);c.drawCircle(18,19,2.5f,p);c.drawLine(8.2f,10.8f,15.8f,6.2f,p);c.drawLine(8.2f,13.2f,15.8f,17.8f,p);break;
            case "copy":c.drawRoundRect(8,8,20,20,2,2,p);path.moveTo(16,8);path.lineTo(16,4);path.lineTo(4,4);path.lineTo(4,16);path.lineTo(8,16);c.drawPath(path,p);break;
            case "code":c.drawLine(9,7,4,12,p);c.drawLine(4,12,9,17,p);c.drawLine(15,7,20,12,p);c.drawLine(20,12,15,17,p);break;
            case "info":c.drawCircle(12,12,9,p);c.drawLine(12,11,12,17,p);p.setStrokeWidth(2.4f);c.drawPoint(12,7.5f,p);break;
            case "power":c.drawArc(new RectF(4,5,20,21),-50,280,false,p);c.drawLine(12,3,12,12,p);break;
            case "palette":c.drawCircle(12,12,9,p);c.drawCircle(8,10,1.2f,p);c.drawCircle(12,7,1.2f,p);c.drawCircle(16,10,1.2f,p);break;
            case "download":c.drawLine(12,4,12,15,p);c.drawLine(7,10,12,15,p);c.drawLine(12,15,17,10,p);c.drawLine(5,20,19,20,p);break;
            case "apps":for(int x=0;x<3;x++)for(int y=0;y<3;y++)c.drawCircle(6+x*6,6+y*6,1.6f,p);break;
            case "chart":c.drawLine(4,20,20,20,p);c.drawLine(7,20,7,13,p);c.drawLine(12,20,12,6,p);c.drawLine(17,20,17,10,p);break;
            case "link":path.moveTo(10,14);path.lineTo(14,10);c.drawPath(path,p);c.drawArc(new RectF(3,10,13,20),45,180,false,p);c.drawArc(new RectF(11,4,21,14),225,180,false,p);break;
            default:c.drawCircle(12,12,8,p);
        }
    }
    static class Icon extends View {
        final String name;int color;
        Icon(Context c,String n,int col){super(c);name=n;color=col;setContentDescription(n);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float s=Math.min(getWidth(),getHeight())/24f;c.save();c.translate((getWidth()-24*s)/2,(getHeight()-24*s)/2);c.scale(s,s);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);drawGlyph(c,name,p,BG);c.restore();}
    }
    /** The same glyphs as a tintable Drawable, for bottom navigation, buttons and menus. */
    static final class Glyph extends Drawable {
        private final String name;private final int size;private ColorStateList tint=ColorStateList.valueOf(Color.BLACK);private int color=Color.BLACK;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context c,String name){this.name=name;size=dp(c,24);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);}
        Glyph tinted(int value){setTintList(ColorStateList.valueOf(value));return this;}
        @Override public void draw(Canvas c){Rect b=getBounds();float s=Math.min(b.width(),b.height())/24f;c.save();c.translate(b.left+(b.width()-24*s)/2,b.top+(b.height()-24*s)/2);c.scale(s,s);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);drawGlyph(c,name,p,Color.TRANSPARENT);c.restore();}
        @Override public void setTintList(ColorStateList value){tint=value==null?ColorStateList.valueOf(Color.BLACK):value;color=tint.getColorForState(getState(),tint.getDefaultColor());invalidateSelf();}
        @Override protected boolean onStateChange(int[] state){int next=tint.getColorForState(state,tint.getDefaultColor());if(next==color)return false;color=next;invalidateSelf();return true;}
        @Override public boolean isStateful(){return tint.isStateful();}
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    /**
     * Small animation helpers. Everything here is additive: a view that is never animated still
     * renders correctly, so animations cannot become a source of missing UI.
     */
    static final class Anim {
        private Anim(){}
        static final int FAST=150,BASE=250,SLOW=300;
        /** M3 "emphasized decelerate". */
        static Interpolator out(){return new PathInterpolator(.05f,.7f,.1f,1f);}
        static Interpolator spring(){return new OvershootInterpolator(.65f);}
        static void stagger(ViewGroup parent){
            for(int i=0;i<parent.getChildCount();i++){
                View child=parent.getChildAt(i);
                if(child.getHeight()==0&&child.getWidth()==0&&i>14)break;
                child.setAlpha(0f);child.setTranslationY(dp(parent.getContext(),10));
                child.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i*24L,160L)).setDuration(300).setInterpolator(out()).start();
            }
        }
        static void press(View v){
            v.animate().cancel();
            v.animate().scaleX(.975f).scaleY(.975f).setDuration(100).setInterpolator(out())
                .withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(spring()).start()).start();
        }
        /** Touch feedback is the ripple now; kept as a no-op hook for existing call sites. */
        static void touch(View v){}
        static void pop(View v){
            v.setScaleX(.5f);v.setScaleY(.5f);v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(spring()).start();
        }
        static void appear(View v,long delay){
            v.setAlpha(0f);v.setTranslationY(dp(v.getContext(),10));
            v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(BASE).setInterpolator(out()).start();
        }
        static void swap(TextView t,String value){
            if(value.equals(t.getText().toString()))return;
            t.animate().cancel();
            t.setText(value);t.setAlpha(.4f);
            t.animate().alpha(1f).setDuration(200).setInterpolator(out()).setListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationCancel(android.animation.Animator a){t.setAlpha(1f);}
                @Override public void onAnimationEnd(android.animation.Animator a){t.setAlpha(1f);}
            }).start();
        }
        static void tint(View v,int from,int to,int radius){
            if(from==to){v.setBackground(bg(to,radius));return;}
            android.animation.ValueAnimator a=android.animation.ValueAnimator.ofArgb(from,to);a.setDuration(BASE);a.setInterpolator(out());
            a.addUpdateListener(u->v.setBackground(bg((int)u.getAnimatedValue(),radius)));a.start();
        }
    }
    /** Connection button: a large M3 tonal circle with a power glyph and an indeterminate arc while connecting. */
    static class Orb extends View {
        int state;
        boolean pinging;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private long changed=System.currentTimeMillis();
        private int drawn=-1;
        Orb(Context c){super(c);setContentDescription(L.t("Подключить или отключить Wraith","Connect or disconnect Wraith"));setFocusable(true);setClickable(true);}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(e.getAction()==android.view.MotionEvent.ACTION_DOWN)animate().scaleX(.95f).scaleY(.95f).setDuration(110).start();
            if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL)
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Anim.spring()).start();
            return super.onTouchEvent(e);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(drawn!=state){drawn=state;changed=System.currentTimeMillis();}
            float d=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
            boolean on=state==WraithService.CONNECTED,loading=state==WraithService.CONNECTING||state==WraithService.STOPPING||pinging;
            long now=System.currentTimeMillis();
            float settle=Math.min(1f,(now-changed)/420f),eased=1f-(1f-settle)*(1f-settle);
            float base=d*.42f;
            p.setShader(null);
            if(on){
                for(int i=0;i<2;i++){
                    float phase=((now+i*1250L)%2500L)/2500f;
                    p.setStyle(Paint.Style.FILL);p.setColor(withAlpha(MINT,.16f*(1f-phase)*eased));
                    c.drawCircle(cx,cy,base*(1f+phase*.18f),p);
                }
            }
            float radius=base*(0.95f+0.05f*eased);
            p.setStyle(Paint.Style.FILL);p.setColor(on?MINT:loading?SECONDARY_CONTAINER:SURFACE_HIGHEST);
            c.drawCircle(cx,cy,radius,p);
            int glyph=on?ON_PRIMARY:loading?ON_SECONDARY_CONTAINER:MUTED;
            p.setStyle(Paint.Style.STROKE);p.setColor(glyph);p.setStrokeWidth(dp(getContext(),3.5f));p.setStrokeCap(Paint.Cap.ROUND);
            float r=d*.12f;
            c.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-50,280,false,p);
            c.drawLine(cx,cy-r*1.45f,cx,cy-r*.15f,p);
            if(loading){
                p.setStrokeWidth(dp(getContext(),4));p.setColor(MINT);
                float angle=(now%1300)/1300f*360f,sweep=50+60*(float)Math.abs(Math.sin(now/650.0)),ring=base+dp(getContext(),8);
                c.drawArc(new RectF(cx-ring,cy-ring,cx+ring,cy+ring),angle,sweep,false,p);
            }
            if(loading||on||settle<1f)postInvalidateDelayed(32);
        }
    }
}

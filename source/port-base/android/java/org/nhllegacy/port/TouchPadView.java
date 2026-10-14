package org.nhllegacy.port;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/** The DOAXBV pad layout, adapted to Xbox 360 button bits and shoulder buttons. */
final class TouchPadView extends View {
    private static final class Control {
        final String label;
        final float x, y, radius;
        final int buttons, stick, trigger;
        Control(String label, float x, float y, float radius, int buttons, int stick, int trigger) {
            this.label=label; this.x=x; this.y=y; this.radius=radius;
            this.buttons=buttons; this.stick=stick; this.trigger=trigger;
        }
    }
    private static final Control[] CONTROLS = {
        new Control("L", .12f, .69f, .080f, 0, 1, 0),
        new Control("R", .73f, .78f, .065f, 0, 2, 0),
        new Control("A", .89f, .79f, .037f, 0x1000, 0, 0),
        new Control("B", .96f, .65f, .037f, 0x2000, 0, 0),
        new Control("X", .82f, .65f, .037f, 0x4000, 0, 0),
        new Control("Y", .89f, .51f, .037f, 0x8000, 0, 0),
        new Control("LB", .18f, .18f, .037f, 0x100, 0, 0),
        new Control("RB", .82f, .18f, .037f, 0x200, 0, 0),
        new Control("LT", .08f, .18f, .040f, 0, 0, 1),
        new Control("RT", .92f, .18f, .040f, 0, 0, 2),
        new Control("L3", .44f, .18f, .032f, 0x40, 0, 0),
        new Control("R3", .56f, .18f, .032f, 0x80, 0, 0),
        new Control("START", .56f, .92f, .032f, 0x10, 0, 0),
        new Control("BACK", .44f, .92f, .032f, 0x20, 0, 0),
        new Control("↑", .27f, .53f, .026f, 1, 0, 0),
        new Control("↓", .27f, .79f, .026f, 2, 0, 0),
        new Control("←", .22f, .66f, .026f, 4, 0, 0),
        new Control("→", .32f, .66f, .026f, 8, 0, 0),
        new Control("МЕНЮ", .50f, .07f, .028f, 0, 0, -1)
    };
    private static final class Finger {
        final Control control;
        float x,y;
        Finger(Control control,float x,float y) { this.control=control; this.x=x; this.y=y; }
    }
    private final GameActivity activity;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer,Finger> fingers=new HashMap<>();
    private boolean connected;
    TouchPadView(GameActivity activity) {
        super(activity); this.activity=activity; setFocusable(false);
        // Keep the overlay on Android's hardware Canvas; no full-screen software bitmap.
    }
    private float radius(Control c) { return c.radius*Math.min(getWidth(), getHeight()*1.6f); }
    void setConnected(boolean enabled) { connected=enabled; clear(); }
    void clear() { fingers.clear(); send(); }
    private void send() {
        int buttons=0; float lx=0,ly=0,rx=0,ry=0,lt=0,rt=0;
        for (Finger f:fingers.values()) {
            Control c=f.control; buttons|=c.buttons;
            if(c.trigger==1) lt=1; if(c.trigger==2) rt=1;
            if(c.stick!=0) {
                float x=(f.x-c.x*getWidth())/radius(c), y=(c.y*getHeight()-f.y)/radius(c);
                float length=(float)Math.sqrt(x*x+y*y);
                if(length>1) { x/=length; y/=length; }
                if(c.stick==1) { lx=x; ly=y; } else { rx=x; ry=y; }
            }
        }
        GameActivity.nativeTouchPad(buttons,lx,ly,rx,ry,lt,rt,connected);
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked(), index=event.getActionIndex();
        if(action==MotionEvent.ACTION_CANCEL) { clear(); return true; }
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            float x=event.getX(index),y=event.getY(index);
            for(Control c:CONTROLS) {
                float dx=x-c.x*getWidth(),dy=y-c.y*getHeight(),r=radius(c);
                if(dx*dx+dy*dy>r*r) continue;
                if(c.trigger==-1) { clear(); activity.showGameMenu(); return true; }
                boolean used=false;
                for(Finger f:fingers.values()) if(f.control==c) used=true;
                if(!used) fingers.put(event.getPointerId(index),new Finger(c,x,y));
                break;
            }
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
            fingers.remove(event.getPointerId(index));
        for(int i=0;i<event.getPointerCount();++i) {
            Finger f=fingers.get(event.getPointerId(i));
            if(f!=null) { f.x=event.getX(i); f.y=event.getY(i); }
        }
        send(); return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); paint.setTextAlign(Paint.Align.CENTER);
        for(Control c:CONTROLS) {
            boolean held=false;
            for(Finger f:fingers.values()) if(f.control==c) held=true;
            float x=c.x*getWidth(),y=c.y*getHeight(),r=radius(c);
            paint.setStyle(Paint.Style.FILL); paint.setColor(held?0xAA4BB4EE:0x55666666);
            canvas.drawCircle(x,y,r,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xAADDFFFF);
            canvas.drawCircle(x,y,r,paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(r*(c.label.length()>2?.38f:.7f));
            canvas.drawText(c.label,x,y-(paint.ascent()+paint.descent())/2,paint);
        }
        for(Finger f:fingers.values()) if(f.control.stick!=0) {
            float x=f.control.x*getWidth(),y=f.control.y*getHeight(),r=radius(f.control);
            float dx=f.x-x,dy=f.y-y,length=(float)Math.sqrt(dx*dx+dy*dy);
            if(length>r) { dx*=r/length; dy*=r/length; }
            paint.setColor(0xAA4BB4EE); canvas.drawCircle(x+dx,y+dy,r*.35f,paint);
        }
    }
}

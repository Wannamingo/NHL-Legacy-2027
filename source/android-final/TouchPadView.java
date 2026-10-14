package org.nhllegacy.port;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/** Transparent outlined DOA3-style controller mapped to NHL's Xbox input bridge. */
final class TouchPadView extends View {
    static final String[] ACTIONS={"A","B","X","Y","LB","RB","LT","RT","L3","R3",
        "START","BACK","↑","↓","←","→"};
    static final String[] IDS={"left-stick","right-stick","a","b","x","y","lb","rb",
        "lt","rt","l3","r3","start","select","up","down","left","right"};
    private static final int[] BITS={0x1000,0x2000,0x4000,0x8000,0x100,0x200,0,0,
        0x40,0x80,0x10,0x20,1,2,4,8};
    private static final float[] DX={.12f,.73f,.89f,.96f,.82f,.89f,.18f,.82f,.08f,.92f,
        .44f,.56f,.56f,.44f,.27f,.27f,.22f,.32f};
    private static final float[] DY={.69f,.78f,.79f,.65f,.65f,.51f,.18f,.18f,.18f,.18f,
        .18f,.18f,.92f,.92f,.53f,.79f,.66f,.66f};
    private static final float[] R={.080f,.065f,.037f,.037f,.037f,.037f,.037f,.037f,
        .040f,.040f,.032f,.032f,.032f,.032f,.026f,.026f,.026f,.026f};
    private static final int[] DA={-1,-1,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    private final GameActivity activity;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Map<Integer,Finger> fingers=new HashMap<>();
    private final float[] x=DX.clone(),y=DY.clone();
    private final int[] bindings=DA.clone();
    private boolean connected,controlsVisible=true,editing;
    private int drag=-1,dragPointer=-1;
    private float dragDx,dragDy;
    private float[] beforeX,beforeY;
    private static final class Finger {
        final int index;float x,y;
        Finger(int index,float x,float y){this.index=index;this.x=x;this.y=y;}
    }
    TouchPadView(GameActivity activity) {
        super(activity);this.activity=activity;setFocusable(false);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        android.content.SharedPreferences prefs=activity.getSharedPreferences("nhl_touch_layout162",Context.MODE_PRIVATE);
        for(int i=0;i<IDS.length;i++){
            float sx=prefs.getFloat(IDS[i]+"_x",DX[i]),sy=prefs.getFloat(IDS[i]+"_y",DY[i]);
            if(Float.isFinite(sx)&&Float.isFinite(sy)&&sx>=.02f&&sx<=.98f&&sy>=.02f&&sy<=.98f){x[i]=sx;y[i]=sy;}
            int action=prefs.getInt(IDS[i]+"_action",DA[i]);
            if(i>=2&&action>=0&&action<ACTIONS.length)bindings[i]=action;
        }
    }
    void setConnected(boolean value){connected=value;clear();}
    void setControlsVisible(boolean value){controlsVisible=value;clear();invalidate();}
    void clear(){fingers.clear();send();}
    int[] bindings(){return bindings.clone();}
    float[] positionsX(){return x.clone();}
    float[] positionsY(){return y.clone();}
    void setBindings(int[] values){
        if(values==null||values.length!=bindings.length)return;
        for(int i=2;i<bindings.length;i++)if(values[i]>=0&&values[i]<ACTIONS.length)bindings[i]=values[i];
        clear();
    }
    void setPositions(float[] nx,float[] ny){
        if(nx==null||ny==null||nx.length!=x.length||ny.length!=y.length)return;
        for(int i=0;i<x.length;i++)if(Float.isFinite(nx[i])&&Float.isFinite(ny[i])){
            x[i]=Math.max(.02f,Math.min(.98f,nx[i]));y[i]=Math.max(.02f,Math.min(.98f,ny[i]));
        }
        clear();
    }
    void persist(){
        android.content.SharedPreferences.Editor edit=activity.getSharedPreferences(
            "nhl_touch_layout162",Context.MODE_PRIVATE).edit();
        for(int i=0;i<IDS.length;i++){
            edit.putFloat(IDS[i]+"_x",x[i]).putFloat(IDS[i]+"_y",y[i]);
            if(i>=2)edit.putInt(IDS[i]+"_action",bindings[i]);
        }
        edit.apply();
    }
    boolean isEditing(){return editing;}
    void beginEditing(){if(editing)return;clear();beforeX=x.clone();beforeY=y.clone();editing=true;drag=-1;invalidate();}
    void finishEditing(boolean save){
        if(!editing)return;
        if(!save)setPositions(beforeX,beforeY);
        editing=false;drag=-1;clear();activity.onTouchLayoutEdited(save);
    }
    private float unit(){return Math.min(getWidth(),getHeight()*1.6f);}
    private float radius(int i){return R[i]*unit();}
    private float gearX(){return activity.surfaceRight()-.10f*unit();}
    private float gearY(){return .043f*getHeight();}
    private float gearRadius(){return Math.max(12f,.013f*unit());}
    private int hit(float px,float py){
        for(int i=IDS.length-1;i>=0;i--){
            float dx=px-x[i]*getWidth(),dy=py-y[i]*getHeight(),r=radius(i)*1.12f;
            if(dx*dx+dy*dy<=r*r)return i;
        }
        return -1;
    }
    private void send(){
        int buttons=0;float lx=0,ly=0,rx=0,ry=0,lt=0,rt=0;
        for(Finger f:fingers.values()){
            int index=f.index;
            if(index<2){
                float vx=(f.x-x[index]*getWidth())/radius(index);
                float vy=(y[index]*getHeight()-f.y)/radius(index);
                float length=(float)Math.sqrt(vx*vx+vy*vy);
                if(length>1){vx/=length;vy/=length;}
                if(index==0){lx=vx;ly=vy;}else{rx=vx;ry=vy;}
            }else{
                int action=bindings[index];buttons|=BITS[action];
                if(action==6)lt=1;if(action==7)rt=1;
            }
        }
        GameActivity.nativeTouchPad(buttons,lx,ly,rx,ry,lt,rt,connected);invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked(),pointer=event.getActionIndex();
        if(editing)return editTouch(event);
        if(action==MotionEvent.ACTION_DOWN){
            float dx=event.getX()-gearX(),dy=event.getY()-gearY();
            if(dx*dx+dy*dy<gearRadius()*gearRadius()*1.5f){clear();activity.openGameSettings();return true;}
        }
        if(!controlsVisible)return false;
        if(action==MotionEvent.ACTION_CANCEL){clear();return true;}
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int index=hit(event.getX(pointer),event.getY(pointer));
            if(index==13){clear();activity.showGameMenu();return true;}
            if(index>=0){
                boolean used=false;for(Finger finger:fingers.values())if(finger.index==index)used=true;
                if(!used)fingers.put(event.getPointerId(pointer),new Finger(index,event.getX(pointer),event.getY(pointer)));
            }
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)
            fingers.remove(event.getPointerId(pointer));
        for(int i=0;i<event.getPointerCount();i++){
            Finger finger=fingers.get(event.getPointerId(i));
            if(finger!=null){finger.x=event.getX(i);finger.y=event.getY(i);}
        }
        send();return true;
    }
    private boolean editTouch(MotionEvent event){
        int action=event.getActionMasked(),pointer=event.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN){
            float px=event.getX(),py=event.getY();
            if(py<getHeight()*.16f){
                if(px<getWidth()/3f)finishEditing(true);
                else if(px>getWidth()*2f/3f)finishEditing(false);
                else{System.arraycopy(DX,0,x,0,x.length);System.arraycopy(DY,0,y,0,y.length);invalidate();}
                return true;
            }
            drag=hit(px,py);dragPointer=event.getPointerId(0);
            if(drag>=0){dragDx=x[drag]*getWidth()-px;dragDy=y[drag]*getHeight()-py;}
        }
        if(action==MotionEvent.ACTION_MOVE&&drag>=0){
            int p=event.findPointerIndex(dragPointer);
            if(p>=0){x[drag]=Math.max(.03f,Math.min(.97f,(event.getX(p)+dragDx)/getWidth()));
                y[drag]=Math.max(.17f,Math.min(.97f,(event.getY(p)+dragDy)/getHeight()));invalidate();}
        }
        if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)
            if(action==MotionEvent.ACTION_CANCEL||event.getPointerId(pointer)==dragPointer){drag=-1;dragPointer=-1;}
        return true;
    }
    private void style(int color,Paint.Style type,float width){paint.setColor(color);paint.setStyle(type);paint.setStrokeWidth(width);}
    private void label(Canvas canvas,String value,float cx,float cy,float size,int color){
        style(color,Paint.Style.FILL,1);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size);
        canvas.drawText(value,cx,cy-(paint.ascent()+paint.descent())/2,paint);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float u=unit(),stroke=Math.max(2,u*.0022f);
        if(editing){style(0x50000000,Paint.Style.FILL,1);canvas.drawRect(0,0,getWidth(),getHeight(),paint);}
        if(controlsVisible||editing)for(int i=0;i<IDS.length;i++){
            float cx=x[i]*getWidth(),cy=y[i]*getHeight(),r=radius(i);
            boolean held=false;Finger heldFinger=null;
            for(Finger f:fingers.values())if(f.index==i){held=true;heldFinger=f;}
            int color=i>=2&&i<=5?new int[]{0xdd73df9b,0xddeb7777,0xdd7db6ff,0xdde6d277}[i-2]:0x90ffffff;
            if(held||editing&&drag==i){style(0x30ffffff,Paint.Style.FILL,1);canvas.drawCircle(cx,cy,r,paint);}
            style(held?0xffffffff:color,Paint.Style.STROKE,stroke*(i>=2&&i<=5?1.5f:1));
            if(i>=6&&i<=13){rect.set(cx-r,cy-r*.65f,cx+r,cy+r*.65f);canvas.drawRoundRect(rect,r*.2f,r*.2f,paint);}
            else canvas.drawCircle(cx,cy,r,paint);
            String name=i==0?"L":i==1?"R":i==13?"SELECT":ACTIONS[bindings[i]];
            label(canvas,name,cx,cy,Math.min(r*.82f,r*3f/name.length()),held?0xffffffff:color);
            if(i<2){float ox=0,oy=0;
                if(heldFinger!=null){ox=Math.max(-r,Math.min(r,heldFinger.x-cx));oy=Math.max(-r,Math.min(r,heldFinger.y-cy));}
                style(0x90ffffff,Paint.Style.STROKE,stroke);canvas.drawCircle(cx+ox*.65f,cy+oy*.65f,r*.38f,paint);
            }
            if(editing){style(i==drag?0xffffd49b:0x77ffd49b,Paint.Style.STROKE,stroke);canvas.drawCircle(cx,cy,r+6,paint);}
        }
        if(!editing){
            float gx=gearX(),gy=gearY(),gr=gearRadius();
            style(0x77000000,Paint.Style.FILL,1);canvas.drawCircle(gx,gy,gr,paint);
            style(0x90ffffff,Paint.Style.STROKE,stroke);canvas.drawCircle(gx,gy,gr,paint);
            label(canvas,"⚙",gx,gy,gr*1.12f,0xffffffff);
        }else{
            float h=getHeight()*.13f;
            style(0xe1212935,Paint.Style.FILL,1);canvas.drawRoundRect(getWidth()*.15f,h*.15f,getWidth()*.85f,h,14,14,paint);
            label(canvas,"Сохранить",getWidth()*.24f,h*.58f,u*.020f,0xffffffff);
            label(canvas,"Сброс",getWidth()*.50f,h*.58f,u*.020f,0xffffffff);
            label(canvas,"Отмена",getWidth()*.76f,h*.58f,u*.020f,0xffffffff);
            label(canvas,"Перетащите кнопки · Игра приостановлена",getWidth()*.5f,h+u*.025f,u*.019f,0xffffffff);
        }
    }
}

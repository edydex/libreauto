// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.Locale;

/** Small, lazy touch keyboard for an app-owned projected text field.
 * Keys edit local text only; only the explicit Find action calls the owner.
 * No IME, network, clipboard, gesture injection, history or third-party dependency. */
public final class CarTextKeyboard extends LinearLayout {
    public interface Listener { void onFind(); void onClose(); }
    public static final int MAX_CHARACTERS=200;
    private final EditText target;
    private final Listener listener;
    private boolean numbers,upper;
    private int cornerWidth,cornerHeight;
    public CarTextKeyboard(Context context,EditText target,Listener listener) {
        super(context);this.target=target;this.listener=listener;
        setOrientation(VERTICAL);setPadding(dp(4),0,dp(4),0);
        setMinimumHeight(dp(192));
        setBackground(CarUi.rounded(context,CarUi.SURFACE,16));
        rebuild();
    }
    /** Reserve just the lower-start corner for an overlaid microphone, not a whole side rail. */
    public void setFloatingControlClearance(int widthDp,int heightDp) {
        cornerWidth=dp(Math.max(0,widthDp));cornerHeight=dp(Math.max(0,heightDp));
        applyCornerClearance();
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        super.onLayout(changed,left,top,right,bottom);
        applyCornerClearance();
    }
    private void applyCornerClearance() {
        for(int index=0;index<getChildCount();index++) {
            LinearLayout row=(LinearLayout)getChildAt(index);
            int inset=cornerHeight>0&&row.getBottom()>getHeight()-cornerHeight?cornerWidth:0;
            if(row.getPaddingLeft()!=inset)row.setPadding(inset,0,0,0);
        }
    }
    private void rebuild() {
        removeAllViews();
        if(numbers) {
            keys(new String[]{"1","2","3","4","5","6","7","8","9","0"});
            keys(new String[]{"-","/",":",";","(",")","$","&","@","\""});
            keys(new String[]{".",",","?","!","'","#","+","=","_","Delete"});
        } else {
            letters("qwertyuiop");letters("asdfghjkl");
            LinearLayout row=new LinearLayout(getContext());
            row.addView(key(upper?"abc":"ABC",()->{upper=!upper;rebuild();}),cell(1.2f));
            for(char value:"zxcvbnm".toCharArray()) {
                String text=Character.toString(value);String shown=upper?text.toUpperCase(Locale.US):text;
                row.addView(key(shown,()->insert(shown)),cell(1));
            }
            row.addView(key("Delete",this::delete),cell(1.4f));addView(row,rowParams());
        }
        LinearLayout actions=new LinearLayout(getContext());
        actions.addView(key(numbers?"ABC":"123",()->{numbers=!numbers;rebuild();}),cell(1));
        actions.addView(key("Space",()->insert(" ")),cell(2));
        actions.addView(key("Clear",()->{if(target.isEnabled())target.getText().clear();}),cell(1));
        actions.addView(key("Close",listener::onClose),cell(1));
        actions.addView(key("Find",()->{if(target.isEnabled())listener.onFind();}),cell(1.2f));
        addView(actions,rowParams());
        applyCornerClearance();
    }
    private void letters(String text) {
        String[] keys=new String[text.length()];
        for(int i=0;i<text.length();i++)keys[i]=upper?text.substring(i,i+1).toUpperCase(Locale.US):text.substring(i,i+1);
        keys(keys);
    }
    private void keys(String[] values) {
        LinearLayout row=new LinearLayout(getContext());
        for(String value:values)row.addView(key(value,"Delete".equals(value)?this::delete:()->insert(value)),cell("Delete".equals(value)?1.4f:1));
        addView(row,rowParams());
    }
    private void insert(String value) {
        if(!target.isEnabled())return;
        Editable text=target.getText();int[] selection=selection(text);
        int available=MAX_CHARACTERS-(text.length()-(selection[1]-selection[0]));
        if(available<=0)return;
        String inserted=value.substring(0,Math.min(value.length(),available));
        text.replace(selection[0],selection[1],inserted);
        target.setSelection(Math.min(selection[0]+inserted.length(),text.length()));
    }
    private void delete() {
        if(!target.isEnabled())return;
        Editable text=target.getText();int[] selection=selection(text);
        if(selection[0]==selection[1]&&selection[0]>0)selection[0]=Character.offsetByCodePoints(text,selection[0],-1);
        if(selection[0]<selection[1])text.delete(selection[0],selection[1]);
        target.setSelection(Math.min(selection[0],text.length()));
    }
    private int[] selection(Editable text) {
        int first=target.getSelectionStart(),last=target.getSelectionEnd();
        if(first<0||last<0)first=last=text.length();
        return new int[]{Math.min(Math.min(first,last),text.length()),Math.min(Math.max(first,last),text.length())};
    }
    private Button key(String text,Runnable action) {
        Button button=CarUi.button(getContext(),"Delete".equals(text)?"Del":text,"Find".equals(text));
        button.setContentDescription(text);
        button.setTextSize(text.length()>2?14:20);
        button.setMinWidth(dp(48));button.setMinimumWidth(dp(48));button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));
        button.setPadding(dp(1),0,dp(1),0);button.setFocusable(false);
        button.setOnClickListener(view->action.run());return button;
    }
    private LayoutParams cell(float weight) { LayoutParams result=new LayoutParams(0,LayoutParams.MATCH_PARENT,weight);result.setMargins(dp(1),0,dp(1),0);return result; }
    private LayoutParams rowParams() { return new LayoutParams(LayoutParams.MATCH_PARENT,0,1); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}

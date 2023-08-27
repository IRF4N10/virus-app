package com.example.autocamera2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Customized view that allows user to draw on it
 */
public class DragRectView extends View {
    private Paint mRectPaint;
    private int mStartX = 0;
    private int mStartY = 0;
    private int mEndX = 0;
    private int mEndY = 0;
    private boolean mDrawRect = false;
    private TextPaint mTextPaint = null;
    private OnUpCallback mCallback = null;

    /**
     * Callback interface
     */
    public interface OnUpCallback {
        void onRectFinished(Rect rect);
    }

    public DragRectView(final Context context) {
        super(context);
        init();
    }

    public DragRectView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DragRectView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    /**
     * Sets callback for up
     *
     * @param callback {@link OnUpCallback}
     */
    public void setOnUpCallback(OnUpCallback callback) {
        mCallback = callback;
    }

    /**
     * Inits internal data
     */
    private void init() {
        mRectPaint = new Paint();
        mRectPaint.setColor(ContextCompat.getColor(getContext(), android.R.color.holo_green_light));
        mRectPaint.setStyle(Paint.Style.STROKE);
        mRectPaint.setStrokeWidth(5);

        mTextPaint = new TextPaint();
        mTextPaint.setColor(ContextCompat.getColor(getContext(), android.R.color.holo_green_light));
        mTextPaint.setTextSize(20);
    }

    /**
     * Reacts to user touch event
     * @param event user touch event
     * @return always return true
     */
    @Override
    public boolean onTouchEvent(final MotionEvent event) {

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDrawRect = false;
                mStartX = (int) event.getX();
                mStartY = (int) event.getY();
                invalidate();
                break;

            case MotionEvent.ACTION_MOVE:
                int x = (int) event.getX();
                int y = (int) event.getY();
                if (!mDrawRect || Math.abs(x - mEndX) > 5 || Math.abs(y - mEndY) > 5) {
                    mEndX = x;
                    mEndY = y;
                    invalidate();
                }
                mDrawRect = true;
                break;

            case MotionEvent.ACTION_UP:
                if (mCallback != null) {
                    x = (int) event.getX();
                    y = (int) event.getY();
                    if (Math.abs(x - mStartX) < 5 || Math.abs(y - mStartY) < 5) {
                        //Resets all coordinates if selected area is too small or just click
                        mStartX = 0;
                        mStartY = 0;
                        mEndY = 0;
                        mEndX = 0;
                        mCallback.onRectFinished(null);
                    } else {
                        mCallback.onRectFinished(new Rect(Math.max(Math.min(mStartX, mEndX), 0), Math.max(Math.min(mStartY, mEndY), 0),
                                Math.min(Math.max(mEndX, mStartX), getWidth()), Math.min(Math.max(mStartY, mEndY), getHeight())));
                    }
                }
                invalidate();
                break;

            default:
                break;
        }

        return true;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        if (mDrawRect) {
            canvas.drawRect(Math.min(mStartX, mEndX), Math.min(mStartY, mEndY),
                    Math.max(mEndX, mStartX), Math.max(mEndY, mStartY), mRectPaint);
            canvas.drawText("  (" + Math.abs(mStartX - mEndX) + ", " + Math.abs(mStartY - mEndY) + ")",
                    Math.max(mEndX, mStartX), Math.max(mEndY, mStartY), mTextPaint);
        }
    }
}
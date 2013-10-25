/*
  Copyright (c) 2009 Bonifaz Kaufmann. 
  
  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public
  License along with this library; if not, write to the Free Software
  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/
// Heavily modified by Alex Bigazzi, Sept. 2012
package edu.pdx.its.portlandace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class GraphView extends View {

	private Bitmap  mBitmap;
	private Paint   mPaint = new Paint();
    private Canvas  mCanvas = new Canvas();
    
	private float   mSpeed = 3.0f;
	private float   mLastX;
    private float   mScale;
    private float   mLastValue;
    private float   mYOffset;
    private int     mColor;
    private float   mWidth;
    private float   maxValue = 0f;
    private float 	minValue = 0f; 
    
    private boolean resetGraph = true;
    
    public GraphView(Context context) {
        super(context);
        init();
    }
    
    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init(){
    	mColor = 0xFF03C03C;
        mPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
    }
    
    public void addDataPoint(float value){
        final Paint paint = mPaint;
        if(value>maxValue){
        	resetValues(value, 1);	// set a new ceiling
        } else if(value<minValue){
        	resetValues(value, 0);	// set a new floor
        } else {
            float newX = mLastX + mSpeed;
        	final float v = mYOffset + (value-minValue) * mScale;
	        paint.setColor(mColor);
	        if(mLastX != 0){
	        	mCanvas.drawLine(mLastX, mLastValue, newX, v, paint);
	        } else {
	        	mCanvas.drawLine(mLastX, v, newX, v, paint);
	        }
	        mLastValue = v;
	        mLastX += mSpeed;
        }
 		invalidate();
    }
    
    public void resetValues(float seed, int fcb){
    	resetGraph = true; 
    	if(fcb == 0) minValue = seed*2;
    	if(fcb == 1) maxValue = seed*2;
    	if(fcb == 2) {
    		if (seed>=0){ 
    			maxValue = seed*2;
    			minValue = 0f;
    		}	else {
    			maxValue = 0f;
    			minValue = seed*2;
    		}
    	}
    	mScale = - (mYOffset * (1.0f / (maxValue-minValue)));
    	mLastX = 0;
    }
    
    public void setSpeed(float speed){
    	mSpeed = speed;
    }
        
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        mCanvas.setBitmap(mBitmap);
        mCanvas.drawColor(0xFF654321);
        mYOffset = h;
        mScale = - (mYOffset * (1.0f / (maxValue-minValue)));
        mWidth = w;
        mLastX = mWidth;
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (this) {
            if (mBitmap != null) {
                if (mLastX >= mWidth | resetGraph) {
                    mLastX = 0;
                    final Canvas cavas = mCanvas;
                    cavas.drawColor(0xFF654321);
                    mPaint.setColor(0xFFFFFFFF);
                    cavas.drawLine(0, 0, mWidth, 0, mPaint);
                    cavas.drawLine(0, mYOffset, mWidth, mYOffset, mPaint);
                    cavas.drawText(String.valueOf(maxValue), 5f, 15f, mPaint);  // add max/min values
                    cavas.drawText(String.valueOf(minValue), 5f, mYOffset-5f, mPaint);
                    resetGraph = false;
                }
                canvas.drawBitmap(mBitmap, 0, 0, null);
            }
        } 
    }
}

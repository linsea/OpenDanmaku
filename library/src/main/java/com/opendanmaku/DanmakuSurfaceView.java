package com.opendanmaku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * DO NOT USE THIS VIEW!!!
 * AS THE KNOWN ISSUE ABOUT SurfaceView ON android 4.3: https://code.google.com/p/android/issues/detail?id=58385
 * 弹幕View
 */
public class DanmakuSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    public static final String TAG = "DanmakuSurfaceView";

    public static final int REFRESH_DELAY = 10;//弹幕动画每10ms刷新一次,也即系统默认的刷新频率.
    public static final int PICK_ITEM_DELAY = 1000;//每隔多长时间取出一条弹幕来播放.

    private HashMap<Integer,ArrayList<IDanmakuItem>> mChannelMap;
    private final java.util.Deque<IDanmakuItem> mWaitingItems = new LinkedList<>();

    private int mMaxRow = 6; //最多几条弹道
    private int mMaxRunning = 3; //每条弹道上最多同时有几个弹幕在屏幕上运行
    private int[] mChannelY; //每条弹道的Y坐标
    private static final float mPartition = 0.6f; //仅View顶部的1/3部分可以播放弹幕
    private SchedulerThread mWorkerThread;
    private SurfaceHolder mSurfaceHolder;

    private static final int STATUS_RUNNING = 1;
    private static final int STATUS_PAUSE = 2;
    private static final int STATUS_STOP = 3;

    private static Random random = new Random();

    private boolean showFPS = true;
    LinkedList<Long> times;
    private Paint fpsPaint;
    private final int MAX_SIZE = 100;
    private final double NANOS = 1000000000.0;

    class SchedulerThread extends Thread {

        private int status;

        public SchedulerThread(String threadName) {
            super(threadName);
        }

        public void setRunning() {
            this.status = STATUS_RUNNING;
        }

        public void setPause() {
            this.status = STATUS_PAUSE;
        }

        public void setStop() {
            this.status = STATUS_STOP;
        }


        @Override
        public void run() {
            long previousTime = 0;
            while (true) {
                while (status != STATUS_STOP) {
                    if (status == STATUS_RUNNING) {
                        Canvas c = null;
                        try {
                            c = mSurfaceHolder.lockCanvas(null);
                            synchronized (mSurfaceHolder) {
                                if (c == null) {
                                    continue;
                                }
                                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                                //先绘制正在播放的弹幕
                                for (int i = 0; i < mChannelMap.size(); i++) {
                                    ArrayList<IDanmakuItem> list = mChannelMap.get(i);
                                    for (int j = 0; j < list.size(); j++) {
                                        IDanmakuItem item = list.get(j);
                                        if (item.isOut()) {
                                            list.remove(item);
                                            item.release();
                                            continue;
                                        }
                                        item.doDraw(c);
                                    }
                                }

                                //检查是否需要加载播放下一个弹幕
                                if (System.currentTimeMillis() - previousTime > PICK_ITEM_DELAY) {
                                    previousTime = System.currentTimeMillis();
                                    IDanmakuItem di = mWaitingItems.pollFirst();
                                    if (di != null) {
                                        int indexY = findVacant(di);

                                        if (indexY >= 0) {
                                            di.setStartPosition(c.getWidth() - 2, mChannelY[indexY]);
                                            di.doDraw(c);
                                            mChannelMap.get(indexY).add(di);//不要忘记加入正运行的维护的列表中

                                        } else {
                                            addIDanmakuItemToHead(di);//找不到可以播放的弹道,则把它放回列表中
                                        }
                                    }

                                }


                                if (showFPS) {
                                    int fps = (int) fps();
                                    c.drawText("FPS:" + fps, 5f, 20f, fpsPaint);
                                }


                            }
                        } finally {
                            if (c != null) {
                                try {
                                    mSurfaceHolder.unlockCanvasAndPost(c);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    } else {//暂停,隐藏弹幕内容
                        clearCanvas();
                        try {
                            Thread.sleep(Integer.MAX_VALUE);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                    }

                    try {
                        Thread.sleep(REFRESH_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                if (status == STATUS_STOP) {
                    clearCanvas();
                    break;
                }
            }

        }

        /**随机寻找一个可以播放弹幕而不会发生碰撞的弹道,返回弹道的Y坐标在mChannelY上的index,如果没有找到则返回-1*/
        private int findVacant(IDanmakuItem item) {
            try {//fix NPT exception
                for (int i = 0; i < mMaxRow; i++) {
                    ArrayList<IDanmakuItem> list = mChannelMap.get(i);
                    if (list.size() == 0) {
                        return i;
                    }
                }
                int ind = random.nextInt(mMaxRow);
                for (int i = 0; i < mMaxRow; i++) {
                    ArrayList<IDanmakuItem> list = mChannelMap.get((i + ind) % mMaxRow);
                    for (int j = 0, jj = list.size(); j < jj; j++) {
                        IDanmakuItem di = list.get(jj - 1);
                        if (!item.willHit(di)) {
                            return (i + ind) % mMaxRow;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return -1;
        }

    }


    public DanmakuSurfaceView(Context context) {
        this(context, null);
    }

    public DanmakuSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DanmakuSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }


    private void init() {
        setZOrderMediaOverlay(true);
        setZOrderOnTop(true);
        setWillNotCacheDrawing(true);
        setDrawingCacheEnabled(false);
        setWillNotDraw(true);
        mSurfaceHolder = getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);
        initChannelMap();
        initChannelY();
        if (showFPS) {
            fpsPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            fpsPaint.setColor(Color.YELLOW);
            fpsPaint.setTextSize(20);
            times = new LinkedList<Long>();
        }
    }

    private void initChannelMap(){
        mChannelMap = new HashMap<>(mMaxRow);
        for (int i = 0; i < mMaxRow; i++) {
            java.util.ArrayList<IDanmakuItem> runningRow= new ArrayList<IDanmakuItem>(mMaxRunning);
            mChannelMap.put(i, runningRow);
        }
    }

    private void initChannelY() {
        if (mChannelY == null){
            mChannelY = new int[mMaxRow];
        }

        float p = (getHeight() - getPaddingBottom() - getPaddingTop()) * mPartition / mMaxRow;
        for (int i = 0; i < mMaxRow; i++) {
            mChannelY[i] = (int) ((p * (i + 1)) + getPaddingTop() - p*3/4);
        }
    }

    private void clearCanvas() {
        Canvas c = null;
        try {
            c = mSurfaceHolder.lockCanvas(null);
            synchronized (mSurfaceHolder) {
                if (c != null) {
                    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                }
            }
        } finally {
            if (c != null) {
                mSurfaceHolder.unlockCanvasAndPost(c);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    /**在Activity.onStop()方法中调用,此时暂停播放并隐藏弹幕*/
    public void onStop() {
        pause();
    }

    /**在Activity.onRestart()方法中调用,此时恢复播放并显示弹幕*/
    public void onRestart() {
        open();
    }

    /**在Activity.onDestroy()方法中调用,此时恢复播放并显示弹幕*/
    public void onDestroy() {
        stop();
    }


    /**清空弹幕等待队列,暂停播放*/
    public void clearAndPause() {
        mWaitingItems.clear();
        if (mWorkerThread != null) {
            mWorkerThread.setPause();
        }
    }


    /**隐藏弹幕,暂停播放*/
    public void pause() {
        if (mWorkerThread != null) {
            mWorkerThread.setPause();
        }
    }

    /**显示弹幕,从暂停播放状态恢复播放*/
    public void open() {
        if (mWorkerThread != null && mWorkerThread.isAlive()) {
            try {
                mWorkerThread.setRunning();
                mWorkerThread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**终止播放弹幕,销毁所有弹幕Item(包括还没有来得及播放的)*/
    public void stop() {
//        boolean retry = true;
        if (mWorkerThread != null) {
            mWorkerThread.setStop();
            mWorkerThread.interrupt();
        }
//        while (retry) {
//            try {
//                mWorkerThread.join();
//                retry = false;
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
        if (mWaitingItems != null) mWaitingItems.clear();
        if (mChannelMap != null) mChannelMap.clear();
    }

    /**启动弹幕播放*/
    public void start() {
        if (mWorkerThread != null && !mWorkerThread.isAlive()) {
            try {
                mWorkerThread.setRunning();
                mWorkerThread.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mWorkerThread = new SchedulerThread("SchedulerThread");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        initChannelY();//可能屏幕方向切换了,得重新计算坐标
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stop();
    }


    public void setmMaxRow(int mMaxRow) {
        this.mMaxRow = mMaxRow;
    }

    public void setmMaxRunning(int mMaxRunning) {
        this.mMaxRunning = mMaxRunning;
    }

    public void addIDanmakuItem(IDanmakuItem item) {
        synchronized (mWaitingItems) {
            this.mWaitingItems.add(item);
        }
    }

    public void addIDanmakuItemToHead(IDanmakuItem item) {
        synchronized (mWaitingItems) {
            this.mWaitingItems.addFirst(item);
        }
    }

    /**是否新建后台线程来执行添加任务*/
    public void addIDanmakuItem(final List<IDanmakuItem> list, boolean background) {
        if (background) {
            new Thread(){
                @Override
                public void run() {
                    synchronized (mWaitingItems) {
                        mWaitingItems.addAll(list);
                    }
                }
            }.start();
        } else {
            synchronized (mWaitingItems) {
                this.mWaitingItems.addAll(list);
            }
        }
    }


    /** Calculates and returns frames per second */
    private double fps() {
        long lastTime = System.nanoTime();
        times.addLast(lastTime);
        double difference = (lastTime - times.getFirst()) / NANOS;
        int size = times.size();
        if (size > MAX_SIZE) {
            times.removeFirst();
        }
        return difference > 0 ? times.size() / difference : 0.0;
    }
}

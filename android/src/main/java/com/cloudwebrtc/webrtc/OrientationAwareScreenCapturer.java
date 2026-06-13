package com.cloudwebrtc.webrtc;

import org.webrtc.SurfaceTextureHelper;
import org.webrtc.CapturerObserver;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.projection.MediaProjection;
import android.view.Surface;
import android.view.WindowManager;
import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjectionManager;
import android.view.Display;
import android.util.Log;

/**
 * 基于 ScreenCapturerAndroid 的拷贝，在捕获屏幕内容的同时感知设备方向变化并自动调整捕获分辨率。
 */
@TargetApi(21)
public class OrientationAwareScreenCapturer implements VideoCapturer, VideoSink {
    static final String TAG = FlutterWebRTCPlugin.TAG;

    // 创建 VirtualDisplay 实例时使用的标志位
    private static final int DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    // VirtualDisplay 的 DPI 设置，对实际效果影响不大
    private static final int VIRTUAL_DISPLAY_DPI = 400;

    // MediaProjection 权限请求的结果数据，用于获取 MediaProjection 实例
    private final Intent mediaProjectionPermissionResultData;

    // MediaProjection 事件回调，用于处理权限撤销等场景
    private final MediaProjection.Callback mediaProjectionCallback;

    // 当前捕获分辨率宽度
    private int width;

    // 当前捕获分辨率高度
    private int height;

    // 上一次捕获分辨率宽度，用于检测分辨率变化
    private int oldWidth;

    // 上一次捕获分辨率高度，用于检测分辨率变化
    private int oldHeight;

    // VirtualDisplay 实例，负责将屏幕内容渲染到目标 Surface
    private VirtualDisplay virtualDisplay;

    // 绑定 SurfaceTextureHelper 的 SurfaceTexture 所创建的 Surface，作为 VirtualDisplay 的输出目标
    private Surface virtualDisplaySurface;

    // 提供 GPU 加速的 SurfaceTexture 以生成视频帧的辅助类
    private SurfaceTextureHelper surfaceTextureHelper;

    // 捕获器观察者，接收帧数据及启动/停止通知
    private CapturerObserver capturerObserver;

    // 当前捕获会话期间累计捕获的帧数
    private long numCapturedFrames = 0;

    // MediaProjection 实例，用于屏幕捕获
    private MediaProjection mediaProjection;

    // 该捕获器是否已被释放
    private boolean isDisposed = false;

    // 管理 MediaProjection 实例的系统服务
    private MediaProjectionManager mediaProjectionManager;

    // 查询显示和方向信息的系统服务
    private WindowManager windowManager;

    // 当前设备是否处于竖屏方向
    private boolean isPortrait;

    // 当前捕获帧率
    private int frameRate;

    // 应用上下文，用于访问系统服务和资源
    private Context applicationContext;

    /**
     * 构造一个新的屏幕捕获器。
     *
     * @param mediaProjectionPermissionResultData MediaProjection 权限请求的结果数据；
     *                                            调用方必须确保结果码为 Activity.RESULT_OK
     * @param mediaProjectionCallback             MediaProjection 回调，用于处理用户撤销捕获权限等应用特定逻辑
     **/
    public OrientationAwareScreenCapturer(Intent mediaProjectionPermissionResultData,
                                          MediaProjection.Callback mediaProjectionCallback) {
        this.mediaProjectionPermissionResultData = mediaProjectionPermissionResultData;
        this.mediaProjectionCallback = mediaProjectionCallback;
    }

    /**
     * 当从 SurfaceTextureHelper 接收到新视频帧时回调。
     * 检测设备方向变化并相应调整捕获分辨率，随后将帧转发给捕获器观察者。
     *
     * @param frame 捕获到的视频帧
     */
    @Override
    public void onFrame(VideoFrame frame) {
        checkNotDisposed();

        final boolean nowPortrait = isDeviceOrientationPortrait();
        if (nowPortrait != this.isPortrait) {
            Log.d(TAG, "device orientation changed from " + this.isPortrait + " to " + nowPortrait);
            this.isPortrait = nowPortrait;
            final int max = Math.max(this.height, this.width);
            final int min = Math.min(this.height, this.width);
            if (nowPortrait) {
                changeCaptureFormat(min, max, frameRate);
            } else {
                changeCaptureFormat(max, min, frameRate);
            }
        }
        numCapturedFrames++;
        capturerObserver.onFrameCaptured(frame);
    }

    /**
     * 通过物理显示度量和系统配置综合判断设备当前是否处于竖屏方向。
     *
     * @return 如果设备处于竖屏方向返回 true，否则返回 false
     */
    private boolean isDeviceOrientationPortrait() {
        final Display display = windowManager.getDefaultDisplay();
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);

        final boolean isPortrait = applicationContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        return metrics.heightPixels > metrics.widthPixels && isPortrait;
    }


    /**
     * 校验该捕获器尚未被释放。
     *
     * @throws RuntimeException 如果捕获器已被释放
     */
    private void checkNotDisposed() {
        if (isDisposed) {
            throw new RuntimeException("capturer is disposed.");
        }
    }

    /**
     * 初始化捕获器所需的依赖项。必须在 {@link #startCapture(int, int, int)} 之前调用。
     *
     * @param surfaceTextureHelper 提供 GPU 加速 SurfaceTexture 以生成视频帧的辅助类
     * @param applicationContext   应用上下文，用于访问系统服务和资源
     * @param capturerObserver     捕获器观察者，接收帧数据及启动/停止通知
     * @throws RuntimeException 如果捕获器已被释放，或必需参数为 null
     */
    @Override
    public synchronized void initialize(final SurfaceTextureHelper surfaceTextureHelper,
                                        final Context applicationContext, final CapturerObserver capturerObserver) {
        checkNotDisposed();
        if (capturerObserver == null) {
            throw new RuntimeException("capturerObserver not set.");
        }
        this.capturerObserver = capturerObserver;
        if (surfaceTextureHelper == null) {
            throw new RuntimeException("surfaceTextureHelper not set.");
        }
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.applicationContext = applicationContext;

        this.windowManager = (WindowManager) applicationContext.getSystemService(
                Context.WINDOW_SERVICE);
        this.mediaProjectionManager = (MediaProjectionManager) applicationContext.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE);
    }

    /**
     * 开始捕获屏幕内容。获取 MediaProjection 实例，以指定分辨率创建 VirtualDisplay，
     * 并开始监听视频帧。重置已捕获帧计数器。
     *
     * @param width     期望的捕获宽度（像素）
     * @param height    期望的捕获高度（像素）
     * @param frameRate 期望的捕获帧率
     */
    @Override
    public synchronized void startCapture(
            final int width, final int height, final int frameRate) {
        // checkNotDisposed();

        this.isPortrait = isDeviceOrientationPortrait();

        this.width = width;
        this.height = height;
        this.frameRate = frameRate;

        this.oldWidth = this.width;
        this.oldHeight = this.height;

        numCapturedFrames = 0;
        mediaProjection = mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK, mediaProjectionPermissionResultData);

        // 让 MediaProjection 回调使用 SurfaceTextureHelper 的线程
        mediaProjection.registerCallback(mediaProjectionCallback, surfaceTextureHelper.getHandler());

        createVirtualDisplay();
        capturerObserver.onCapturerStarted(true);
        surfaceTextureHelper.startListening(this);
    }

    /**
     * 停止捕获屏幕内容。释放 VirtualDisplay 及其 Surface，注销 MediaProjection 回调，
     * 并停止 MediaProjection。重置已捕获帧计数器。
     * 清理操作在 SurfaceTextureHelper 线程上执行以确保线程安全。
     */
    @Override
    public synchronized void stopCapture() {
        checkNotDisposed();
        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.getHandler(), new Runnable() {
            @Override
            public void run() {
                surfaceTextureHelper.stopListening();
                capturerObserver.onCapturerStopped();
                numCapturedFrames = 0;
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }
                releaseVirtualDisplaySurface();
                if (mediaProjection != null) {
                    // 先注销回调再停止，否则回调会递归调用此方法
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                    mediaProjection.stop();
                    mediaProjection = null;
                }
            }
        });
    }

    /**
     * 标记该捕获器为已释放状态。调用后，后续任何方法调用都将抛出 RuntimeException。
     */
    @Override
    public synchronized void dispose() {
        isDisposed = true;
    }

    /**
     * 更改输出视频格式。可用于缩放输出视频，或在捕获屏幕方向变化时调整宽高比。
     *
     * @param width     新的输出视频宽度
     * @param height    新的输出视频高度
     * @param frameRate 新的输出视频帧率
     */
    @Override
    public synchronized void changeCaptureFormat(
            final int width, final int height, final int frameRate) {
        checkNotDisposed();
        this.frameRate = frameRate;
        if (this.oldWidth != width || this.oldHeight != height) {
            this.width = width;
            this.height = height;
            this.oldWidth = width;
            this.oldHeight = height;

            ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.getHandler(), new Runnable() {
                @Override
                public void run() {
                    if (surfaceTextureHelper == null || mediaProjection == null) {
                        return;
                    }

                    if (virtualDisplay != null) {
                        resizeVirtualDisplay();
                    } else {
                        createVirtualDisplay();
                    }
                }
            });
        }
    }

    /**
     * 创建新的 VirtualDisplay 实例，将屏幕内容镜像渲染到
     * 绑定 SurfaceTextureHelper 的 SurfaceTexture 的 Surface 上。
     */
    private void createVirtualDisplay() {
        updateSurfaceTextureSize();
        releaseVirtualDisplaySurface();
        virtualDisplaySurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
        virtualDisplay = mediaProjection.createVirtualDisplay("WebRTC_ScreenCapture", width, height,
                VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS, virtualDisplaySurface,
                null /* 回调 */, null /* 回调处理器 */);
    }

    /**
     * 调整现有 VirtualDisplay 的大小并重新绑定新的 Surface，
     * 以匹配当前的宽高。释放之前的 Surface。
     */
    private void resizeVirtualDisplay() {
        updateSurfaceTextureSize();
        virtualDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI);
        final Surface oldSurface = virtualDisplaySurface;
        virtualDisplaySurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
        virtualDisplay.setSurface(virtualDisplaySurface);
        if (oldSurface != null) {
            oldSurface.release();
        }
    }

    /**
     * 更新 SurfaceTextureHelper 和底层 SurfaceTexture 缓冲区大小，
     * 以匹配当前的捕获宽高。
     */
    private void updateSurfaceTextureSize() {
        surfaceTextureHelper.setTextureSize(width, height);
        surfaceTextureHelper.getSurfaceTexture().setDefaultBufferSize(width, height);
    }

    /**
     * 释放当前的 VirtualDisplay Surface 并将引用置空，防止资源泄漏。
     */
    private void releaseVirtualDisplaySurface() {
        if (virtualDisplaySurface != null) {
            virtualDisplaySurface.release();
            virtualDisplaySurface = null;
        }
    }

    /**
     * 指示该捕获器是否为屏幕投射类型。由于该捕获器始终捕获系统屏幕，固定返回 true。
     *
     * @return 始终返回 true，因为这是屏幕捕获器
     */
    @Override
    public boolean isScreencast() {
        return true;
    }

    /**
     * 返回当前捕获会话期间累计捕获的帧总数。
     *
     * @return 自从上次开始捕获或重置以来的帧数
     */
    public long getNumCapturedFrames() {
        return numCapturedFrames;
    }
}

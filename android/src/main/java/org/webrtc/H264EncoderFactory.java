package org.webrtc;

import android.media.MediaCodecInfo;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

// H264 编码器工厂。在 org.webrtc 包内，可以直接访问包私有类，无需反射。
// 用于在标准 libwebrtc 白名单不包含的设备上（如华为 Kirin）创建 H264 硬件编码器。
public class H264EncoderFactory {
    private static final String TAG = "H264EncoderFactory";

    /**
     * 创建 H264 编码器。即使当前编码器在 libwebrtc 白名单之外，也可通过此工厂创建。
     *
     * @param codecName     MediaCodec 编码器名称（如 "OMX.google.h264.encoder"）
     * @param sharedContext EGL 共享上下文，用于 Surface 模式；为 null 时降级为 YUV buffer 模式
     * @return 编码器实例，失败时返回 null
     */
    public static VideoEncoder createEncoder(String codecName, EglBase.Context sharedContext) {
        try {
            MediaCodecWrapperFactory wrapperFactory = new MediaCodecWrapperFactoryImpl();

            EglBase14.Context egl14Context = extractEgl14Context(sharedContext);

            // yuvColorFormat 必须始终非 null，HardwareVideoEncoder.updateInputFormat() 会解包 intValue()
            Integer yuvColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            Integer surfaceColorFormat = (egl14Context != null)
                    ? MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    : null;

            Map<String, String> params = new HashMap<>();
            params.put("level-asymmetry-allowed", "1");
            params.put("packetization-mode", "1");
            params.put("profile-level-id", "42e01f");

            Log.i(TAG, "Creating H264 encoder: codec=" + codecName
                    + ", surface=" + surfaceColorFormat
                    + ", yuv=" + yuvColorFormat
                    + ", egl14Context=" + (egl14Context != null ? egl14Context.getClass().getName() : "null"));

            return new HardwareVideoEncoder(
                    wrapperFactory,
                    codecName,
                    VideoCodecMimeType.H264,
                    surfaceColorFormat,
                    yuvColorFormat,
                    params,
                    20,
                    0,
                    new BaseBitrateAdjuster(),
                    egl14Context
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create H264 encoder", e);
            return null;
        }
    }

    private static EglBase14.Context extractEgl14Context(EglBase.Context sharedContext) {
        if (sharedContext == null) {
            return null;
        }
        if (sharedContext instanceof EglBase14.Context) {
            return (EglBase14.Context) sharedContext;
        }
        Log.w(TAG, "sharedContext is not EglBase14.Context: " + sharedContext.getClass().getName());
        return null;
    }
}

package org.webrtc.video;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.SimulcastVideoEncoderFactoryWrapper;

import org.webrtc.EglBase;
import org.webrtc.H264EncoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoEncoder;
import org.webrtc.VideoEncoderFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义视频编码器工厂，在 SimulcastVideoEncoderFactoryWrapper 基础上扩展 H264 硬件编码支持。
 *
 * 对 H264 编码增加了硬件编码器探测和 fallback 机制：当设备搭载华为 Kirin 芯片（OMX.hisi.*）时
 * 优先使用硬件 H264 编码器，否则回退到 Google 软件编码器。此举解决了部分设备（尤其是华为）
 * 上 Simulcast 工厂无法正确处理 TextureBuffer 导致的编码失败问题。
 */
public class CustomVideoEncoderFactory implements VideoEncoderFactory {
    private static final String TAG = "CustomVideoEncoderFac";

    private final SoftwareVideoEncoderFactory softwareVideoEncoderFactory = new SoftwareVideoEncoderFactory();
    private final SimulcastVideoEncoderFactoryWrapper simulcastVideoEncoderFactoryWrapper;

    private boolean forceSWCodec = false;

    private List<String> forceSWCodecs = new ArrayList<>();

    /**
     * 保存 EGL 共享上下文，用于 H264EncoderFactory 创建硬件编码器时绑定 EGL 环境。
     */
    private final EglBase.Context sharedContext;

    public CustomVideoEncoderFactory(EglBase.Context sharedContext,
                                     boolean enableIntelVp8Encoder,
                                     boolean enableH264HighProfile) {
        this.sharedContext = sharedContext;
        this.simulcastVideoEncoderFactoryWrapper = new SimulcastVideoEncoderFactoryWrapper(sharedContext, enableIntelVp8Encoder, enableH264HighProfile);
    }

    public void setForceSWCodec(boolean forceSWCodec) {
        this.forceSWCodec = forceSWCodec;
    }

    public void setForceSWCodecList(List<String> forceSWCodecs) {
        this.forceSWCodecs = forceSWCodecs;
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo videoCodecInfo) {
        if (forceSWCodec) {
            return softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
        }

        if (!forceSWCodecs.isEmpty() && forceSWCodecs.contains(videoCodecInfo.name)) {
            return softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
        }

        VideoEncoder encoder = simulcastVideoEncoderFactoryWrapper.createEncoder(videoCodecInfo);

        /*
         * H264 优先使用硬件编码器 fallback 工厂。
         * SimulcastVideoEncoderFactoryWrapper 内部创建的 H264 编码器在某些设备上
         * 无法处理 TextureBuffer（缺少 Surface+EGL 支持），导致编码失败。
         * 因此通过 H264EncoderFactory 直接创建编码器作为备选方案。
         */
        if ("H264".equalsIgnoreCase(videoCodecInfo.name)) {
            String codecName = findHuaweiH264EncoderName();
            if (codecName != null) {
                VideoEncoder fallbackEncoder = H264EncoderFactory.createEncoder(codecName, sharedContext);
                if (fallbackEncoder != null) {
                    Log.i(TAG, "Using H264 encoder: " + codecName);
                    return fallbackEncoder;
                }
            }
            Log.w(TAG, "H264 fallback encoder unavailable, using base factory result");
        }

        return encoder;
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        if (forceSWCodec && forceSWCodecs.isEmpty()) {
            return softwareVideoEncoderFactory.getSupportedCodecs();
        }
        VideoCodecInfo[] base = simulcastVideoEncoderFactoryWrapper.getSupportedCodecs();

        /*
         * 若 Simulcast 工厂返回的编码器列表中不包含 H264，但在设备上能探测到
         * 可用的 H264 硬件或软件编码器，则主动将 H264 加入支持列表。
         * 这样上层可以在 SDP 协商中包含 H264，提升兼容性。
         */
        if (!hasCodec(base, "H264")) {
            String codecName = findHuaweiH264EncoderName();
            if (codecName != null) {
                Log.i(TAG, "Adding H264 encoder: " + codecName);
                List<VideoCodecInfo> extended = new ArrayList<>();
                for (VideoCodecInfo c : base) {
                    extended.add(c);
                }
                extended.add(new VideoCodecInfo("H264", createH264Params(), null));
                base = extended.toArray(new VideoCodecInfo[0]);
            } else {
                Log.w(TAG, "No suitable H264 encoder found");
            }
        }

        return base;
    }

    /**
     * 检查编码器列表中是否已包含指定名称的编码器（忽略大小写）。
     */
    private static boolean hasCodec(VideoCodecInfo[] codecs, String name) {
        for (VideoCodecInfo c : codecs) {
            if (c.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找可用的 H264 编码器。优先使用华为 Kirin 硬件编码器，否则回退到 Google 软件编码器。
     *
     * @return MediaCodec 编码器名称，找不到返回 null
     */
    @Nullable
    private static String findHuaweiH264EncoderName() {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String googleH264Fallback = null;
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                String name = info.getName();
                boolean supportsAvc = false;
                for (String type : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        supportsAvc = true;
                        break;
                    }
                }
                if (!supportsAvc) continue;
                if (name.startsWith("OMX.hisi.")) {
                    return name;
                }
                if ("OMX.google.h264.encoder".equals(name)) {
                    googleH264Fallback = name;
                }
            }
            return googleH264Fallback;
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaCodecList: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 构造 H264 编解码参数映射，用于声明支持的 H264 profile 和能力。
     * profile-level-id=42e01f 表示 Constrained Baseline Profile Level 3.1。
     */
    private static Map<String, String> createH264Params() {
        Map<String, String> params = new HashMap<>();
        params.put("level-asymmetry-allowed", "1");
        params.put("packetization-mode", "1");
        params.put("profile-level-id", "42e01f");
        return params;
    }
}

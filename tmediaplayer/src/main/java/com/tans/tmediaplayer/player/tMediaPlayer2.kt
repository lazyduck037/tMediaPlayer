package com.tans.tmediaplayer.player

import androidx.annotation.Keep
import com.tans.tmediaplayer.MediaLog
import com.tans.tmediaplayer.player.model.AudioSampleFormat
import com.tans.tmediaplayer.player.model.AudioStreamInfo
import com.tans.tmediaplayer.player.model.FFmpegCodec
import com.tans.tmediaplayer.player.model.MediaInfo
import com.tans.tmediaplayer.player.model.VideoPixelFormat
import com.tans.tmediaplayer.player.model.VideoStreamInfo
import com.tans.tmediaplayer.player.render.tMediaPlayerView
import com.tans.tmediaplayer.player.rwqueue.AudioFrame
import com.tans.tmediaplayer.player.rwqueue.Packet
import com.tans.tmediaplayer.player.rwqueue.PacketQueue
import com.tans.tmediaplayer.player.rwqueue.VideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@Suppress("ClassName")
@Keep
class tMediaPlayer2(
    private val audioOutputChannel: AudioChannel = AudioChannel.Stereo,
    private val audioOutputSampleRate: AudioSampleRate = AudioSampleRate.Rate48000,
    private val audioOutputSampleBitDepth: AudioSampleBitDepth = AudioSampleBitDepth.SixteenBits,
    private val enableVideoHardwareDecoder: Boolean = true,
) : IPlayer {

    private val listener: AtomicReference<tMediaPlayerListener?> by lazy {
        AtomicReference(null)
    }

    private val state: AtomicReference<tMediaPlayerState> by lazy {
        AtomicReference(tMediaPlayerState.NoInit)
    }

    private val audioPacketQueue: PacketQueue by lazy {
        PacketQueue(this)
    }

    private val videoPacketQueue: PacketQueue by lazy {
        PacketQueue(this)
    }

    private val packetReader: PacketReader by lazy {
        PacketReader(
            player = this,
            audioPacketQueue = audioPacketQueue,
            videoPacketQueue = videoPacketQueue
        )
    }


    // region public methods
    @Synchronized
    override fun prepare(file: String): OptResult {
        val lastState = getState()
        if (lastState == tMediaPlayerState.Released) {
            MediaLog.e(TAG, "Prepare fail, player has released.")
            return OptResult.Fail
        }
        val lastMediaInfo = getMediaInfo()
        if (lastMediaInfo != null) {
            // Release last nativePlayer.
            releaseNative(lastMediaInfo.nativePlayer)
        }
        dispatchNewState(tMediaPlayerState.NoInit)
        packetReader.removeAllHandlerMessages()
        audioPacketQueue.flushReadableBuffer()
        videoPacketQueue.flushReadableBuffer()
        val nativePlayer = createPlayerNative()
        val result = prepareNative(
            nativePlayer = nativePlayer,
            file = file,
            requestHw = enableVideoHardwareDecoder,
            targetAudioChannels = audioOutputChannel.channel,
            targetAudioSampleRate = audioOutputSampleRate.rate,
            targetAudioSampleBitDepth = audioOutputSampleBitDepth.depth
        ).toOptResult()
        if (result == OptResult.Success) {
            // Load media file success.
            val mediaInfo = getMediaInfo(nativePlayer)
            MediaLog.d(tMediaPlayer.TAG, "Prepare player success: $mediaInfo")
            dispatchNewState(tMediaPlayerState.Prepared(mediaInfo))
            packetReader.requestReadPkt()
        } else {
            // Load media file fail.
            releaseNative(nativePlayer)
            MediaLog.e(tMediaPlayer.TAG, "Prepare player fail.")
            dispatchNewState(tMediaPlayerState.Error("Prepare player fail."))
        }
        return result
    }

    @Synchronized
    override fun play(): OptResult {
        // TODO:
        return OptResult.Fail
    }

    @Synchronized
    override fun pause(): OptResult {
        // TODO:
        return OptResult.Fail
    }

    @Synchronized
    override fun seekTo(position: Long): OptResult {
        // TODO:
        return OptResult.Fail
    }

    @Synchronized
    override fun stop(): OptResult {
        // TODO:
        return OptResult.Fail
    }

    @Synchronized
    override fun release(): OptResult {
        synchronized(packetReader) {
            val lastState = getState()
            if (lastState == tMediaPlayerState.NoInit || lastState == tMediaPlayerState.Released) {
                return OptResult.Fail
            }
            val mediaInfo = getMediaInfo()
            if (mediaInfo != null) {
                releaseNative(mediaInfo.nativePlayer)
            }
            dispatchNewState(tMediaPlayerState.Released)
            listener.set(null)
            audioPacketQueue.release()
            videoPacketQueue.release()
            packetReader.release()
        }
        return OptResult.Success
    }

    override fun getProgress(): Long {
        // TODO:
        return 0L
    }

    override fun getState(): tMediaPlayerState = state.get()

    override fun getMediaInfo(): MediaInfo? {
        return getMediaInfoByState(getState())
    }

    override fun setListener(l: tMediaPlayerListener?) {
        listener.set(l)
        l?.onPlayerState(getState())
    }

    override fun attachPlayerView(view: tMediaPlayerView?) {
        // TODO:
    }
    // endregion

    // region Player internal methods.
    private fun getMediaInfoByState(state: tMediaPlayerState): MediaInfo? {
        return when (state) {
            tMediaPlayerState.NoInit -> null
            is tMediaPlayerState.Error -> null
            is tMediaPlayerState.Released -> null
            is tMediaPlayerState.Paused -> state.mediaInfo
            is tMediaPlayerState.PlayEnd -> state.mediaInfo
            is tMediaPlayerState.Playing -> state.mediaInfo
            is tMediaPlayerState.Prepared -> state.mediaInfo
            is tMediaPlayerState.Stopped -> state.mediaInfo
            is tMediaPlayerState.Seeking -> getMediaInfoByState(state.lastState)
        }
    }

    private fun getMediaInfo(nativePlayer: Long): MediaInfo {
        val metadata = mutableMapOf<String, String>()
        val metaDataArray = getMetadataNative(nativePlayer)
        repeat(metaDataArray.size / 2) {
            val key = metaDataArray[it * 2]
            val value = metaDataArray[it * 2 + 1]
            metadata[key] = value
        }
        val audioStreamInfo: AudioStreamInfo? = if (containAudioStreamNative(nativePlayer)) {
            val codecId = audioCodecIdNative(nativePlayer)
            val sampleFormatId = audioSampleFmtNative(nativePlayer)
            AudioStreamInfo(
                audioChannels = audioChannelsNative(nativePlayer),
                audioSimpleRate = audioSampleRateNative(nativePlayer),
                audioPerSampleBytes = audioPerSampleBytesNative(nativePlayer),
                audioDuration = audioDurationNative(nativePlayer),
                audioCodec = FFmpegCodec.entries.find { it.codecId == codecId } ?: FFmpegCodec.UNKNOWN,
                audioBitrate = audioBitrateNative(nativePlayer),
                audioSampleBitDepth = audioSampleBitDepthNative(nativePlayer),
                audioSampleFormat = AudioSampleFormat.entries.find { it.formatId == sampleFormatId } ?: AudioSampleFormat.UNKNOWN
            )
        } else {
            null
        }
        val videoStreamInfo: VideoStreamInfo? = if (containVideoStreamNative(nativePlayer)) {
            val codecId = videoCodecIdNative(nativePlayer)
            val pixelFormatId = videoPixelFmtNative(nativePlayer)
            VideoStreamInfo(
                videoWidth = videoWidthNative(nativePlayer),
                videoHeight = videoHeightNative(nativePlayer),
                videoFps = videoFpsNative(nativePlayer),
                videoDuration = videoDurationNative(nativePlayer),
                videoCodec = FFmpegCodec.entries.find { it.codecId == codecId } ?: FFmpegCodec.UNKNOWN,
                videoBitrate = videoBitrateNative(nativePlayer),
                videoPixelBitDepth = videoPixelBitDepthNative(nativePlayer),
                videoPixelFormat = VideoPixelFormat.entries.find { it.formatId == pixelFormatId } ?: VideoPixelFormat.UNKNOWN
            )
        } else {
            null
        }
        return MediaInfo(
            nativePlayer = nativePlayer,
            duration = durationNative(nativePlayer),
            metadata = metadata,
            audioStreamInfo = audioStreamInfo,
            videoStreamInfo = videoStreamInfo
        )
    }

    private fun dispatchNewState(s: tMediaPlayerState) {
        val lastState = getState()
        if (lastState != s) {
            state.set(s)
            callbackExecutor.execute {
                listener.get()?.onPlayerState(s)
            }
        }
    }

    internal fun dispatchProgress(progress: Long) {
        val state = getState()
        if (state !is tMediaPlayerState.PlayEnd &&
            state !is tMediaPlayerState.Error &&
            state !is tMediaPlayerState.Stopped &&
            state !is tMediaPlayerState.Seeking &&
            state !is tMediaPlayerState.NoInit) {
            val info = getMediaInfo()
            if (info != null) {
                callbackExecutor.execute {
                    listener.get()?.onProgressUpdate(progress, info.duration)
                }
            }
        } else {
            MediaLog.e(TAG, "Ignore progress update, because of state: $state")
        }
    }

    internal fun readableVideoPacketReady() {
        // TODO: Notify video decoder
    }
    internal fun readableAudioPacketReady() {
        // TODO: Notify audio decoder
    }

    internal fun writeableVideoPacketReady() {
        packetReader.packetBufferReady()
    }

    internal fun writeableAudioPacketReady() {
        packetReader.packetBufferReady()
    }
    // endregion

    // region Native player control methods.
    private external fun createPlayerNative(): Long

    private external fun prepareNative(
        nativePlayer: Long,
        file: String,
        requestHw: Boolean,
        targetAudioChannels: Int,
        targetAudioSampleRate: Int,
        targetAudioSampleBitDepth: Int): Int

    internal fun readPacketInternal(nativePlayer: Long): ReadPacketResult = readPacketNative(nativePlayer).toReadPacketResult()

    private external fun readPacketNative(nativePlayer: Long): Int

    internal fun pauseReadPacketInternal(nativePlayer: Long): OptResult = pauseReadPacketNative(nativePlayer).toOptResult()

    private external fun pauseReadPacketNative(nativePlayer: Long): Int

    internal fun playReadPacketInternal(nativePlayer: Long): OptResult = playReadPacketNative(nativePlayer).toOptResult()

    private external fun playReadPacketNative(nativePlayer: Long): Int

    internal fun movePacketRefInternal(nativePlayer: Long, nativePacket: Long) = movePacketRefNative(nativePlayer, nativePacket)

    private external fun movePacketRefNative(nativePlayer: Long, nativePacket: Long)

    internal fun seekToInternal(nativePlayer: Long, targetPosInMillis: Long): OptResult = seekToNative(nativePlayer, targetPosInMillis).toOptResult()

    private external fun seekToNative(nativePlayer: Long, targetPosInMillis: Long): Int

    internal fun decodeVideoInternal(nativePlayer: Long, pkt: Packet): DecodeResult2 {
        return decodeVideoNative(nativePlayer, pkt.nativePacket).toDecodeResult2()
    }

    private external fun decodeVideoNative(nativePlayer: Long, nativeBuffer: Long): Int

    internal fun flushVideoCodecBufferInternal(nativePlayer: Long) = flushVideoCodecBufferNative(nativePlayer)

    private external fun flushVideoCodecBufferNative(nativePlayer: Long)

    internal fun moveDecodedVideoFrameToBufferInternal(nativePlayer: Long, videoFrame: VideoFrame): OptResult {
        return moveDecodedVideoFrameToBufferNative(nativePlayer, videoFrame.nativeFrame).toOptResult()
    }

    private external fun moveDecodedVideoFrameToBufferNative(nativePlayer: Long, nativeBuffer: Long): Int

    internal fun decodeAudioInternal(nativePlayer: Long, pkt: Packet): DecodeResult2 {
        return decodeAudioNative(nativePlayer, pkt.nativePacket).toDecodeResult2()
    }

    private external fun decodeAudioNative(nativePlayer: Long, nativeBuffer: Long): Int

    internal fun flushAudioCodecBufferInternal(nativePlayer: Long) = flushAudioCodecBufferNative(nativePlayer)

    private external fun flushAudioCodecBufferNative(nativePlayer: Long)

    internal fun moveDecodedAudioFrameToBufferInternal(nativePlayer: Long, audioFrame: AudioFrame): OptResult {
        return moveDecodedAudioFrameToBufferNative(nativePlayer, audioFrame.nativeFrame).toOptResult()
    }

    private external fun moveDecodedAudioFrameToBufferNative(nativePlayer: Long, nativeBuffer: Long): Int

    private external fun releaseNative(nativePlayer: Long)
    // endregion

    // region Native media file info
    private external fun durationNative(nativePlayer: Long): Long

    private external fun containVideoStreamNative(nativePlayer: Long): Boolean

    private external fun containAudioStreamNative(nativePlayer: Long): Boolean

    private external fun getMetadataNative(nativePlayer: Long): Array<String>
    // endregion

    // region Native video stream info
    private external fun videoWidthNative(nativePlayer: Long): Int

    private external fun videoHeightNative(nativePlayer: Long): Int

    private external fun videoBitrateNative(nativePlayer: Long): Int

    private external fun videoPixelBitDepthNative(nativePlayer: Long): Int

    private external fun videoPixelFmtNative(nativePlayer: Long): Int


    private external fun videoFpsNative(nativePlayer: Long): Double

    private external fun videoDurationNative(nativePlayer: Long): Long

    private external fun videoCodecIdNative(nativePlayer: Long): Int
    // endregion

    // region Native audio stream info
    private external fun audioChannelsNative(nativePlayer: Long): Int

    private external fun audioPerSampleBytesNative(nativePlayer: Long): Int

    private external fun audioBitrateNative(nativePlayer: Long): Int

    private external fun audioSampleBitDepthNative(nativePlayer: Long): Int

    private external fun audioSampleFmtNative(nativePlayer: Long): Int

    private external fun audioSampleRateNative(nativePlayer: Long): Int

    private external fun audioDurationNative(nativePlayer: Long): Long

    private external fun audioCodecIdNative(nativePlayer: Long): Int
    // endregion

    // region Native packet buffer
    internal fun allocPacketInternal(): Long = allocPacketNative()

    private external fun allocPacketNative(): Long
    internal fun getPacketPtsInternal(nativeBuffer: Long): Long = getPacketPtsNative(nativeBuffer)
    private external fun getPacketPtsNative(nativeBuffer: Long): Long
    internal fun getPacketDurationInternal(nativeBuffer: Long): Long = getPacketDurationNative(nativeBuffer)
    private external fun getPacketDurationNative(nativeBuffer: Long): Long
    internal fun getPacketBytesSizeInternal(nativeBuffer: Long): Int = getPacketBytesSizeNative(nativeBuffer)
    private external fun getPacketBytesSizeNative(nativeBuffer: Long): Int
    internal fun releasePacketInternal(nativeBuffer: Long) = releasePacketNative(nativeBuffer)
    private external fun releasePacketNative(nativeBuffer: Long)
    // endregion

    // region Native video buffer
    internal fun allocVideoBufferInternal(): Long = allocVideoBufferNative()

    private external fun allocVideoBufferNative(): Long

    internal fun getVideoPtsInternal(nativeBuffer: Long): Long = getVideoPtsNative(nativeBuffer)

    private external fun getVideoPtsNative(nativeBuffer: Long): Long

    internal fun getVideoWidthNativeInternal(nativeBuffer: Long): Int = getVideoWidthNative(nativeBuffer)

    private external fun getVideoWidthNative(nativeBuffer: Long): Int

    internal fun getVideoHeightNativeInternal(nativeBuffer: Long): Int = getVideoHeightNative(nativeBuffer)

    private external fun getVideoHeightNative(nativeBuffer: Long): Int

    internal fun getVideoFrameTypeNativeInternal(nativeBuffer: Long): ImageRawType = getVideoFrameTypeNative(nativeBuffer).toImageRawType()

    private external fun getVideoFrameTypeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameRgbaBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getVideoFrameRgbaBytesNative(nativeBuffer, bytes)

    private external fun getVideoFrameRgbaBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun getVideoFrameRgbaSizeNativeInternal(nativeBuffer: Long): Int = getVideoFrameRgbaSizeNative(nativeBuffer)

    private external fun getVideoFrameRgbaSizeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameYSizeNativeInternal(nativeBuffer: Long): Int = getVideoFrameYSizeNative(nativeBuffer)

    private external fun getVideoFrameYSizeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameYBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getVideoFrameYBytesNative(nativeBuffer, bytes)

    private external fun getVideoFrameYBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun getVideoFrameUSizeNativeInternal(nativeBuffer: Long): Int = getVideoFrameUSizeNative(nativeBuffer)

    private external fun getVideoFrameUSizeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameUBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getVideoFrameUBytesNative(nativeBuffer, bytes)

    private external fun getVideoFrameUBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun getVideoFrameVSizeNativeInternal(nativeBuffer: Long): Int = getVideoFrameVSizeNative(nativeBuffer)

    private external fun getVideoFrameVSizeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameVBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getVideoFrameVBytesNative(nativeBuffer, bytes)

    private external fun getVideoFrameVBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun getVideoFrameUVSizeNativeInternal(nativeBuffer: Long): Int = getVideoFrameUVSizeNative(nativeBuffer)

    private external fun getVideoFrameUVSizeNative(nativeBuffer: Long): Int

    internal fun getVideoFrameUVBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getVideoFrameUVBytesNative(nativeBuffer, bytes)

    private external fun getVideoFrameUVBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun releaseVideoBufferInternal(nativeBuffer: Long) = releaseVideoBufferNative(nativeBuffer)

    private external fun releaseVideoBufferNative(nativeBuffer: Long)
    // endregion

    // region Native audio buffer
    internal fun allocAudioBufferInternal(): Long = allocAudioBufferNative()

    private external fun allocAudioBufferNative(): Long

    internal fun getAudioPtsInternal(nativeBuffer: Long): Long = getAudioPtsNative(nativeBuffer)

    private external fun getAudioPtsNative(nativeBuffer: Long): Long

    internal fun getAudioFrameBytesNativeInternal(nativeBuffer: Long, bytes: ByteArray) = getAudioFrameBytesNative(nativeBuffer, bytes)

    private external fun getAudioFrameBytesNative(nativeBuffer: Long, bytes: ByteArray)

    internal fun getAudioFrameSizeNativeInternal(nativeBuffer: Long): Int = getAudioFrameSizeNative(nativeBuffer)

    private external fun getAudioFrameSizeNative(nativeBuffer: Long): Int

    internal fun releaseAudioBufferInternal(nativeBuffer: Long) = releaseAudioBufferNative(nativeBuffer)

    private external fun releaseAudioBufferNative(nativeBuffer: Long)
    // endregion


    companion object {
        private const val TAG = "tMediaPlayer"

        init {
            System.loadLibrary("tmediaplayer2")
        }

        private val callbackExecutor by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "tMediaPlayerCallbackThread")
            }
        }
    }
}
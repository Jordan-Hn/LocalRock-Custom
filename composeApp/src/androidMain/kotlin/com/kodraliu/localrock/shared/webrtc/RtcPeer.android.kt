package com.kodraliu.localrock.shared.webrtc

import com.kodraliu.localrock.shared.protocol.appContextOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual val liveViewSupported: Boolean = true

private val CRLF = "" + 13.toChar() + 10.toChar()

actual fun createRtcPeer(iceServers: List<RtcIceServer>): RtcPeer = AndroidRtcPeer(iceServers)

internal val rtcEglBase: EglBase by lazy { EglBase.create() }

private val peerConnectionFactory: PeerConnectionFactory by lazy {
    val ctx = requireNotNull(appContextOrNull()) { "Android context not initialized" }
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
    )
    PeerConnectionFactory.builder()
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(rtcEglBase.eglBaseContext))
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(rtcEglBase.eglBaseContext, true, true))
        .createPeerConnectionFactory()
}

internal class AndroidRtcPeer(iceServers: List<RtcIceServer>) : RtcPeer {

    private val _localCandidates = MutableSharedFlow<RtcIceCandidate>(replay = 32, extraBufferCapacity = 32)
    override val localCandidates: SharedFlow<RtcIceCandidate> = _localCandidates

    private val _connectionState = MutableStateFlow(RtcConnectionState.NEW)
    override val connectionState: StateFlow<RtcConnectionState> = _connectionState

    val videoTrack = MutableStateFlow<VideoTrack?>(null)

    private val pc: PeerConnection

    init {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.url)
                .apply {
                    if (s.username.isNotEmpty()) setUsername(s.username)
                    if (s.password.isNotEmpty()) setPassword(s.password)
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = requireNotNull(peerConnectionFactory.createPeerConnection(config, Observer())) {
            "createPeerConnection returned null"
        }
    }

    override suspend fun createOffer(): String {
        // Video receive-only first, then two-way audio: go2rtc's Roborock client, which streams from
        // current models, builds its offer in this order. No microphone track is attached yet, so
        // nothing is sent until talk is added.
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        audioTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
        )
        val created = suspendCoroutine<SessionDescription> { cont ->
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(RuntimeException("createOffer failed: $error"))
            }, MediaConstraints())
        }
        // The robot never answers the full offer Android builds (VP8, VP9, AV1, RTX, RED, FEC, a dozen
        // header extensions): tested 2026-11-25 on the a226 by replaying offers through go2rtc, the full
        // one got "retry" forever and the same offer cut down to H.264 video and Opus/PCMU/PCMA audio was
        // answered at once. So the trimmed offer is what gets sent.
        val trimmed = SessionDescription(created.type, trimOfferForRobot(created.description))
        Log.i(TAG, "offer ${created.description.length} -> ${trimmed.description.length} chars")
        // Removing codecs is a change libwebrtc accepts; should a build ever refuse it, the full offer is
        // set locally instead. The robot's answer only uses what the trimmed offer listed, which the
        // full one also has, so the call still connects.
        val setTrimmed = runCatching { setLocal(trimmed) }
        if (setTrimmed.isFailure) {
            Log.w(TAG, "trimmed offer refused locally, using the full one", setTrimmed.exceptionOrNull())
            setLocal(created)
        }
        return trimmed.description
    }

    private suspend fun setLocal(desc: SessionDescription) = suspendCoroutine<Unit> { cont ->
        pc.setLocalDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) =
                cont.resumeWithException(RuntimeException("setLocalDescription failed: $error"))
        }, desc)
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        suspendCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(RuntimeException("setRemoteDescription failed: $error"))
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
    }

    override fun addRemoteCandidate(candidate: RtcIceCandidate) {
        pc.addIceCandidate(IceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex, candidate.candidate))
    }

    private var audioTransceiver: RtpTransceiver? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    override fun setMicrophoneEnabled(enabled: Boolean) {
        val transceiver = audioTransceiver ?: return
        if (enabled && micTrack == null) {
            // The audio section is already send/receive, so attaching a track needs no renegotiation.
            val source = peerConnectionFactory.createAudioSource(MediaConstraints())
            val track = peerConnectionFactory.createAudioTrack("localrock-mic", source)
            transceiver.sender.setTrack(track, false)
            micSource = source
            micTrack = track
        }
        micTrack?.setEnabled(enabled)
    }

    override fun close() {
        runCatching { micTrack?.setEnabled(false) }
        videoTrack.value = null
        _connectionState.value = RtcConnectionState.CLOSED
        runCatching { pc.close() }
        runCatching { pc.dispose() }
        runCatching { micSource?.dispose() }
        micTrack = null
        micSource = null
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            _localCandidates.tryEmit(
                RtcIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            )
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let { videoTrack.value = it }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.let { videoTrack.value = it }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            // Don't clobber the terminal CLOSED state set by close().
            if (_connectionState.value == RtcConnectionState.CLOSED) return
            _connectionState.value = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> RtcConnectionState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> RtcConnectionState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> RtcConnectionState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> RtcConnectionState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> RtcConnectionState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> RtcConnectionState.CLOSED
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }
}

private const val TAG = "LocalRockRtc"

private val VIDEO_CODECS = setOf("h264")
private val AUDIO_CODECS = setOf("opus", "pcmu", "pcma")

/**
 * Keeps only the codecs go2rtc offers (H.264 video; Opus, PCMU, PCMA audio), drops every header
 * extension but the mid one BUNDLE needs, and removes a=extmap-allow-mixed and RTX SSRC groups.
 */
internal fun trimOfferForRobot(sdp: String): String {
    val lines = sdp.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
    val out = mutableListOf<String>()
    var i = 0
    // Session part, up to the first m= line.
    while (i < lines.size && !lines[i].startsWith("m=")) {
        if (!lines[i].startsWith("a=extmap-allow-mixed")) out += lines[i]
        i++
    }
    while (i < lines.size) {
        val section = mutableListOf(lines[i]); i++
        while (i < lines.size && !lines[i].startsWith("m=")) { section += lines[i]; i++ }
        out += trimSection(section)
    }
    return out.joinToString(CRLF) + CRLF
}

private fun trimSection(section: List<String>): List<String> {
    val mLine = section.first()
    val parts = mLine.split(" ")
    val kind = parts.first().removePrefix("m=")
    val wanted = when (kind) { "video" -> VIDEO_CODECS; "audio" -> AUDIO_CODECS; else -> return section }
    val names = section.mapNotNull { l ->
        if (!l.startsWith("a=rtpmap:")) return@mapNotNull null
        val pt = l.removePrefix("a=rtpmap:").substringBefore(' ')
        pt to l.substringAfter(' ').substringBefore('/').lowercase()
    }.toMap()
    val keep = parts.drop(3).filter { names[it] in wanted }.toSet()
    if (keep.isEmpty()) return section
    // Retransmission streams: the second SSRC of each FID group.
    val rtxSsrcs = section.filter { it.startsWith("a=ssrc-group:FID") }.mapNotNull { it.split(" ").getOrNull(2) }.toSet()
    fun ptOf(l: String, prefix: String) = l.removePrefix(prefix).substringBefore(' ').substringBefore(':')
    return buildList {
        add((parts.take(3) + parts.drop(3).filter { it in keep }).joinToString(" "))
        for (l in section.drop(1)) {
            when {
                l.startsWith("a=rtpmap:") && ptOf(l, "a=rtpmap:") !in keep -> Unit
                l.startsWith("a=fmtp:") && ptOf(l, "a=fmtp:") !in keep -> Unit
                l.startsWith("a=rtcp-fb:") && ptOf(l, "a=rtcp-fb:").let { it != "*" && it !in keep } -> Unit
                l.startsWith("a=extmap:") && !l.contains("sdes:mid") -> Unit
                l.startsWith("a=extmap-allow-mixed") -> Unit
                l.startsWith("a=ssrc-group:FID") -> Unit
                l.startsWith("a=ssrc:") && l.removePrefix("a=ssrc:").substringBefore(' ') in rtxSsrcs -> Unit
                else -> add(l)
            }
        }
    }
}

private abstract class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

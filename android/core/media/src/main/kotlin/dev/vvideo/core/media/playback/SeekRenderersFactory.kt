package dev.vvideo.core.media.playback

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
internal class SeekRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
 init { if(Build.VERSION.SDK_INT>=34)experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(true) }

 override fun buildVideoRenderers(
  context: Context,
  extensionRendererMode: Int,
  selector: MediaCodecSelector,
  fallback: Boolean,
  handler: Handler,
  listener: VideoRendererEventListener,
  joiningMs: Long,
  out: ArrayList<Renderer>,
 ) {
  if(extensionRendererMode!=EXTENSION_RENDERER_MODE_OFF){
   super.buildVideoRenderers(context,extensionRendererMode,selector,fallback,handler,listener,joiningMs,out)
   return
  }
  val builder=MediaCodecVideoRenderer.Builder(context)
   .setCodecAdapterFactory(getCodecAdapterFactory()).setMediaCodecSelector(selector)
   .setAllowedJoiningTimeMs(joiningMs).setEnableDecoderFallback(fallback)
   .setEventHandler(handler).setEventListener(listener)
   .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)

  if(Build.VERSION.SDK_INT>=34)builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(true)
  out.add(SeekVideoRenderer(builder))
 }
}

@UnstableApi
private class SeekVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
 private var burst=false
 private var deadlineMs=0L

 override fun onPositionReset(positionUs: Long, joining: Boolean) {
  super.onPositionReset(positionUs,joining)
  burst=true;deadlineMs=SystemClock.elapsedRealtime()+2000
  updateCodecOperatingRate()
 }

 override fun getCodecOperatingRateV23(speed: Float, format: Format, streamFormats: Array<Format>): Float {
  val normal=super.getCodecOperatingRateV23(speed,format,streamFormats)
  val info=getCodecInfo()
  // Unknown rates stay unset: returning to an unset rate after a hint would
  // reinitialize the codec. Software and undeclared capabilities also retain
  // Media3's defaults. This hint never changes presentation/playback speed.
  if(!burst || !normal.isFinite() || normal<=0 ||
   format.sampleMimeType!=MimeTypes.VIDEO_H265 ||
   format.width<=0 || format.height<=0 ||
   format.width.toLong()*format.height<3840L*2160 ||
   info==null || !info.hardwareAccelerated
  )return normal
  val maximum=info.getMaxSupportedFrameRate(format.width,format.height)
  if(!maximum.isFinite()||maximum<=normal)return normal
  val requested=maxOf(normal,minOf(maximum,120f))
  if(requested>normal)Log.i("VVideoCodec","seek_operating_rate=$requested normal=$normal dimensions=${format.width}x${format.height}")
  return requested
 }

 override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
  super.renderOutputBufferV21(codec,index,presentationTimeUs,releaseTimeNs)
  endBurst()
 }

 override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
  super.render(positionUs,elapsedRealtimeUs)
  if(burst&&SystemClock.elapsedRealtime()>=deadlineMs)endBurst()
 }

 override fun onStopped(){endBurst();super.onStopped()}
 override fun onDisabled(){burst=false;super.onDisabled()}

 private fun endBurst(){if(burst){burst=false;updateCodecOperatingRate()}}
}

package dev.vvideo.data.settings

import java.net.URI

// Remote HTTP is permitted only for the one private host explicitly selected
// when this APK was built. All other remote connections require system-trusted
// HTTPS. The generated Android network policy enforces the same host boundary.
object ConnectionPolicy {
 fun canonical(url:String, trustedLanHost:String):String {
  val uri=URI(url.trim().trimEnd('/'))
  val scheme=uri.scheme?.lowercase()
  val host=uri.host?.lowercase()
  require(!host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null && (uri.path.isNullOrEmpty()||uri.path=="/"))
  require(uri.port==-1||uri.port in 1..65535)
  val loopback=host in setOf("localhost","127.0.0.1","[::1]","::1")
  val selected=trustedLanHost.isNotEmpty()&&host==trustedLanHost
  require(scheme=="https" || scheme=="http"&&(loopback||selected))
  return URI(scheme,null,host,uri.port,"",null,null).toString()
 }
}

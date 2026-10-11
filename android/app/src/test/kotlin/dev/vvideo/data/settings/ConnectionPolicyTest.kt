package dev.vvideo.data.settings

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
 @Test fun exactHostBoundaryAndCanonicalRoot() {
  assertEquals("http://127.0.0.1:19323",ConnectionPolicy.canonical("http://127.0.0.1:19323/",""))
  assertEquals("https://nas.example",ConnectionPolicy.canonical(" HTTPS://NAS.EXAMPLE/ ",""))
  assertEquals("http://192.168.1.10:8080",ConnectionPolicy.canonical("http://192.168.1.10:8080","192.168.1.10"))
  assertEquals("http://[::1]:8080",ConnectionPolicy.canonical("http://[::1]:8080",""))
  for(url in listOf("http://192.168.1.11","http://192.168.1.100","http://192.168.1.10.example","http://example.org","http://user:secret@192.168.1.10","http://192.168.1.10/path","http://192.168.1.10?token=x","http://192.168.1.10#x","http://192.168.1.10:0","http://192.168.1.10:65536")) {
   assertThrows(url,IllegalArgumentException::class.java){ConnectionPolicy.canonical(url,"192.168.1.10")}
  }
  assertThrows(IllegalArgumentException::class.java){ConnectionPolicy.canonical("http://192.168.1.10","")}
 }
}

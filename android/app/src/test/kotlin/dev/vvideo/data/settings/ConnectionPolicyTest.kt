package dev.vvideo.data.settings

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
 @Test fun acceptsHttpAndHttpsRootsForAnyHost() {
  assertEquals("http://127.0.0.1:19323",ConnectionPolicy.canonical("http://127.0.0.1:19323/"))
  assertEquals("https://nas.example",ConnectionPolicy.canonical(" HTTPS://NAS.EXAMPLE/ "))
  assertEquals("http://192.168.1.10:8080",ConnectionPolicy.canonical("http://192.168.1.10:8080"))
  assertEquals("http://nas.local:8080",ConnectionPolicy.canonical("http://NAS.local:8080/"))
  assertEquals("http://[::1]:8080",ConnectionPolicy.canonical("http://[::1]:8080"))
  for(url in listOf("ftp://192.168.1.10","http://user:secret@192.168.1.10","http://192.168.1.10/path","http://192.168.1.10?token=x","http://192.168.1.10#x","http://192.168.1.10:0","http://192.168.1.10:65536")) {
   assertThrows(url,IllegalArgumentException::class.java){ConnectionPolicy.canonical(url)}
  }
  // The settings form treats any exception as invalid input; a scheme-less address fails URI parsing.
  assertThrows(Exception::class.java){ConnectionPolicy.canonical("192.168.1.10:8080")}
 }
}

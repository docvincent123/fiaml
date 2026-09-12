package com.quremed.rehaflow.nativeui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
class ServerAddressTest {
 @Test fun normalizesHttpsServer() {
  assertEquals("https://192.168.1.106",ClinicApi.normalize(" 192.168.1.106 "))
  assertEquals("https://clinic.local",ClinicApi.normalize("https://CLINIC.local:443/"))
  assertEquals("https://clinic.local:8443",ClinicApi.normalize("clinic.local:8443"))
 }
 @Test fun rejectsUnsafeOrigins() {
  listOf("http://clinic.local","https://user:secret@clinic.local","https://clinic.local/patients","https://clinic.local?token=x","https://clinic.local#x","https://clinic.local:0","https://clinic.local:65536","https://").forEach { input ->
   assertThrows(IllegalArgumentException::class.java) { ClinicApi.normalize(input) }
  }
 }
}

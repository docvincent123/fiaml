package com.quremed.rehaflow.nativeui

import org.junit.Assert.*
import org.junit.Test

class PatientQrTest {
 private val id="12345678-1234-1234-1234-123456789abc"
 @Test fun acceptsOnlyConfiguredHttpsOrigin(){
  assertEquals(id,PatientQr.parse("https://192.168.1.106/patients?patient=$id","https://192.168.1.106"))
  assertEquals(id,PatientQr.parse("https://clinic.test:443/patients?patient=$id","https://clinic.test"))
 }
 @Test fun rejectsForeignOrAmbiguousCodes(){
  for(url in listOf("http://clinic.test/patients?patient=$id","https://evil.test/patients?patient=$id",
   "https://clinic.test:444/patients?patient=$id","https://user@clinic.test/patients?patient=$id",
   "https://clinic.test/patients?patient=$id&patient=$id","https://clinic.test/patients?patient=bad",
   "https://clinic.test/bed/$id","https://clinic.test/patients?patient=$id#settings")){
    try{PatientQr.parse(url,"https://clinic.test");fail(url)}catch(expected:IllegalArgumentException){}
  }
 }
}

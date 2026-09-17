package com.quremed.rehaflow.nativeui

import java.net.URI

/** Accept only links from the configured clinic; never navigate arbitrary scan text. */
object PatientQr {
    fun parse(text:String, server:String):String {
        require(text.length<=2048) { "Невідомий QR-код." }
        val uri=URI(text.trim());val origin=URI(server)
        fun port(u:URI)=if(u.port==-1)443 else u.port
        require(uri.scheme=="https" && origin.scheme=="https" &&
            uri.host!=null && uri.host.equals(origin.host,true) && port(uri)==port(origin) &&
            uri.rawUserInfo==null && uri.fragment==null && uri.rawPath=="/patients") {
            "Це не QR-код пацієнта поточного центру. Перевірте адресу сервера."
        }
        val match=Regex("patient=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})").matchEntire(uri.rawQuery ?: "")
        require(match!=null) { "Неправильний QR-код пацієнта." }
        return match.groupValues[1]
    }
}

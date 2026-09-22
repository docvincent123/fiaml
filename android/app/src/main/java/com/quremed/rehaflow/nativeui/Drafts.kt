package com.quremed.rehaflow.nativeui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.*
import androidx.compose.material3.*
import com.quremed.rehaflow.NativeSession
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DraftStore {
 private lateinit var context:Context
 fun initialize(c:Context){context=c.applicationContext}
 private fun slot(scope:String):String {require(NativeSession.userId.isNotEmpty()){ "Увійдіть для доступу до чернеток" };return MessageDigest.getInstance("SHA-256").digest((NativeSession.server+"|"+NativeSession.userId+"|"+scope).toByteArray()).joinToString(""){"%02x".format(it)}}
 @Synchronized private fun key():SecretKey {val k=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};return (k.getKey("rehaflow-drafts-v4",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("rehaflow-drafts-v4",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()}
 fun read(scope:String):JSONObject? {val id=slot(scope);val p=context.getSharedPreferences("private-drafts-v4",Context.MODE_PRIVATE);val raw=p.getString(id,null)?:return null;val e=JSONObject(raw);if(scope!="pending"&&System.currentTimeMillis()-e.getLong("at")>7*86400000L){p.edit().remove(id).commit();return null};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(e.getString("iv"),Base64.NO_WRAP)));c.updateAAD(id.toByteArray());return JSONObject(String(c.doFinal(Base64.decode(e.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8))}
 fun save(scope:String,value:JSONObject){val id=slot(scope);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(id.toByteArray());val e=JSONObject().put("at",System.currentTimeMillis()).put("iv",Base64.encodeToString(c.iv,Base64.NO_WRAP)).put("data",Base64.encodeToString(c.doFinal(value.toString().toByteArray()),Base64.NO_WRAP));check(context.getSharedPreferences("private-drafts-v4",Context.MODE_PRIVATE).edit().putString(id,e.toString()).commit()){ "Не вдалося зберегти чернетку" }}
 fun remove(scope:String){check(context.getSharedPreferences("private-drafts-v4",Context.MODE_PRIVATE).edit().remove(slot(scope)).commit())}
}
class LocalDraft(val scope:String){
 private var blocked=false
 var error by mutableStateOf("");private set
 private var data=try{DraftStore.read(scope)?:JSONObject()}catch(e:Exception){blocked=true;error="Не вдалося відновити чернетку. Не надсилайте повторно непідтверджений запис.";JSONObject()}
 val id:String=data.optString("request_id").ifEmpty{java.util.UUID.randomUUID().toString()}
 fun get(k:String,default:String=""):String {if(!data.has(k)&&!blocked)set(k,default);return data.optString(k,default)}
 fun set(k:String,value:String){if(blocked)return;data.put(k,value).put("request_id",id);try{DraftStore.save(scope,data);error=""}catch(e:Exception){error="Чернетку не збережено на пристрої"}}
}
@Composable fun rememberDraft(scope:String)=remember(NativeSession.server,NativeSession.userId,scope){LocalDraft(scope)}
@Composable fun draftText(d:LocalDraft,key:String,default:String=""):MutableState<String>{val state=remember(d,key){mutableStateOf(d.get(key,default))};return remember(d,key){object:MutableState<String>{override var value:String get()=state.value;set(v){state.value=v;d.set(key,v)};override fun component1()=value;override fun component2():(String)->Unit={value=it}}}}
@Composable fun DraftNotice(d:LocalDraft){Text(if(d.error.isEmpty())"Чернетка на цьому пристрої · 7 днів. Після відновлення перевірте пацієнта й час." else d.error,color=if(d.error.isEmpty())MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}

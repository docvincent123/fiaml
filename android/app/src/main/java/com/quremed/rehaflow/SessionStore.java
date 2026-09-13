package com.quremed.rehaflow;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
/** Encrypted session token only. Clinical data and passwords are never stored here. */
public final class SessionStore {
 private static final String ALIAS="rehaflow-session-v1";
 private static SecretKey key() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)){KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
  return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
 }
 public static synchronized void save(Context c){
  if(NativeSession.token.isEmpty()){clear(c);return;}
  try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] data=cipher.doFinal(new JSONObject().put("server",NativeSession.server).put("token",NativeSession.token).put("user",NativeSession.userId).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
   c.getSharedPreferences(ALIAS,0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("data",Base64.encodeToString(data,Base64.NO_WRAP)).commit();
  }catch(Exception e){clear(c);}
 }
 public static synchronized void restore(Context c){try{
  android.content.SharedPreferences p=c.getSharedPreferences(ALIAS,0);String stored=p.getString("data","");if(stored.isEmpty())return;
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
  JSONObject value=new JSONObject(new String(cipher.doFinal(Base64.decode(stored,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
  if(value.getString("server").equals(NativeSession.server)){NativeSession.token=value.getString("token");NativeSession.userId=value.getString("user");}else clear(c);
 }catch(Exception e){clear(c);}}
 public static void clear(Context c){c.getSharedPreferences(ALIAS,0).edit().clear().commit();}
}

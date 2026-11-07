package com.lifeatlas.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(context:Context) {
    val prefs=context.getSharedPreferences("atlas_settings",Context.MODE_PRIVATE)
    private fun secret():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("atlas-settings",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("atlas-settings",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    fun setSecret(name:String,value:String) {val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret());prefs.edit().putString(name,Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)).apply()}
    fun getSecret(name:String):String=runCatching {val data=Base64.decode(prefs.getString(name,null) ?: return "",Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,data.copyOfRange(0,12)));String(cipher.doFinal(data.copyOfRange(12,data.size)))}.getOrDefault("")
    fun ai()=AiConfig(prefs.getString("endpoint","").orEmpty(),getSecret("api_key"),prefs.getString("model","").orEmpty())
    fun saveAi(endpoint:String,model:String,key:String) {require(endpoint.startsWith("https://") && model.isNotBlank());prefs.edit().putString("endpoint",endpoint.trim()).putString("model",model.trim()).apply();setSecret("api_key",key.trim())}
}

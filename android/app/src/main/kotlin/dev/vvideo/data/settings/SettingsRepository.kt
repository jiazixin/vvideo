package dev.vvideo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("connection")
data class Connection(val address: String = "", val token: String = "", val budget: Long = 5_000_000_000L, val lastDirectory: Long = 0L) {
 val configured: Boolean get() = address.isNotBlank() && token.isNotBlank()
 // Connection is immutable. Compute its cache/bookmark namespace once rather
 // than constructing a digest and 32 formatters during UI recomposition.
 val id: String = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
}
class SettingsRepository(private val context: Context) {
 private val address = stringPreferencesKey("address")
 private val token = stringPreferencesKey("token")
 private val budget = longPreferencesKey("budget")
 private val themeKey = stringPreferencesKey("theme")
 val theme = context.settings.data.map { it[themeKey] ?: "system" }
 suspend fun setTheme(value: String) { require(value in setOf("system", "light", "dark")); context.settings.edit { it[themeKey] = value } }
 private val recent = longPreferencesKey("recent")
 val connection = context.settings.data.map { Connection(it[address].orEmpty(), it[token].orEmpty(), it[budget] ?: 5_000_000_000L, it[recent] ?: 0L) }
 fun candidate(url:String,credential:String):Connection {
  val canonical=ConnectionPolicy.canonical(url);require(credential.isNotBlank());return Connection(canonical,credential)
 }
 suspend fun connect(url: String, credential: String) {
  val canonical = ConnectionPolicy.canonical(url)
  require(credential.isNotBlank())
  context.settings.edit { if (it[address] != canonical) it.remove(recent); it[address] = canonical; it[token] = credential }
 }
 suspend fun setBudget(bytes: Long) { require(bytes in setOf(1_000_000_000L, 5_000_000_000L, 10_000_000_000L)); context.settings.edit { it[budget] = bytes } }
 suspend fun recent(directory: Long) { context.settings.edit { it[recent] = directory } }
}

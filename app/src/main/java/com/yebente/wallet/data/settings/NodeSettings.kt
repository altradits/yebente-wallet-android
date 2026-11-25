package com.yebente.wallet.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yebente.wallet.domain.protocol.PromiseCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nodeStore: DataStore<Preferences> by preferencesDataStore(name = "node_settings")

data class NodeConfig(
  val backendUrl: String = DEFAULT_BACKEND_URL,
  val nodeType: String = NODE_NONE,
  val nodeUrl: String = "",
  val lightningAddress: String = "",
  val addressIndex: Int = 0,
  val outboundSeq: Long = 0L,
  val outboundHash: String = PromiseCodec.GENESIS_HASH,
  val anchorSats: Long = 0L,
) {
  fun hasNode(): Boolean =
    nodeType != NODE_NONE && nodeUrl.isNotBlank() && nodeKeyPresent

  var nodeKeyPresent: Boolean = false
    private set

  fun withNodeKeyPresent(present: Boolean): NodeConfig {
    val copy = copy()
    copy.nodeKeyPresent = present
    return copy
  }

  companion object {
    const val DEFAULT_BACKEND_URL = "https://yebente.onrender.com"
    const val NODE_NONE = "none"
    const val NODE_PHOENIXD = "phoenixd"
    const val NODE_LNBITS = "lnbits"
    const val NODE_LND = "lnd"
  }
}

interface NodeCredentials {
  suspend fun current(): NodeConfig
  fun nodeKey(): String
}

class NodeSettings(private val context: Context) : NodeCredentials {
  private val backendUrlKey = stringPreferencesKey("backend_url")
  private val nodeTypeKey = stringPreferencesKey("node_type")
  private val nodeUrlKey = stringPreferencesKey("node_url")
  private val lightningKey = stringPreferencesKey("lightning_address")
  private val addressIndexKey = longPreferencesKey("address_index")
  private val outboundSeqKey = longPreferencesKey("outbound_seq")
  private val outboundHashKey = stringPreferencesKey("outbound_hash")
  private val anchorKey = longPreferencesKey("anchor_sats")

  private val secrets: SharedPreferences by lazy {
    EncryptedSharedPreferences.create(
      context.applicationContext,
      SECRETS_NAME,
      MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build(),
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  val config: Flow<NodeConfig> = context.nodeStore.data.map { prefs ->
    NodeConfig(
      backendUrl = prefs[backendUrlKey] ?: NodeConfig.DEFAULT_BACKEND_URL,
      nodeType = prefs[nodeTypeKey] ?: NodeConfig.NODE_NONE,
      nodeUrl = prefs[nodeUrlKey] ?: "",
      lightningAddress = prefs[lightningKey] ?: "",
      addressIndex = (prefs[addressIndexKey] ?: 0L).toInt(),
      outboundSeq = prefs[outboundSeqKey] ?: 0L,
      outboundHash = prefs[outboundHashKey] ?: PromiseCodec.GENESIS_HASH,
      anchorSats = prefs[anchorKey] ?: 0L,
    ).withNodeKeyPresent(nodeKey().isNotBlank())
  }

  override suspend fun current(): NodeConfig = config.first()

  override fun nodeKey(): String = secrets.getString(KEY_NODE_KEY, "") ?: ""

  suspend fun saveConnection(backendUrl: String, nodeType: String, nodeUrl: String, nodeKey: String) {
    secrets.edit().putString(KEY_NODE_KEY, nodeKey.trim()).apply()
    context.nodeStore.edit { prefs ->
      prefs[backendUrlKey] = backendUrl.trim().trimEnd('/')
      prefs[nodeTypeKey] = nodeType
      prefs[nodeUrlKey] = nodeUrl.trim().trimEnd('/')
    }
  }

  suspend fun saveLightningAddress(value: String) {
    context.nodeStore.edit { prefs -> prefs[lightningKey] = value.trim() }
  }

  suspend fun nextAddressIndex(): Int {
    var next = 0
    context.nodeStore.edit { prefs ->
      next = ((prefs[addressIndexKey] ?: 0L) + 1).toInt()
      prefs[addressIndexKey] = next.toLong()
    }
    return next - 1
  }

  suspend fun advanceOutboundChain(seq: Long, hash: String) {
    context.nodeStore.edit { prefs ->
      prefs[outboundSeqKey] = seq
      prefs[outboundHashKey] = hash
    }
  }

  suspend fun adjustAnchor(deltaSats: Long) {
    context.nodeStore.edit { prefs ->
      val next = maxOf(0L, (prefs[anchorKey] ?: 0L) + deltaSats)
      prefs[anchorKey] = next
    }
  }

  suspend fun wipeSecrets() {
    secrets.edit().clear().apply()
    context.nodeStore.edit { prefs ->
      prefs.remove(nodeTypeKey)
      prefs.remove(nodeUrlKey)
      prefs.remove(lightningKey)
      prefs.remove(outboundSeqKey)
      prefs.remove(outboundHashKey)
      prefs.remove(anchorKey)
      prefs.remove(addressIndexKey)
    }
  }

  suspend fun resetChainState() {
    context.nodeStore.edit { prefs ->
      prefs.remove(outboundSeqKey)
      prefs.remove(outboundHashKey)
      prefs.remove(anchorKey)
      prefs.remove(addressIndexKey)
    }
  }

  companion object {
    private const val SECRETS_NAME = "yebente_encrypted_prefs"
    private const val KEY_NODE_KEY = "node_key"
  }
}

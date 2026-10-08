package com.minibrain.ai.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 回答などに使う LLM。E2B が速さ優先の既定、E4B は精度優先（ADR-057）。
 * E4B は E2B より 2〜3 倍遅く、時系列や複数ノートをまたぐ質問に強い（ADR-056）。
 */
enum class LlmModel(
    val fileName: String,
    val url: String,
    val sha256: String,
    /** これより小さいファイルは途中までのダウンロードとみなす */
    val minSize: Long,
) {
    E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minSize = 2_000_000_000L, // 実際は約 2.6GB
    ),
    E4B(
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        minSize = 3_000_000_000L, // 実際は約 3.7GB
    );

    companion object {
        val DEFAULT = E2B

        fun fromName(name: String?): LlmModel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

private val PREF_LLM_MODEL = stringPreferencesKey("llm_model")

fun DataStore<Preferences>.selectedLlmModelFlow(): Flow<LlmModel> =
    data.map { LlmModel.fromName(it.asMap()[PREF_LLM_MODEL] as? String) }

suspend fun DataStore<Preferences>.selectedLlmModel(): LlmModel = selectedLlmModelFlow().first()

suspend fun DataStore<Preferences>.setSelectedLlmModel(model: LlmModel) {
    edit { it[PREF_LLM_MODEL] = model.name }
}

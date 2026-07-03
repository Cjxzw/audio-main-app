package com.agent.voiceassistant.model

import android.content.Context

/**
 * 模型路径管理。
 *
 * 支持两种模式：
 * 1. **Assets 打包模式**（默认，推荐）：模型放在 `src/main/assets/models/` 下，
 *    通过 AssetManager 加载，无需运行时下载
 * 2. **外存模式**（保留）：模型放在外部存储，运行时下载（仅用于开发调试）
 *
 * Assets 模式目录结构：
 * ```
 * src/main/assets/models/
 * ├── paraformer/
 * │   ├── model.int8.onnx
 * │   └── tokens.txt
 * ├── piper/
 * │   ├── zh_CN-huayan-medium.onnx
 * │   ├── tokens.txt
 * │   └── espeak-ng-data/
 * └── vad/
 *     └── silero_vad.onnx
 * ```
 */
data class ModelPaths(
    val rootDir: String,
    val asrDir: String,
    val ttsDir: String,
    val vadDir: String
) {
    val asrModel: String get() = "$asrDir/model.int8.onnx"
    val asrTokens: String get() = "$asrDir/tokens.txt"
    val ttsModel: String get() = "$ttsDir/zh_CN-huayan-medium.onnx"
    val ttsTokens: String get() = "$ttsDir/tokens.txt"
    val ttsEspeakDataDir: String get() = "$ttsDir/espeak-ng-data"
    val vadModel: String get() = "$vadDir/silero_vad.onnx"

    companion object {
        /** Assets 模式：路径相对于 assets 根目录 */
        fun fromAssets(): ModelPaths {
            val root = "models"
            return ModelPaths(
                rootDir = root,
                asrDir = "$root/paraformer",
                ttsDir = "$root/piper",
                vadDir = "$root/vad"
            )
        }

        /** 外存模式：模型在外部存储（开发调试用） */
        fun fromContext(ctx: Context): ModelPaths {
            val base = ctx.getExternalFilesDir(null)?.absolutePath
                ?: ctx.filesDir.absolutePath
            val root = "$base/models"
            return ModelPaths(
                rootDir = root,
                asrDir = "$root/paraformer",
                ttsDir = "$root/piper",
                vadDir = "$root/vad"
            )
        }
    }
}

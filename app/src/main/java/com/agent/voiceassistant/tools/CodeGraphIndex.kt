package com.agent.voiceassistant.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Read-only, bounded queries over the Graphify snapshot bundled with the APK. */
class CodeGraphIndex(context: Context) {

    private data class Node(
        val id: String,
        val label: String,
        val sourceFile: String,
        val sourceLocation: String,
        val community: String,
    )

    private data class Link(
        val source: String,
        val target: String,
        val relation: String,
    )

    private val assets = context.assets
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded by lazy { loadSnapshot() }

    fun search(query: String, limit: Int = 8): String {
        val snapshot = loaded ?: return unavailable()
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isBlank()) return "代码图谱查询失败：缺少查询词。"
        val terms = terms(normalizedQuery)
        val ranked = snapshot.nodes
            .map { node -> node to score(node, normalizedQuery, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Node, Int>> { it.second }.thenBy { it.first.sourceFile })
            .take(limit.coerceIn(1, 12))

        if (ranked.isEmpty()) return "代码图谱中没有找到与“$query”直接相关的节点。"
        return buildString {
            appendLine("代码图谱查询结果（只读导航，最终结论需核对源码或日志）：")
            ranked.forEachIndexed { index, (node, score) ->
                appendLine("${index + 1}. ${node.label} [匹配度 $score]")
                appendLine("   文件：${node.sourceFile} ${node.sourceLocation}")
                if (node.community.isNotBlank()) appendLine("   子系统：${node.community}")
            }
        }.trim()
    }

    fun explain(symbol: String): String {
        val snapshot = loaded ?: return unavailable()
        val query = symbol.trim().lowercase(Locale.ROOT)
        if (query.isBlank()) return "代码图谱解释失败：缺少符号名。"
        val node = snapshot.nodes
            .sortedBy { if (it.label.equals(symbol.trim(), ignoreCase = true)) 0 else 1 }
            .firstOrNull { it.label.lowercase(Locale.ROOT).contains(query) }
            ?: return "代码图谱中没有找到符号“$symbol”。"
        val neighbors = snapshot.links
            .asSequence()
            .filter { it.source == node.id || it.target == node.id }
            .take(20)
            .mapNotNull { link ->
                val otherId = if (link.source == node.id) link.target else link.source
                snapshot.byId[otherId]?.let { other ->
                    val direction = if (link.source == node.id) "->" else "<-"
                    "$direction ${link.relation} ${other.label} (${other.sourceFile} ${other.sourceLocation})"
                }
            }
            .toList()
        return buildString {
            appendLine("符号：${node.label}")
            appendLine("位置：${node.sourceFile} ${node.sourceLocation}")
            if (node.community.isNotBlank()) appendLine("子系统：${node.community}")
            if (neighbors.isNotEmpty()) {
                appendLine("关联节点：")
                neighbors.forEach { appendLine("- $it") }
            }
            append("图谱结果仅用于定位，修改或定论前必须读取对应源码和运行日志。")
        }
    }

    private fun score(node: Node, query: String, terms: List<String>): Int {
        val label = node.label.lowercase(Locale.ROOT)
        val file = node.sourceFile.lowercase(Locale.ROOT)
        val direct = if (label == query) 100 else if (label.contains(query)) 40 else 0
        val termScore = terms.fold(0) { total, term ->
            total + when {
                label == term -> 24
                label.contains(term) -> 12
                file.contains(term) -> 6
                else -> 0
            }
        }
        return direct + termScore
    }

    private fun terms(query: String): List<String> = Regex("[\\p{L}\\p{N}_.$-]+")
        .findAll(query)
        .map { it.value }
        .filter { it.length >= 2 }
        .distinct()
        .toList()

    private fun unavailable(): String =
        "代码图谱暂不可用：APK 中没有找到与当前源码版本匹配的 Graphify 快照。"

    private fun loadSnapshot(): Snapshot? = runCatching {
        val rawBytes = runCatching { assets.open(GRAPH_ASSET_GZIP).use { it.readBytes() } }
            .recoverCatching { assets.open(GRAPH_ASSET_JSON).use { it.readBytes() } }
            .getOrThrow()
        val jsonText = if (rawBytes.take(2) == listOf(0x1f.toByte(), 0x8b.toByte())) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.bufferedReader().readText() }
        } else {
            rawBytes.toString(Charsets.UTF_8)
        }
        val root = json.parseToJsonElement(jsonText).jsonObject
        val nodes = (root["nodes"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            Node(
                id = obj.string("id") ?: return@mapNotNull null,
                label = obj.string("label") ?: return@mapNotNull null,
                sourceFile = obj.string("source_file").orEmpty(),
                sourceLocation = obj.string("source_location").orEmpty(),
                community = obj.string("community_name").orEmpty(),
            )
        }
        val links = (root["links"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            Link(
                source = obj.string("source") ?: return@mapNotNull null,
                target = obj.string("target") ?: return@mapNotNull null,
                relation = obj.string("relation").orEmpty(),
            )
        }
        Snapshot(nodes, links, nodes.associateBy { it.id })
    }.getOrNull()

    private data class Snapshot(
        val nodes: List<Node>,
        val links: List<Link>,
        val byId: Map<String, Node>,
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private companion object {
        private const val GRAPH_ASSET_GZIP = "codegraph/graph.json.gz"
        private const val GRAPH_ASSET_JSON = "codegraph/graph.json"
    }
}

package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

data class ProductInfo(
    val id: String,
    val title: String,
    val platform: String,
    val price: Double,
    val originalPrice: Double? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val sales: Int? = null,
    val tags: List<String> = emptyList(),
    val url: String = "",
)

data class ProductComparison(
    val products: List<ProductInfo>,
    val bestValue: ProductInfo?,
    val cheapest: ProductInfo?,
    val highestRated: ProductInfo?,
    val mostReviewed: ProductInfo?,
    val summary: String,
)

object ProductAnalyzer {

    fun compare(products: List<ProductInfo>): ProductComparison {
        val nonEmpty = products.filter { it.price > 0 }
        fun maxByOrNull(selector: (ProductInfo) -> Double?): ProductInfo? =
            nonEmpty.filter { selector(it) != null }.maxByOrNull { selector(it)!! }
        return ProductComparison(
            products = products,
            bestValue = nonEmpty.minByOrNull { it.price / (it.rating ?: 1.0).coerceAtLeast(0.1) },
            cheapest = nonEmpty.minByOrNull { it.price },
            highestRated = maxByOrNull { it.rating },
            mostReviewed = maxByOrNull { (it.reviewCount ?: 0).toDouble() },
            summary = buildSummary(products),
        )
    }

    fun parseProducts(json: String): List<ProductInfo> {
        val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val price = obj.optDouble("price", Double.NaN)
                if (price.isNaN() || price < 0) continue
                val tags = obj.optJSONArray("tags")?.let { arr ->
                    buildList {
                        for (j in 0 until arr.length()) add(arr.optString(j))
                    }
                } ?: emptyList()
                add(
                    ProductInfo(
                        id = obj.optString("id", "p$i"),
                        title = obj.optString("title"),
                        platform = obj.optString("platform"),
                        price = price,
                        originalPrice = if (obj.has("originalPrice") && !obj.isNull("originalPrice")) obj.optDouble("originalPrice") else null,
                        rating = if (obj.has("rating") && !obj.isNull("rating")) obj.optDouble("rating") else null,
                        reviewCount = if (obj.has("reviewCount") && !obj.isNull("reviewCount")) obj.optInt("reviewCount") else null,
                        sales = if (obj.has("sales") && !obj.isNull("sales")) obj.optInt("sales") else null,
                        tags = tags,
                        url = obj.optString("url"),
                    ),
                )
            }
        }
    }

    private fun buildSummary(products: List<ProductInfo>): String {
        if (products.isEmpty()) return "没有可比较的商品"
        val cheapest = products.minByOrNull { it.price }
        val topRated = products.filter { it.rating != null }.maxByOrNull { it.rating!! }
        return buildString {
            append("共 ${products.size} 个商品。")
            cheapest?.let { append("最低价 ${it.platform} ${it.title} ${it.price} 元；") }
            topRated?.let { append("评分最高 ${it.platform} ${it.title} ${it.rating} 分。") }
        }
    }
}

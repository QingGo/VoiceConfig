package com.voiceconfig.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingResearchTest {

    @Test
    fun `product analyzer picks cheapest and highest rated`() {
        val products = listOf(
            ProductInfo("a", "A", "京东", 100.0, rating = 4.5, reviewCount = 10),
            ProductInfo("b", "B", "淘宝", 80.0, rating = 4.8, reviewCount = 20),
            ProductInfo("c", "C", "拼多多", 120.0, rating = 4.2, reviewCount = 5),
        )
        val comparison = ProductAnalyzer.compare(products)
        assertEquals("b", comparison.cheapest?.id)
        assertEquals("b", comparison.highestRated?.id)
        assertEquals("b", comparison.bestValue?.id)
        assertTrue(comparison.summary.contains("3 个商品"))
    }

    @Test
    fun `product compare tool parses json and returns summary`() = runBlocking {
        val tool = ProductCompareTool()
        val json = """
            [
              {"title":"奶粉1","platform":"京东","price":200,"rating":4.7,"reviewCount":100},
              {"title":"奶粉2","platform":"淘宝","price":180,"rating":4.5,"reviewCount":80}
            ]
        """.trimIndent()
        val result = tool.execute(mapOf("products" to json))
        assertTrue(result.ok)
        val products = result.data["products"] as? List<*>
        assertEquals(2, products?.size)
        assertTrue(result.message.contains("最低价"))
    }

    @Test
    fun `parse flexible products from object with products array`() {
        val json = """
            {"products":[
              {"id":"x1","title":"A","platform":"JD","price":10},
              {"id":"x2","title":"B","platform":"TB","price":9}
            ]}
        """.trimIndent()
        val products = ProductAnalyzer.parseProductsFlexible(json)
        assertEquals(2, products.size)
        assertEquals("x2", products[1].id)
    }

    @Test
    fun `product compare tool rejects empty products`() = runBlocking {
        val tool = ProductCompareTool()
        val result = tool.execute(mapOf("products" to "[]"))
        assertTrue(!result.ok)
        assertNotNull(result.message)
    }
}

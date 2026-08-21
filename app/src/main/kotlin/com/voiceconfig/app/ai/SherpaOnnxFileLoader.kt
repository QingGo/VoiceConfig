package com.voiceconfig.app.ai

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig

/**
 * sherpa-onnx 的 Java/Kotlin API 只公开了从 assets 加载的构造函数，
 * 但底层 native 其实也提供了 newFromFile。这里通过反射调用它，
 * 从而支持从 filesDir 等运行时下载路径加载模型。
 */
object SherpaOnnxFileLoader {

    fun newOnlineRecognizerFromFile(config: OnlineRecognizerConfig): OnlineRecognizer {
        val instance = allocateInstance(OnlineRecognizer::class.java) as OnlineRecognizer
        val method = OnlineRecognizer::class.java.getDeclaredMethod("newFromFile", OnlineRecognizerConfig::class.java)
        method.isAccessible = true
        val ptr = method.invoke(instance, config) as Long
        setPtr(instance, "ptr", ptr)
        return instance
    }

    fun newOfflineRecognizerFromFile(config: OfflineRecognizerConfig): OfflineRecognizer {
        val instance = allocateInstance(OfflineRecognizer::class.java) as OfflineRecognizer
        val method = OfflineRecognizer::class.java.getDeclaredMethod("newFromFile", OfflineRecognizerConfig::class.java)
        method.isAccessible = true
        val ptr = method.invoke(instance, config) as Long
        setPtr(instance, "ptr", ptr)
        return instance
    }

    private fun allocateInstance(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, clazz)
    }

    private fun setPtr(instance: Any, fieldName: String, value: Long) {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setLong(instance, value)
    }
}

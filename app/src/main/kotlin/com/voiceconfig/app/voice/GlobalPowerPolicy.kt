package com.voiceconfig.app.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局功耗策略。
 *
 * 控制是否允许系统级聆听：
 * - 屏幕关闭时暂停；
 * - 低电量时自动关闭全局聆听；
 * - 亮屏 / 充电恢复后重新允许。
 */
@Singleton
class GlobalPowerPolicy @Inject constructor() {

    @Volatile
    var screenOff: Boolean = false
        private set

    @Volatile
    var lowBattery: Boolean = false
        private set

    fun canListen(): Boolean = !screenOff && !lowBattery

    fun setScreenOff(value: Boolean) {
        screenOff = value
    }

    fun setLowBattery(value: Boolean) {
        lowBattery = value
    }
}

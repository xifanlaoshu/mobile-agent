package com.xhs.agent

import android.app.Application
import android.util.Log

/**
 * 应用入口
 *
 * 负责：
 * - 初始化全局组件
 * - 管理应用生命周期
 * - 提供依赖注入（简易版，不使用 DI 框架以减小 APK 体积）
 */
class XhsAgentApp : Application() {

    companion object {
        private const val TAG = "XhsAgentApp"
        lateinit var instance: XhsAgentApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "XhsAgent 初始化完成")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "XhsAgent 终止")
    }
}

package com.lfork.phonelimitadvanced.limit

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.lfork.phonelimitadvanced.LimitApplication
import com.lfork.phonelimitadvanced.base.AppConstants
import com.lfork.phonelimitadvanced.data.appinfo.AppInfoRepository
import com.lfork.phonelimitadvanced.main.MainActivity
import com.lfork.phonelimitadvanced.utils.PermissionManager.clearDefaultLauncher
import com.lfork.phonelimitadvanced.utils.PermissionManager.clearDefaultLauncherFake

/**
 *
 * Created by 98620 on 2018/12/14.
 */
class Executor(var context: Context?, var limitTask: LimitTask?) {

    lateinit var executorThread: Thread

    var isActive = false

    /**
     * 只能调用一次开始
     */
    fun start(): Boolean {
        if (isActive || context == null) {
            return false
        }
        val executorTask = Runnable {
            isActive = true
            beforeLimitation()
            while (isActive) {
                limitTask?.doLimit()
            }
            releaseLimitation()
            onDestroy()
        }
        executorThread = Thread(executorTask)
        executorThread.name = "限制监督与执行线程"
        executorThread.start()

        return true
    }

    /**
     * 关闭Executor
     */
    fun close() {
        isActive = false

        //尽快结束线程
        executorThread.interrupt()
    }

    /**
     * 进行最后的资源释放
     */
    fun onDestroy() {
        context = null
        limitTask = null
    }

    /**
     * 这个主要是给root用户使用的
     */
    private fun beforeLimitation() {
        context?.let {
            limitTask?.initLimit(it)
        }
    }


    /**
     * 结束限制：时间到了，然后可以选桌面了。
     * 因为Android的运行机制，结束限制需要服务端(Service)
     * 和客户端(Activity)先后调用
     * @see clearDefaultLauncher,
     * @see clearDefaultLauncherFake
     */
    private fun releaseLimitation() {
        limitTask?.closeLimit()
    }


}
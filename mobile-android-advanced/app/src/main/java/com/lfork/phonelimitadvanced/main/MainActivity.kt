package com.lfork.phonelimitadvanced.main

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import com.lfork.phonelimitadvanced.PermissionManager
import com.lfork.phonelimitadvanced.PermissionManager.requestStoragePermission
import com.lfork.phonelimitadvanced.R
import com.lfork.phonelimitadvanced.limit.LimitService
import com.lfork.phonelimitadvanced.util.ToastUtil
import kotlinx.android.synthetic.main.main_act.*
import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.content.ServiceConnection
import android.util.Log
import com.lfork.phonelimitadvanced.limit.LimitStateListener
import com.lfork.phonelimitadvanced.util.SystemToggle


class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_STORAGE_PERMISSION = 0

        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_act)

        // Example of a call to a native method
        // sample_text.text = stringFromJNI()
        btn_start.setOnClickListener {
            if (!TextUtils.isEmpty(editText.text.toString())) {
                startLimit(editText.text.toString().toLong())
            } else{
                startLimit()
            }


        }

        btn_close.setOnClickListener {
            closeLimit()
        }

//        test_recycle.layoutManager = LinearLayoutManager(this)
//        test_recycle.adapter = MyRecycleAdapter()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder = iBinder as LimitService.StateBinder

            SystemToggle.openAirModeSettings(applicationContext)

            binder.getLimitService().listener = object : LimitStateListener {
                override fun onLimitFinished() {
                    SystemToggle.openAirModeSettings(applicationContext)
                }

                override fun onLimitStarted() {
                    SystemToggle.openAirModeSettings(applicationContext)
                }

                override fun autoUnlocked(msg: String) {
                    runOnUiThread {
                        remain_time_text.text = msg
                        closeLimit()
                    }
                }

                override fun forceUnlocked(msg: String) {
                    runOnUiThread {
                        remain_time_text.text = msg
                        closeLimit()
                    }
                }

                override fun remainTimeRefreshed(timeSeconds: Long) {
                    runOnUiThread {
                        //刷新剩余时间
                        Log.d("timeTest", "剩余时间${timeSeconds}秒")
                        remain_time_text.text = "剩余时间${timeSeconds}秒"
                    }
                }
            };
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
        }
    }

    private fun startLimit(limitTime: Long = 1L) {
        bindService()
        if (!PermissionManager.isGrantedStoragePermission(applicationContext)) {
            ToastUtil.showShort(this, "请给与程序需要的权限")
            requestStoragePermission(applicationContext, REQUEST_STORAGE_PERMISSION, this);
            return
        }

        if (!PermissionManager.getRootAhth()) {
            PermissionManager.getRootPermission(packageCodePath)

            if (!PermissionManager.getRootAhth()) {
                ToastUtil.showShort(this, "请授予Root权限")
                return
            }
        }

        val startIntent = Intent(this, LimitService::class.java)
        startIntent.putExtra("limit_time", limitTime)
        this.startService(startIntent)
    }

    private fun closeLimit() {
        unBindService()
        val stopIntent = Intent(this, LimitService::class.java)
        this.stopService(stopIntent)
    }

    private fun checkAndRecoveryUnfinishedLimit() {
        //
    }

    private fun bindService() {
        val bindIntent = Intent(this, LimitService::class.java)
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun unBindService() {
        unbindService(connection)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (PermissionManager.isGrantedStoragePermission(applicationContext)) {
                ToastUtil.showShort(applicationContext, "获取文件访问权限成功")
                if (!TextUtils.isEmpty(editText.text.toString())) {
                    startLimit(editText.text.toString().toLong())
                } else{
                    startLimit()
                }
            }
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String
}

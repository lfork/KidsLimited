package com.lfork.phonelimit.limitcore.task

 import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.lfork.phonelimit.R
import com.lfork.phonelimit.base.AppConstants
import com.lfork.phonelimit.data.appinfo.AppInfoRepository
import com.lfork.phonelimit.view.main.MainActivity
import com.lfork.phonelimit.MainHandler
 import com.lfork.phonelimit.limitcore.LimitEnvironment.isOnRecentApps
 import com.lfork.phonelimit.utils.Constants


/**
 * Created by L.Fork
 *
 * @author lfork@vip.qq.com
 * @date 2019/02/07 17:35
 *
 * 只做悬浮窗
 *
 */
open class FloatingLimitTask : BaseLimitTask(), RecentlyReceiver.SystemKeyListener {

    private var wmParams: WindowManager.LayoutParams? = null
    private var mWindowManager: WindowManager? = null
    private var mWindowView: View? = null
    private var tips: TextView? = null

    var mReceiver: RecentlyReceiver? = null

    override fun initLimit(context: Context) {
        super.initLimit(context)
        mContext = context
        initWindowParams()
        initView()
        mReceiver = RecentlyReceiver()
        mReceiver?.registerKeyListener(this)
        mContext!!.registerReceiver(mReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }


    private fun initWindowParams() {
        if (mContext == null) {
            return
        }

        mWindowManager = mContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        wmParams = WindowManager.LayoutParams()
        // 更多type：https://developer.android.com/reference/android/view/WindowManager.LayoutParams.html#TYPE_PHONE
        //        wmParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        wmParams?.let {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                it.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                it.type = WindowManager.LayoutParams.TYPE_PHONE
            }

            it.format = PixelFormat.TRANSLUCENT
            // 更多falgs:https://developer.android.com/reference/android/view/WindowManager.LayoutParams.html#FLAG_NOT_FOCUSABLE
            //wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            it.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            it.gravity = Gravity.START or Gravity.TOP
            it.width = WindowManager.LayoutParams.MATCH_PARENT
            it.height = WindowManager.LayoutParams.MATCH_PARENT
        }
    }

    private fun initView() {
        mWindowView = LayoutInflater.from(mContext).inflate(R.layout.focus_window_tips, null)
        tips = mWindowView?.findViewById(R.id.tv_windows_tips)
        tips?.setOnClickListener {
            val intent = Intent(mContext!!, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            mContext!!.startActivity(intent)
        }
    }

    var viewIsAdded = false

    @Synchronized
    private fun addWindowView() {

        if (mWindowView!!.isAttachedToWindow) {
            return
        }
        if (viewIsAdded) {
            return
        }
        viewIsAdded = true
        MainHandler.getInstance().post {
            if (mWindowView!!.isAttachedToWindow) {
                return@post
            }
            try {
                mWindowManager?.addView(mWindowView, wmParams)
            } catch (e:Exception){
                e.printStackTrace()
            }
        }

    }

    private fun removeWindow() {
        if (viewIsAdded) {
            MainHandler.getInstance().post {
                if (mWindowView!!.isAttachedToWindow) {
                    mWindowManager?.removeView(mWindowView)
                    viewIsAdded = false
                }
            }
        }
    }

    @Synchronized
    override fun doLimit(): Boolean {

        if (mContext == null) {
            return false
        }

        if (!started){
            val intent = Intent(mContext!!, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            mContext!!.startActivity(intent)
            started = true
            return true
        }

        //获取栈顶app的包名
        val packageName = getTopRunningApp(
            mContext!!,
            mContext!!.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        )

        if (packageName.isEmpty()) {
            return false
        }

        Log.d("当前包名", packageName + "  ")


        try {
            Thread.sleep(300)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        if (AppInfoRepository.whiteNameList.contains(packageName) || Constants.SPECIAL_WHITE_NAME_LIST.contains(
                packageName
            )
        ) {
            if (!isOnRecentApps){
                removeWindow()
            }
            return false
        }
        addWindowView()
        val intent = Intent(mContext!!, MainActivity::class.java)
        intent.putExtra(AppConstants.LOCK_PACKAGE_NAME, packageName)
        intent.putExtra(AppConstants.LOCK_FROM, AppConstants.LOCK_FROM_FINISH)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        mContext!!.startActivity(intent)
        return true
    }


    override fun onRecentAppsClicked() {
        isOnRecentApps = true
        addWindowView()
    }

    override fun onHomeKeyClicked() {
        isOnRecentApps = true
        addWindowView()
    }


    override fun closeLimit() {
        mContext?.unregisterReceiver(mReceiver)
        mReceiver?.unregisterKeyListener()
        removeWindow()
        mContext = null
    }

}
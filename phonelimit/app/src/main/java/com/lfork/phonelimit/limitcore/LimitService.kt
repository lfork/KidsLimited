package com.lfork.phonelimit.limitcore

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.hjq.toast.ToastUtils
import com.lfork.phonelimit.LimitApplication
import com.lfork.phonelimit.R
import com.lfork.phonelimit.data.*
import com.lfork.phonelimit.data.taskconfig.TaskConfig
import com.lfork.phonelimit.data.taskconfig.TaskConfigRepository
import com.lfork.phonelimit.data.taskconfig.TaskConfig.Companion.CYCLE_MODEL_NO_CYCLE
import com.lfork.phonelimit.limitcore.task.*
import com.lfork.phonelimit.view.main.MainActivity
import com.lfork.phonelimit.base.permission.PermissionManager
import java.lang.Exception
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap


/**
 * 模拟CS模式,Activity作为客户端，Service作为服务端
 * activity 需要在服务结束(通过binder来通知activity)后关闭服务。
 */
class LimitService : Service() {

    private var listener: StateListener? = null

    private val stateBinder = LimitBinder()

    private lateinit var limitTimer: LimitTimer

    private lateinit var limitTaskExecutor: LimitExecutor

    private lateinit var timedTaskExecutor: LimitExecutor

    private var notification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        //判断是否有未执行完的任务->继续执行限制任务  把判断剩余限制任务的逻辑放到服务里面
        checkAndRecoveryLimitTask()
        checkTimedLimitTask()
        //判断是否有定时服务->开启定时服务的监听

        //通知用户 显示通知(开启前台服务)
        showNotification()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent == null) {
            Log.e("onStartCommand", "Intent 不能为空")
            return super.onStartCommand(intent, flags, startId)
        }

//        val commandType = intent.getIntExtra("command_type", 0)

//        if (commandType == COMMAND_COMMIT_TASK ){
        val taskConfig = intent.getSerializableExtra("limit_task_time_info") as TaskConfig?

        if (taskConfig == null) {
            Log.e("onStartCommand", "taskTimeInfo 不能为空")
            return super.onStartCommand(intent, flags, startId)
        }

        if (taskConfig.isImmediatelyExecuted) {
            startLimitTask(taskConfig)
        } else {
            startTimedTask(taskConfig)
        }

//        } else if (commandType == COMMAND_CANCEL_TASK){
//            val taskConfig
//        }


        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? {
        return stateBinder
    }

    /**
     * 将限制状态同步给Activity
     */
    inner class LimitBinder internal constructor() : Binder() {
        fun setLimitStateListener(limitStateListener: StateListener) {
            listener = limitStateListener
        }

        fun closeLimitTask() {
            this@LimitService.closeLimitTask()
        }

        fun closeTimedTask(id: String) {
        }
    }

    /**
     * 现在的任务执行策略是，只执行一个任务，如果后来的任务有冲突的话，那么就会被抛弃
     */
    @Synchronized
    private fun startLimitTask(taskConfig: TaskConfig) {

        //如果限制已开启，那么直接返回
        if (LimitApplication.isOnLimitation) {
            Log.d("startLimit", "限制已开启，当前Task被丢弃")
            return
        }
        val limitTimeSeconds: Long = taskConfig.limitTimeSeconds


        val limitTask: LimitTask =
            when (taskConfig.limitModel) {
                TaskConfig.LIMIT_MODEL_LIGHT -> BaseLimitTask()
                TaskConfig.LIMIT_MODEL_FLOATING -> FloatingLimitTask()
                TaskConfig.LIMIT_MODEL_ULTIMATE -> FloatingLauncherLimitTask()
                TaskConfig.LIMIT_MODEL_ROOT -> RootLimitTask()
                else -> {
                    LauncherLimitTask()
                }
            }

        limitTaskExecutor = LimitExecutor(this, limitTask)

        val timerListener = object : LimitTimer.TimeListener {

            override fun onClosedInAdvance(remainTimeSeconds: Long) {
                limitTaskExecutor.close()
                listener?.onLimitFinished()
                clearCacheLimitTaskInfo()
            }

            override fun onCompleted() {
                //计时器结束时前需要先关闭限制服务，再通知用户
                limitTaskExecutor.close()
                LimitApplication.isOnLimitation = false
                listener?.onLimitFinished()
                clearCacheLimitTaskInfo()
            }

            override fun onRemainTimeRefreshed(remainTimeSeconds: Long) {
                listener?.updateRemainTime(remainTimeSeconds)
            }
        }

        val startTime = if (taskConfig.startTimeMillisForUnfinishedTask > 0) {
            taskConfig.startTimeMillisForUnfinishedTask
        } else {
            System.currentTimeMillis()
        }
        limitTimer = LimitTimer(limitTimeSeconds, timerListener, startTime)

        setLimitTime(limitTimeSeconds)
        //计时器开启前需要先开启限制服务
        //需要先开 limitTaskExecutor ，因为如果时间很短，然后先开的 limitTimer，可能会导致在 limitTaskExecutor 开启之前时间就结束了，然后等下
        //就会执行 limitTaskExecutor，此时就没有人能关闭 limitTaskExecutor 了
        if (!limitTaskExecutor.start()) {
            Log.e("startLimit", "限制任务开启失败:limitTaskExecutor启动失败")
            return
        }

        if (!limitTimer.start()) {
            limitTaskExecutor.close()
            Log.e("startLimit", "限制任务开启失败:limitTimer启动失败")
            return
        }

        LimitApplication.isOnLimitation = true
        listener?.onLimitStarted()
        saveStartTime(startTime)
        saveLimitModel(taskConfig.limitModel)

    }

    /**
     * 以天、周为单位的周期任务
     * 以天为单位的任务：传一个具体的开始时间、限制时间进来    【时间池】
     *
     * //循环任务：【单次任务】  16点开始限制 10分钟 、以天为单位循环。
     *
     * 任务队列：时间有效性检查，如果有任务被延迟，那么当执行这个任务的时候，如果这个任务
     */
    private fun startTimedTask(taskConfig: TaskConfig) {

        if (taskConfig.limitTimeSeconds < 0) {
            return
        }

        val task = Runnable {
            //TODO 权限检查，如果权限不足，就把权限不足的状态写入数据库。然后再toast一下。在任务列表的界面就直接提示权限不足
            if (PermissionManager.modelPermissionCheck(this@LimitService, taskConfig.limitModel)) {
                timedTaskController.remove(taskConfig.id)
                if (timedTaskController.isEmpty()) {
                    LimitApplication.isTimedTaskRunning = false
                }
                startLimitTask(taskConfig)
                if (taskConfig.cycleModel == TaskConfig.CYCLE_MODEL_NO_CYCLE) {
                    taskConfig.isActive = false
                }
                TaskConfigRepository.updateLimitTask(taskConfig)
            } else {
                ToastUtils.show(taskConfig.tips)
            }

        }

        val currentTime = GregorianCalendar()
        val startTime = taskConfig.getStartTimeMillisForTimedTask()
        val delayTime = startTime - currentTime.timeInMillis
        Log.d("延迟时间测试", "当前时间${currentTime.timeInMillis},任务开始时间${startTime} ")

        if (startTime < 0) {
            throw Exception("startTime error!!")
        }

        if (taskConfig.cycleModel == CYCLE_MODEL_NO_CYCLE) {
            //传过来的参数是：【任务开始的时间】，【任务持续的时间】，【任务的重复周期】/不重复
            //Java的Date和Calendar的月份是从0开始计时的
//            taskConfig.startTime.set(2019, 1, 7, 17, 3, 0)

            val controller =
                scheduledThreadPoolExecutor.schedule(task, delayTime, TimeUnit.MILLISECONDS)

            timedTaskController[taskConfig.id] = controller
        } else {

            val cycleTime = when (taskConfig.cycleModel) {
                TaskConfig.CYCLE_MODEL_DAILY -> 24 * 60 * 60 * 1000L
                TaskConfig.CYCLE_MODEL_WEEKLY -> 7 * 24 * 60 * 60 * 1000L
                else -> {
                    24 * 60 * 60 * 1000L
                }
            }

            //如果任务之间的时间有冲突，那么会依次执行
            val controller = scheduledThreadPoolExecutor.scheduleWithFixedDelay(
                task,
                delayTime,
                cycleTime,
                TimeUnit.MILLISECONDS
            )
            timedTaskController[taskConfig.id] = controller
        }

        Log.d(
            "TimedTask",
            "初始化成功  \n限制开始时间${Date(startTime)}\n当前系统时间${Date()} 延迟时间$delayTime "
        )

        LimitApplication.isTimedTaskRunning = true
    }

    private fun closeLimitTask() {
        limitTaskExecutor.close()
    }

    private fun showNotification() {
        //使用前台服务 防止被系统回收 状态栏会显示一个通知

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val NOTIFICATION_CHANNEL_ID = "com.lfork.phonelimit"
            val channelName = "Phone Limit"
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_NONE
            )
            chan.lightColor = Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)

            val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification1)
                .setContentTitle("PhoneLimit 运行中")
                .setContentText("在程序空闲状态下，按返回键可随时退出")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .build()
            startForeground(2, notification)
        } else {
            notification = NotificationCompat.Builder(this)
                .setContentTitle("PhoneLimit 运行中")
                .setContentText("在未开启限制的状态下可以随时关闭PhoneLimit")
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentIntent(pi)
                .build()
            startForeground(1, notification)
        }
    }

    private fun checkAndRecoveryLimitTask() {
        val limitTime = getLimitTime()
        val startTimeMillis = getStartTime()
        val limitModel = getLimitModel()

        val config = TaskConfig().apply {
            limitTimeSeconds = limitTime
            isImmediatelyExecuted = true
            startTimeMillisForUnfinishedTask = startTimeMillis
            this.limitModel = limitModel
        }
        startLimitTask(config)
    }

    private fun checkTimedLimitTask() {
        if (timedTaskCheckFlag) {
            return
        }
        timedTaskCheckFlag = true
        LimitApplication.executeAsyncDataTask {
            val tasks = TaskConfigRepository.getTasks()
            tasks.forEach {
                if (it.isActive) {
                    commitTimedTask(this@LimitService, it)
                    LimitApplication.isTimedTaskRunning = true
                }
            }
        }
    }

    interface StateListener {

        fun updateRemainTime(timeSeconds: Long)

        fun onLimitStarted()

        fun onLimitFinished()
    }

    companion object {
        private val scheduledThreadPoolExecutor = Executors.newScheduledThreadPool(5)
        val timedTaskController = HashMap<String, ScheduledFuture<*>?>()

        var timedTaskCheckFlag = false

        fun startLimit(context: Context, limitTimeSeconds: Long) {
            val taskInfo = TaskConfig().apply {
                this.limitTimeSeconds = limitTimeSeconds
                isImmediatelyExecuted = true
                limitModel = LimitApplication.defaultLimitModel
            }

            //开启之前需要把权限获取到位  不同的限制模式需要不同的权限。
            val limitIntent = Intent(context, LimitService::class.java)
            limitIntent.putExtra("limit_task_time_info", taskInfo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(limitIntent)
            } else {
                context.startService(limitIntent)
            }
        }

        fun commitTimedTask(context: Context, taskConfig: TaskConfig) {
            //开启之前需要把权限获取到位  不同的限制模式需要不同的权限。
            val limitIntent = Intent(context, LimitService::class.java)
            limitIntent.putExtra("limit_task_time_info", taskConfig)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(limitIntent)
            } else {
                context.startService(limitIntent)
            }

        }


        fun cancelTimedTask(id: String): Boolean {
            val control = timedTaskController[id]
            if (control == null) {
                return false
            }
            if (control.cancel(false)) {
                timedTaskController.remove(id)
                if (timedTaskController.isEmpty()) {
                    LimitApplication.isTimedTaskRunning = false
                }
                return true
            }

            return false
        }


        fun taskIsRunning(id: String): Boolean {
            val control = timedTaskController[id]
            return control?.isDone == false && !control.isCancelled
        }

        fun taskIsDone(id: String): Boolean {
            return timedTaskController[id]?.isDone == true
        }

        /**
         * 程序重启后需要重新提交未完成的任务
         */
        fun timedTaskCheck() {

        }

    }
}

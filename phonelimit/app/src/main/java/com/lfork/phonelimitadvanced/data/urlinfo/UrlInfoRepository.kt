package com.lfork.phonelimitadvanced.data.urlinfo

import com.lfork.phonelimitadvanced.LimitApplication
import com.lfork.phonelimitadvanced.LimitApplication.Companion.executeAsyncDataTask
import com.lfork.phonelimitadvanced.data.DataCallback
import com.lfork.phonelimitadvanced.data.LimitDatabase


/**
 *
 * Created by 98620 on 2018/12/8.
 */
object UrlInfoRepository {
    private var mUrlInfoDao: UrlInfoDao = LimitDatabase.getDataBase().urlInfoDao()

    private var whiteNameListCache = ArrayList<UrlInfo>(0)

    fun initUrlData() {
        executeAsyncDataTask {
            if (mUrlInfoDao.getAllAchievedUrl().isEmpty()) {
                mUrlInfoDao.insert(UrlInfo("www.cuit.edu.cn", "成都信息工程大学"))
                mUrlInfoDao.insert(UrlInfo("cn.bing.com", "必应"))
            }
        }
    }


    fun getWhiteNameUrls(callback: DataCallback<List<UrlInfo>>) {

       executeAsyncDataTask {
            whiteNameListCache.clear()
            whiteNameListCache.addAll(mUrlInfoDao.getAllAchievedUrl())
            callback.succeed(whiteNameListCache)
        }

    }

    const val ADD_SUCCEED = 0
    const val DELETE_SUCCEED = 1

    fun addOrDeleteUrl(url: String, callback: DataCallback<String>) {

        LimitApplication.executeAsyncDataTask {
            if (mUrlInfoDao.getUrlInfo(url) != null) {
                mUrlInfoDao.delete(UrlInfo(url))
                callback.succeed("删除成功")
            } else {
                mUrlInfoDao.insert(UrlInfo(url))
                callback.succeed("添加成功")
            }
        }
    }

    fun addUrl(url: String, callback: DataCallback<String>) {
        LimitApplication.executeAsyncDataTask {
            mUrlInfoDao.insert(UrlInfo(url))
            callback.succeed("添加成功")
        }
    }

    /**
     * 添加下次生效的URL
     */
    fun addNextUrl(url: String, callback: DataCallback<String>) {
        LimitApplication.executeAsyncDataTask {
            mUrlInfoDao.insert(UrlInfo(url, isActive = false))
            callback.succeed("添加成功")
        }
    }

    /**
     * 激活由 {@see addNextUrl}添加的URL
     */
    fun activeUrl(url: String, callback: DataCallback<String>) {
        LimitApplication.executeAsyncDataTask {
            mUrlInfoDao.activeUrls()
            callback.succeed("激活成功")
        }
    }

    fun deleteUrl(url: String, callback: DataCallback<String>) {
        LimitApplication.executeAsyncDataTask {
            mUrlInfoDao.delete(UrlInfo(url))
            callback.succeed("删除成功")
        }
    }

    fun contains(url: String): Boolean {
        whiteNameListCache.forEach {
            if (it.url == url) {
                return true
            }
        }
        return false
    }
}
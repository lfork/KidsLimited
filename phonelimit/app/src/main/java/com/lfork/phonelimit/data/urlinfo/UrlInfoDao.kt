package com.lfork.phonelimit.data.urlinfo

import android.arch.persistence.room.*

@Dao
interface UrlInfoDao {
    @Query("SELECT * FROM url_info where is_active=1")
    fun getAllActiveUrl(): List<UrlInfo>

    @Query("SELECT * FROM url_info where url=:url")
    fun getUrlInfo(url:String): UrlInfo?

    @Query("update url_info set is_active=1 WHERE is_active=0")
    fun activeUrls(): Int

    @Update
    fun update(vararg urlInfo: UrlInfo):Int

    @Insert
    fun insert(urlInfo: UrlInfo):Long

//    @Query("delete from url_info  WHERE url=:url")
    @Delete
    fun delete(urlInfo: UrlInfo)

}
package com.lfork.phonelimit.base.widget

import android.content.Context

import com.lfork.phonelimit.R

/**
 * Created by xian on 2017/2/28.
 */

class UsagePermissionDialog(context: Context?) : PermissionDialog(context) {
    override fun getContentViewId(): Int {
        return R.layout.permission_access_usage_dialog
    }
}

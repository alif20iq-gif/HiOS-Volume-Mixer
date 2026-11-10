package com.alif.hiosvolumemixer
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
object RecentApps {
 fun hasAccess(c:Context):Boolean=try{(c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),c.packageName)==AppOpsManager.MODE_ALLOWED}catch(_:Exception){false}
 fun query(c:Context):List<ApplicationInfo>{
  if(!hasAccess(c))return emptyList()
  val now=System.currentTimeMillis()
  val s=try{(c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryUsageStats(UsageStatsManager.INTERVAL_DAILY,now-86400000L,now)}catch(_:Exception){emptyList()}
  return s.orEmpty().filter{it.packageName!=c.packageName&&it.lastTimeUsed>now-86400000L}.groupBy{it.packageName}.mapNotNull{(p,v)->
   val a=try{c.packageManager.getApplicationInfo(p,0)}catch(_:Exception){null}
   if(a!=null&&c.packageManager.getLaunchIntentForPackage(p)!=null)a to v.maxOf{it.lastTimeUsed}else null
  }.sortedByDescending{it.second}.take(40).map{it.first}
 }
}
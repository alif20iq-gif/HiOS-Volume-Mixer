package com.alif.hiosvolumemixer
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors
class OverlayMixerController(private val s:AccessibilityService){
 private val prefs=s.getSharedPreferences("mixer",Context.MODE_PRIVATE)
 private val wm=s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
 private val audio=s.getSystemService(Context.AUDIO_SERVICE) as AudioManager
 private val worker=Executors.newSingleThreadExecutor()
 private var root:android.view.View?=null
 private fun d(v:Int)=(v*s.resources.displayMetrics.density).toInt()
 private fun panel()=LinearLayout(s).apply{orientation=1;setPadding(d(14),d(14),d(10),d(10));background=GradientDrawable().apply{setColor(Color.rgb(249,248,252));cornerRadius=d(22).toFloat()};elevation=d(12).toFloat()}
 fun hide(){root?.let{try{wm.removeView(it)}catch(_:Exception){}};root=null}
 fun show(){if(root==null)drawMixer()}
 fun destroy(){hide();worker.shutdownNow()}
 private fun attach(v:android.view.View,h:Int=-2){val p=WindowManager.LayoutParams(d(300),h,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL;x=d(8)};try{wm.addView(v,p);root=v}catch(_:Exception){}}
 private fun drawMixer(){
  val box=panel();val head=LinearLayout(s).apply{gravity=Gravity.CENTER_VERTICAL}
  head.addView(TextView(s).apply{text="Volume";textSize=18f;setTextColor(Color.DKGRAY)},LinearLayout.LayoutParams(0,-2,1f))
  head.addView(TextView(s).apply{text="×";textSize=24f;setTextColor(Color.DKGRAY);setOnClickListener{hide()}})
  box.addView(head)
  val row=LinearLayout(s).apply{gravity=Gravity.CENTER;setPadding(0,d(12),0,d(8))}
  row.addView(column(null),LinearLayout.LayoutParams(d(68),d(190)))
  prefs.getStringSet("selected_apps",emptySet()).orEmpty().take(3).forEach{row.addView(column(it),LinearLayout.LayoutParams(d(68),d(190)))}
  box.addView(row)
  box.addView(Button(s).apply{text="+ Tambah aplikasi";isAllCaps=false;setOnClickListener{drawPicker()}},LinearLayout.LayoutParams(-1,d(44)))
  attach(box)
 }
 private fun column(pkg:String?):android.view.View{
  val c=LinearLayout(s).apply{orientation=1;gravity=Gravity.CENTER}
  val maxV=if(pkg==null)audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) else 100
  val init=if(pkg==null)audio.getStreamVolume(AudioManager.STREAM_MUSIC) else prefs.getInt("volume_"+pkg,100).coerceIn(0,100)
  val label=TextView(s).apply{text=(init*100/maxV).toString()+"%";textSize=12f;setTextColor(Color.DKGRAY)}
  c.addView(label)
  val seek=VSeek(s).apply{max=maxV;progress=init;progressTintList=android.content.res.ColorStateList.valueOf(Color.rgb(100,82,190));thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(100,82,190));setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(b:SeekBar?,v:Int,user:Boolean){if(!user)return;label.text=(v*100/maxV).toString()+"%";if(pkg==null)audio.setStreamVolume(AudioManager.STREAM_MUSIC,v,0)else prefs.edit().putInt("volume_"+pkg,v).apply()}
   override fun onStartTrackingTouch(b:SeekBar?){}
   override fun onStopTrackingTouch(b:SeekBar?){if(pkg!=null){val v=progress/100f;worker.execute{AudioSessionDetector.setPackageVolume(s,pkg,v)}}}
  })}
  c.addView(seek,LinearLayout.LayoutParams(d(42),d(126)))
  if(pkg==null)c.addView(TextView(s).apply{text="Sistem";textSize=11f;setTextColor(Color.DKGRAY)}) else{
   val info=try{s.packageManager.getApplicationInfo(pkg,0)}catch(_:Exception){null}
   c.addView(ImageView(s).apply{setImageDrawable(if(info!=null)s.packageManager.getApplicationIcon(info)else s.getDrawable(android.R.drawable.sym_def_app_icon))},LinearLayout.LayoutParams(d(26),d(26)))
   c.addView(TextView(s).apply{text=if(info!=null)s.packageManager.getApplicationLabel(info).toString()else pkg;textSize=10f;setTextColor(Color.DKGRAY);gravity=Gravity.CENTER;maxLines=1})
  };return c
 }
 private fun drawPicker(){
  val apps=RecentApps.query(s);val selected=prefs.getStringSet("selected_apps",emptySet()).orEmpty().toMutableSet()
  val box=panel();box.addView(TextView(s).apply{text="Aplikasi terbaru";textSize=18f;setTextColor(Color.DKGRAY)})
  box.addView(TextView(s).apply{text="Aplikasi yang baru digunakan";textSize=11f;setTextColor(Color.GRAY)})
  val scroll=ScrollView(s);val list=LinearLayout(s).apply{orientation=1}
  if(apps.isEmpty()){
   list.addView(TextView(s).apply{text=if(RecentApps.hasAccess(s))"Tidak ada aplikasi terbaru." else "Izinkan Akses penggunaan untuk menampilkan aplikasi terbaru.";setTextColor(Color.DKGRAY);setPadding(0,d(16),0,d(16))})
   if(!RecentApps.hasAccess(s))list.addView(Button(s).apply{text="Buka Akses penggunaan";setOnClickListener{try{s.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){}}})
  }
  apps.forEach{app->
   val pkg=app.packageName;val r=LinearLayout(s).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,d(7),0,d(7))}
   r.addView(ImageView(s).apply{setImageDrawable(s.packageManager.getApplicationIcon(app))},LinearLayout.LayoutParams(d(34),d(34)))
   r.addView(TextView(s).apply{text=s.packageManager.getApplicationLabel(app).toString();textSize=14f;setTextColor(Color.DKGRAY);setPadding(d(9),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
   val tick=TextView(s).apply{text=if(pkg in selected)"✓"else"+";textSize=20f;setTextColor(Color.rgb(100,82,190))}
   r.addView(tick);r.setOnClickListener{if(!selected.add(pkg))selected.remove(pkg);tick.text=if(pkg in selected)"✓"else"+"};list.addView(r)
  }
  scroll.addView(list);box.addView(scroll,LinearLayout.LayoutParams(-1,d(290)))
  val actions=LinearLayout(s).apply{gravity=Gravity.END}
  actions.addView(Button(s).apply{text="Kembali";setOnClickListener{drawMixer()}})
  actions.addView(Button(s).apply{text="Simpan";setOnClickListener{prefs.edit().putStringSet("selected_apps",selected).apply();drawMixer()}})
  box.addView(actions);hide();attach(box,d(470))
 }
}
private class VSeek(c:Context):SeekBar(c){
 override fun onMeasure(w:Int,h:Int){super.onMeasure(h,w);setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h))}
 override fun onDraw(c:android.graphics.Canvas){c.save();c.rotate(-90f);c.translate(-height.toFloat(),0f);super.onDraw(c);c.restore()}
 override fun onTouchEvent(e:android.view.MotionEvent):Boolean{if(!isEnabled)return false;when(e.action){0,2,1->{progress=(max*(1f-e.y/height)).toInt().coerceIn(0,max);if(e.action==1)performClick()};3->return false};return true}
 override fun performClick():Boolean{super.performClick();return true}
}
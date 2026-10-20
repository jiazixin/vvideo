package dev.vvideo

import android.graphics.Color
import android.graphics.Point
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import dev.vvideo.app.MainActivity
import dev.vvideo.app.VVideoApplication
import dev.vvideo.feature.library.LibraryViewModel
import dev.vvideo.model.Sort
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val app get()=instrumentation.targetContext.applicationContext as VVideoApplication
 private suspend fun connect():dev.vvideo.data.settings.Connection{
  withTimeout(15000){app.ready.first{it}}
  val config=withContext(Dispatchers.IO){JSONObject(File(app.filesDir,"validation.json").readText())}
  app.graph.settings.connect(InstrumentationRegistry.getArguments().getString("serverAddress")?:config.getString("address"),config.getString("token"))
  return app.graph.settings.connection.first()
 }
 private suspend fun launch():MainActivity{
  withContext(Dispatchers.IO){instrumentation.uiAutomation.executeShellCommand("am start -n dev.vvideo/dev.vvideo.app.MainActivity").use{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use{it.readBytes()}}}
  return withTimeout(10000){var found:MainActivity?=null;while(found==null){withContext(Dispatchers.Main){found=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};if(found==null)delay(50)};checkNotNull(found)}
 }
 // An album item whose row split depends on the measured width used to appear
 // with a provisional height, so a position restored at the end of the list
 // was cut short once the viewer closed.
 @Test fun albumEndPositionSurvivesViewer()=runBlocking<Unit> {
  val connection=connect()
  val root=app.graph.api.libraries(connection).first().directoryId
  val media=app.graph.api.page(connection,root,Sort("name","asc")).items
  org.junit.Assume.assumeTrue("needs a root that fits one page and ends with an image",media.size in 11..99&&media.last().kind=="image")
  val last=media.last();val cell=By.desc(last.name+"，image")
  val activity=launch()
  val device=UiDevice.getInstance(instrumentation)
  try {
   delay(400)
   lateinit var library:LibraryViewModel
   withContext(Dispatchers.Main){library=ViewModelProvider(activity)[LibraryViewModel::class.java];library.open(root,Sort("name","asc"))}
   withTimeout(10000){library.state.first{!it.busy&&it.directory?.id==root&&it.albums.isNotEmpty()}}
   val cx=device.displayWidth/2;val h=device.displayHeight
   repeat(12){if(!device.hasObject(cell)){device.swipe(cx,h*3/4,cx,h/4,20);delay(500)}}
   repeat(2){device.swipe(cx,h*3/4,cx,h/4,20);delay(500)}
   delay(1000)
   val before=checkNotNull(device.findObject(cell)).visibleBounds
   checkNotNull(device.findObject(cell)).click()
   checkNotNull(device.wait(Until.findObject(By.desc(last.name)),10000))
   delay(600);device.pressBack()
   assertNotNull("album moved: its last cell left the screen after returning from the viewer",device.wait(Until.findObject(cell),5000));delay(1200)
   val after=checkNotNull(device.findObject(cell)).visibleBounds
   assertEquals("album moved after returning from the viewer",before,after)
   Log.i("VVideoValidation","album_end_position_kept=true fixture=${last.name} bounds=$after")
  }finally{withContext(Dispatchers.Main){activity.finish()}}
 }
 @Test fun originalPhotoPinchAndReturn()=runBlocking<Unit> {
  val connection=connect()
  val root=app.graph.api.libraries(connection).first().directoryId
  val directory=app.graph.api.children(connection,root).items.first{it.name=="large"}.id
  val image=app.graph.api.page(connection,directory,Sort("name","asc"),kind="image").items.first{it.displayRatio in 1.4f..2f}
  val activity=launch()
  val device=UiDevice.getInstance(instrumentation)
  try {
   delay(400)
   lateinit var library:LibraryViewModel
   withContext(Dispatchers.Main){library=ViewModelProvider(activity)[LibraryViewModel::class.java];library.open(directory,Sort("name","asc"))}
   withTimeout(10000){library.state.first{!it.busy&&it.directory?.id==directory&&it.albums.isNotEmpty()}}
   checkNotNull(device.wait(Until.findObject(By.desc(image.name+"，image")),10000)).click()
   val photo=checkNotNull(device.wait(Until.findObject(By.desc(image.name)),10000))
   // Select a landscape fixture explicitly: a portrait image already fills
   // the measured region, so visible-area growth cannot prove its zoom.
   // Synthetic landscape JPEGs have a colored pattern. Count visible image pixels
   // away from system bars/buttons: this verifies the rendered zoom, rather than
   // asserting a private scale value or merely accepting the gesture call.
   fun coloredPixels():Int {
    val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
     var pixels=0
     for(y in bitmap.height/6 until bitmap.height*5/6 step 4)for(x in 0 until bitmap.width step 4){
      val color=bitmap.getPixel(x,y);val channels=listOf(Color.red(color),Color.green(color),Color.blue(color))
      if(channels.max()-channels.min()>50)pixels++
     }
     return pixels
    }finally{bitmap.recycle()}
   }
   var normal=0
   withTimeout(10000){while(normal<1000){normal=coloredPixels();if(normal<1000)delay(100)}}
   // Node bounds scale with graphicsLayer. Use fixed screen-space touches,
   // wholly inside the viewport for both directions, rather than deriving
   // the second gesture from the enlarged accessibility node.
   val cx=device.displayWidth/2;val cy=device.displayHeight/2
   val near=device.displayWidth/10;val far=device.displayWidth*4/10
   val gesture=device.findObject(UiSelector().description(image.name))
   assertTrue(gesture.performTwoPointerGesture(Point(cx-near,cy),Point(cx+near,cy),Point(cx-far,cy),Point(cx+far,cy),100))
   delay(500)
   val enlarged=coloredPixels()
   assertTrue("pinch did not enlarge rendered image: $normal -> $enlarged",enlarged>normal*1.5)
   Log.i("VVideoValidation","photo_zoom_open fixture=${image.name} normal=$normal enlarged=$enlarged bounds=${photo.visibleBounds}")
   assertTrue(gesture.performTwoPointerGesture(Point(cx-far,cy),Point(cx+far,cy),Point(cx-near,cy),Point(cx+near,cy),100))
   delay(500)
   val reduced=coloredPixels()
   Log.i("VVideoValidation","photo_zoom_close enlarged=$enlarged reduced=$reduced")
   assertTrue("pinch did not reduce rendered image: $enlarged -> $reduced",reduced<enlarged*.85)
   // Paging follows the folder's image order, left and right. A horizontal pinch
   // travels along the paging axis and must still zoom; an enlarged photo pans
   // under one finger instead of handing the drag to the pager.
   val queue=app.graph.api.page(connection,directory,Sort("name","asc"),anchor=image.id,kind="image").items
   val following=queue[queue.indexOfFirst{it.id==image.id}+1]
   device.swipe(cx+far,cy,cx-far,cy,12)
   assertNotNull("swipe left did not show the next photo",device.wait(Until.findObject(By.desc(following.name)),5000))
   device.swipe(cx-far,cy,cx+far,cy,12)
   assertNotNull("swipe right did not return to the photo",device.wait(Until.findObject(By.desc(image.name)),5000))
   delay(500)
   device.swipe(cx,cy+far,cx,cy-far,12);delay(500)
   assertTrue("a vertical swipe paged photos",device.hasObject(By.desc(image.name)))
   assertTrue(gesture.performTwoPointerGesture(Point(cx-near,cy),Point(cx+near,cy),Point(cx-far,cy),Point(cx+far,cy),100))
   delay(500)
   assertTrue("horizontal pinch paged instead of zooming",device.hasObject(By.desc(image.name)))
   val wide=coloredPixels()
   assertTrue("horizontal pinch did not enlarge rendered image: $reduced -> $wide",wide>reduced*1.5)
   device.swipe(cx+near,cy,cx-near,cy,12);delay(500)
   assertTrue("an enlarged photo paged under a one-finger drag",device.hasObject(By.desc(image.name)))
   assertTrue(gesture.performTwoPointerGesture(Point(cx-far,cy),Point(cx+far,cy),Point(cx-near,cy),Point(cx+near,cy),100))
   delay(500)
   val restored=coloredPixels()
   assertTrue("horizontal pinch did not reduce rendered image: $wide -> $restored",restored<wide*.85)
   Log.i("VVideoValidation","photo_paging next=${following.name} paging_axis_pinch_pixels=$wide restored_pixels=$restored")
   checkNotNull(device.wait(Until.findObject(By.desc("媒体详情")),3000)).click()
   assertTrue(device.wait(Until.hasObject(By.text(image.name)),3000))
   assertTrue(device.hasObject(By.text("所在文件夹")))
   checkNotNull(device.wait(Until.findObject(By.desc("关闭")),3000)).click()
   device.pressBack();delay(500)
   assertEquals(directory,library.state.value.directory?.id)
   assertTrue(device.hasObject(By.text("large")))
   Log.i("VVideoValidation","photo_original_pinch_return=true fixture=${image.name} dimensions=${image.width}x${image.height} normal_pixels=$normal enlarged_pixels=$enlarged reduced_pixels=$reduced")
  }finally{withContext(Dispatchers.Main){activity.finish()}}
 }
}

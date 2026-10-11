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
 @Test fun originalPhotoPinchAndReturn()=runBlocking<Unit> {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as VVideoApplication
  withTimeout(15000){app.ready.first{it}}
  val config=withContext(Dispatchers.IO){JSONObject(File(app.filesDir,"validation.json").readText())}
  app.graph.settings.connect(InstrumentationRegistry.getArguments().getString("serverAddress")?:config.getString("address"),config.getString("token"))
  val connection=app.graph.settings.connection.first()
  val root=app.graph.api.libraries(connection).first().directoryId
  val directory=app.graph.api.children(connection,root).items.first{it.name=="large"}.id
  val image=app.graph.api.page(connection,directory,Sort("name","asc"),kind="image").items.first{it.displayRatio in 1.4f..2f}
  withContext(Dispatchers.IO){instrumentation.uiAutomation.executeShellCommand("am start -n dev.vvideo/dev.vvideo.app.MainActivity").use{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use{it.readBytes()}}}
  val activity=withTimeout(10000){var found:MainActivity?=null;while(found==null){withContext(Dispatchers.Main){found=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};if(found==null)delay(50)};checkNotNull(found)}
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
   // Paging follows the folder's image order. A vertical pinch travels along
   // the paging axis and must still zoom; an enlarged photo pans under one
   // finger instead of handing the drag to the pager.
   val queue=app.graph.api.page(connection,directory,Sort("name","asc"),anchor=image.id,kind="image").items
   val following=queue[queue.indexOfFirst{it.id==image.id}+1]
   device.swipe(cx,cy+far,cx,cy-far,12)
   assertNotNull("swipe up did not show the next photo",device.wait(Until.findObject(By.desc(following.name)),5000))
   device.swipe(cx,cy-far,cx,cy+far,12)
   assertNotNull("swipe down did not return to the photo",device.wait(Until.findObject(By.desc(image.name)),5000))
   delay(500)
   assertTrue(gesture.performTwoPointerGesture(Point(cx,cy-near),Point(cx,cy+near),Point(cx,cy-far),Point(cx,cy+far),100))
   delay(500)
   assertTrue("vertical pinch paged instead of zooming",device.hasObject(By.desc(image.name)))
   val tall=coloredPixels()
   assertTrue("vertical pinch did not enlarge rendered image: $reduced -> $tall",tall>reduced*1.5)
   device.swipe(cx,cy+near,cx,cy-near,12);delay(500)
   assertTrue("an enlarged photo paged under a one-finger drag",device.hasObject(By.desc(image.name)))
   assertTrue(gesture.performTwoPointerGesture(Point(cx,cy-far),Point(cx,cy+far),Point(cx,cy-near),Point(cx,cy+near),100))
   delay(500)
   val restored=coloredPixels()
   assertTrue("vertical pinch did not reduce rendered image: $tall -> $restored",restored<tall*.85)
   Log.i("VVideoValidation","photo_paging next=${following.name} vertical_pinch_pixels=$tall restored_pixels=$restored")
   checkNotNull(device.wait(Until.findObject(By.desc("媒体详情")),3000)).click()
   checkNotNull(device.wait(Until.findObject(By.text("媒体详情")),3000)).click()
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

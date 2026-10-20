package dev.vvideo

import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.vvideo.app.*
import dev.vvideo.feature.library.LibraryViewModel
import dev.vvideo.data.library.ChildrenWindow
import dev.vvideo.model.Sort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class UiRedesignDeviceTest {
 @Test fun foldersReverseSettingsAndSystemBack()=runBlocking<Unit>{
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as VVideoApplication
  withTimeout(15000){app.ready.first{it}}
  val secret=withContext(Dispatchers.IO){JSONObject(File(app.filesDir,"validation.json").readText())}
  app.graph.settings.connect("http://127.0.0.1:19090",secret.getString("token"))
  val c=app.graph.settings.connection.first();val api=app.graph.api
  val root=api.libraries(c).first().directoryId
  val folders=api.children(c,root).items
  val wide=folders.first{it.name=="wide"}.id
  val window=ChildrenWindow();window.reset(api.children(c,wide))
  while(window.next.isNotEmpty()){window.add(api.children(c,wide,window.next),false);assertTrue(window.items.size<=300)}
  assertEquals("folder-0449",window.items.last().name)
  while(window.previous.isNotEmpty()){window.add(api.children(c,wide,window.previous,true),true);assertTrue(window.items.size<=300)}
  assertEquals("folder-0000",window.items.first().name)
  instrumentation.uiAutomation.executeShellCommand("am start -n dev.vvideo/dev.vvideo.app.MainActivity").close()
  var activity:MainActivity?=null
  withTimeout(10000){while(activity==null){withContext(Dispatchers.Main){activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};delay(50)}}
  val a=checkNotNull(activity);lateinit var vm:LibraryViewModel
  withContext(Dispatchers.Main){vm=ViewModelProvider(a)[LibraryViewModel::class.java];vm.connect(c)}
  // The first composition still has to consume the authenticated connection.
  // Wait for initialization before driving the VM, so it cannot cancel open().
  withTimeout(15000){vm.state.first{it.libraries.isNotEmpty()&&!it.busy}}
  withContext(Dispatchers.Main){vm.open(wide)}
  withTimeout(10000){vm.state.first{!it.busy&&it.directory?.id==wide}}
  val device=UiDevice.getInstance(instrumentation)
  checkNotNull(device.wait(Until.findObject(By.text("folder-0000")),5000)).click()
  assertTrue(device.wait(Until.hasObject(By.text("这里还没有媒体")),5000))
  device.pressBack()
  assertTrue(device.wait(Until.hasObject(By.text("folder-0000")),5000))
  device.pressBack()
  withTimeout(5000){vm.state.first{!it.busy&&it.directory?.id==root}}
  device.pressBack()
  checkNotNull(device.wait(Until.findObject(By.desc("设置")),5000)).click()
  assertTrue(device.wait(Until.hasObject(By.textStartsWith("已连接 ·")),10000))
  device.pressBack()
  assertTrue(device.wait(Until.hasObject(By.desc("设置")),5000));assertFalse(device.hasObject(By.text("最近浏览")))
  withContext(Dispatchers.Main){a.finish()}
 }
}

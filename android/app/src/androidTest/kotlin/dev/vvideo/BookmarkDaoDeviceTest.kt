package dev.vvideo

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.vvideo.data.library.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Room's real schema on the device; no room-testing dependency. */
class BookmarkDaoDeviceTest {
 @Test fun visitsCannotBeOverwrittenOrResurrectedByScroll()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val db=Room.inMemoryDatabaseBuilder(context,BrowseDatabase::class.java).build()
  try {
   val dao=db.bookmarks();val old=BookmarkEntity("test",1,"library",anchor="anchor")
   dao.put(old)
   assertEquals("asc",dao.get("test",1)?.direction);assertEquals("",dao.get("test",1)?.name);assertEquals(0L,dao.get("test",1)?.visitedAt)
   dao.visit(old.copy(name="Folder",pathLabel="Library",visitedAt=1000))
   dao.put(old.copy(anchor="new-anchor",savedAt=2000))
   var recent=dao.recent("test").first();assertEquals(1,recent.size);assertEquals(1000L,recent.single().visitedAt);assertEquals("new-anchor",recent.single().anchor)
   dao.hide("test",1);dao.put(old.copy(savedAt=3000));assertTrue(dao.recent("test").first().isEmpty())
   assertNotNull(dao.get("test",1))
   dao.visit(old.copy(name="Folder",visitedAt=4000));recent=dao.recent("test").first();assertEquals(4000L,recent.single().visitedAt)
   assertTrue(dao.recent("other").first().isEmpty())
  }finally{db.close()}
 }
}

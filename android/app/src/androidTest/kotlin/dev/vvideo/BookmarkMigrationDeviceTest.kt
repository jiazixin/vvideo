package dev.vvideo

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.vvideo.data.library.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Uses platform SQLite plus Room's real schema validation; no room-testing dependency. */
class BookmarkMigrationDeviceTest {
 @Test fun upgradeMovesNewestFirstToDefaultAndVisitsCannotBeOverwrittenByScroll()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="migration-ui-test.db"
  context.deleteDatabase(name)
  val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
  SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
   db.execSQL("CREATE TABLE bookmarks (connection TEXT NOT NULL,directory INTEGER NOT NULL,library TEXT NOT NULL,sort TEXT NOT NULL,direction TEXT NOT NULL,anchor TEXT NOT NULL,neighbor TEXT NOT NULL,fallback TEXT NOT NULL,pixelOffset INTEGER NOT NULL,width INTEGER NOT NULL,revision INTEGER NOT NULL,savedAt INTEGER NOT NULL,formatVersion INTEGER NOT NULL,PRIMARY KEY(connection,directory))")
   db.execSQL("INSERT INTO bookmarks VALUES('test',1,'library','capture','desc','anchor','neighbor','fallback',123,400,2,500,1)")
   db.execSQL("INSERT INTO bookmarks VALUES('test',2,'library','name','desc','anchor','neighbor','fallback',123,400,2,500,1)")
   db.version=1
  }
  val db=Room.databaseBuilder(context,BrowseDatabase::class.java,name).addMigrations(BrowseMigration1To2,BrowseMigration2To3).build()
  try {
   val dao=db.bookmarks();val old=checkNotNull(dao.get("test",1))
   // The newest-first capture sort moved to the oldest-first default and lost its anchor.
   assertEquals("asc",old.direction);assertEquals("",old.anchor);assertEquals(0,old.pixelOffset);assertEquals("",old.name);assertEquals(0L,old.visitedAt)
   assertEquals("desc",dao.get("test",2)?.direction);assertEquals("anchor",dao.get("test",2)?.anchor)
   dao.visit(old.copy(name="Folder",pathLabel="Library",visitedAt=1000))
   dao.put(old.copy(anchor="new-anchor",savedAt=2000))
   var recent=dao.recent("test").first();assertEquals(1,recent.size);assertEquals(1000L,recent.single().visitedAt);assertEquals("new-anchor",recent.single().anchor)
   dao.hide("test",1);dao.put(old.copy(savedAt=3000));assertTrue(dao.recent("test").first().isEmpty())
   assertNotNull(dao.get("test",1))
   dao.visit(old.copy(name="Folder",visitedAt=4000));recent=dao.recent("test").first();assertEquals(4000L,recent.single().visitedAt)
   assertTrue(dao.recent("other").first().isEmpty())
  }finally{db.close();context.deleteDatabase(name)}
 }
}

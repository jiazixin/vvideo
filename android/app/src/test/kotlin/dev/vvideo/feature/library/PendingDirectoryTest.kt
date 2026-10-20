package dev.vvideo.feature.library

import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.model.*
import org.junit.Assert.*
import org.junit.Test

class PendingDirectoryTest {
 private val root=Directory(1,"lib",0,"media",7)
 private val wide=Directory(2,"lib",1,"wide",3)
 private val leaf=Directory(3,"lib",2,"folder-0001",1)
 private val recent=RecentFolder(BookmarkEntity("c",9,"lib",name="deep",pathLabel="media › wide"),"昨天")
 private val home=LibraryUiState(libraries=listOf(Library("lib","media",1,true)),recent=listOf(recent),restoreSerial=4)
 private val inWide=home.copy(directory=wide,path=listOf(root,wide),pathLabel="media",children=listOf(leaf),albums=listOf(Album(emptyList())),hasMedia=true,kind="video",error=true)

 @Test fun childExtendsThePathAndStartsEmpty(){
  val pending=checkNotNull(pendingDirectory(inWide,3))
  assertEquals(leaf,pending.directory);assertEquals(listOf(root,wide,leaf),pending.path);assertEquals("media › wide",pending.pathLabel)
  assertTrue(pending.busy);assertTrue(pending.albums.isEmpty());assertTrue(pending.children.isEmpty())
  assertFalse(pending.hasMedia);assertFalse(pending.error);assertEquals("",pending.kind)
  assertEquals(home.libraries,pending.libraries);assertEquals(home.recent,pending.recent);assertEquals(4,pending.restoreSerial)
 }
 @Test fun ancestorTruncatesThePath(){
  val pending=checkNotNull(pendingDirectory(inWide.copy(directory=leaf,path=listOf(root,wide,leaf),children=emptyList()),2))
  assertEquals(wide,pending.directory);assertEquals(listOf(root,wide),pending.path);assertEquals("media",pending.pathLabel)
 }
 @Test fun childOutsideTheKnownPathStandsAlone(){
  val pending=checkNotNull(pendingDirectory(inWide.copy(path=listOf(leaf)),3))
  assertEquals(listOf(leaf),pending.path);assertEquals("",pending.pathLabel)
 }
 @Test fun libraryRootAndRecentEntryFromHome(){
  val library=checkNotNull(pendingDirectory(home,1))
  assertEquals(Directory(1,"lib",0,"media",0),library.directory);assertEquals(1,library.path.size);assertEquals("",library.pathLabel)
  val deep=checkNotNull(pendingDirectory(home,9))
  assertEquals(Directory(9,"lib",0,"deep",0),deep.directory);assertEquals(listOf(deep.directory),deep.path);assertEquals("media › wide",deep.pathLabel)
 }
 @Test fun unknownDestinationKeepsTheCurrentPage(){assertNull(pendingDirectory(home,42));assertNull(pendingDirectory(inWide,42))}
 @Test fun restoredPageKeepsItsContentAndTakesLiveLists(){
  val left=inWide.copy(pageBusy=true,childBusy=true,restoreSerial=4,anchorMissing=true)
  val newer=RecentFolder(BookmarkEntity("c",3,"lib",name="folder-0001",pathLabel="media › wide"),"今天")
  val failed=checkNotNull(pendingDirectory(left,3)).copy(busy=false,error=true,recent=listOf(newer),restoreSerial=6)
  val back=restoredPage(left,failed)
  assertEquals(wide,back.directory);assertEquals(left.albums,back.albums);assertEquals(left.children,back.children);assertEquals(left.path,back.path);assertEquals("video",back.kind)
  assertEquals(listOf(newer),back.recent);assertEquals(6,back.restoreSerial);assertFalse(back.pageBusy);assertFalse(back.childBusy);assertFalse(back.busy)
  // The notice belonged to the load that missed; going back to the page does not repeat it.
  assertFalse(back.anchorMissing)
 }
}

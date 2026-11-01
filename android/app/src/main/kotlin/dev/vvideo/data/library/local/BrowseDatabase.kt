package dev.vvideo.data.library.local

import androidx.room.Database
import androidx.room.RoomDatabase

// No migrations before the first release: a database of any other version is
// dropped and recreated (see AppGraph); these are only local browse positions.
@Database(entities = [BookmarkEntity::class], version = 3, exportSchema = true)
internal abstract class BrowseDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
}

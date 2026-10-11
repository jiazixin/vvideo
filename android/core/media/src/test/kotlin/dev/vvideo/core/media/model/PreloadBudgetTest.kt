package dev.vvideo.core.media.model

import org.junit.Assert.*
import org.junit.Test

class PreloadBudgetTest {
 @Test fun rangesStayWithinOriginalOffsetsAndBudget(){assertTrue(PreloadBudget.allowed(ByteRange(4096,128),5000));assertFalse(PreloadBudget.allowed(ByteRange(-1,128),5000));assertFalse(PreloadBudget.allowed(ByteRange(4900,128),5000));assertFalse(PreloadBudget.allowed(ByteRange(Long.MAX_VALUE,128),5000));assertFalse(PreloadBudget.allowed(ByteRange(0,PreloadBudget.MAX_BYTES+1),Long.MAX_VALUE));assertFalse(PreloadBudget.allowed(ByteRange(0,-1),5000))}
 @Test fun openingIsBoundedForUnknownAndVeryLargeMedia(){for(size in listOf(0L,200L,100_000_000L,Long.MAX_VALUE)){for(duration in listOf(0L,8000L,Long.MAX_VALUE)){val bytes=PreloadBudget.openingBytes(size,duration);assertTrue(bytes>=0);assertTrue(bytes<=size);assertTrue(bytes<=4L*1024*1024)}}}
 @Test fun versionsAndServersHaveSeparateCacheKeys(){val a=PlaybackItem("server","library","media","version","url","index","token",1,1);assertNotEquals(a.cacheKey,a.copy(version="other").cacheKey);assertNotEquals(a.cacheKey,a.copy(server="other").cacheKey);assertNotEquals(a.cacheKey,a.copy(library="other").cacheKey);assertEquals(a.cacheKey,a.copy(token="renewed").cacheKey)}
}

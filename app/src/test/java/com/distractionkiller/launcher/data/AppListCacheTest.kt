package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppListCacheTest {

    private val first = listOf(LaunchableApp("com.example.a", "Alpha"))
    private val second = listOf(LaunchableApp("com.example.a", "Alpha"), LaunchableApp("com.example.b", "Beta"))

    @Test
    fun `a new cache is cold`() {
        assertNull(AppListCache().cached())
    }

    @Test
    fun `load returns the computed list and warms the cache with it`() {
        val cache = AppListCache()
        assertSame(first, cache.load { first })
        assertSame(first, cache.cached())
    }

    @Test
    fun `a warm cache is served without computing again`() {
        val cache = AppListCache()
        val computes = AtomicInteger()
        cache.load { computes.incrementAndGet(); first }
        val again = cache.load { computes.incrementAndGet(); second }
        assertEquals(1, computes.get())
        assertSame(first, again)
    }

    @Test
    fun `invalidate makes the cache cold and the next load recomputes`() {
        val cache = AppListCache()
        cache.load { first }
        cache.invalidate()
        assertNull(cache.cached())
        assertSame(second, cache.load { second })
        assertSame(second, cache.cached())
    }

    @Test
    fun `invalidating a cold cache is harmless`() {
        val cache = AppListCache()
        cache.invalidate()
        cache.invalidate()
        assertNull(cache.cached())
        assertSame(first, cache.load { first })
    }

    @Test
    fun `an invalidation during a load keeps its result out of the cache`() {
        val cache = AppListCache()
        // The package event arrives while the query is running, so the list it
        // returns may predate the change.
        val result = cache.load {
            cache.invalidate()
            first
        }
        assertSame("the caller still gets what the query returned", first, result)
        assertNull("but it must not be served as current", cache.cached())

        assertSame(second, cache.load { second })
        assertSame(second, cache.cached())
    }

    @Test
    fun `a failed load reaches the caller and leaves the cache cold but usable`() {
        val cache = AppListCache()
        cache.load { first }
        // Stale, so the next load really computes, and fails.
        cache.invalidate()
        try {
            cache.load { throw IllegalStateException("binder died") }
            fail("expected the failure to reach the caller")
        } catch (expected: IllegalStateException) {
            assertEquals("binder died", expected.message)
        }
        assertNull(cache.cached())

        // Not wedged by the failure: the load lock was released.
        assertSame(second, cache.load { second })
        assertSame(second, cache.cached())
    }

    @Test
    fun `callers that miss together share one query`() {
        val cache = AppListCache()
        val computes = AtomicInteger()
        val insideCompute = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = arrayOfNulls<List<LaunchableApp>>(2)

        val leader = Thread {
            results[0] = cache.load {
                computes.incrementAndGet()
                insideCompute.countDown()
                release.await(10, TimeUnit.SECONDS)
                first
            }
        }
        val follower = Thread {
            results[1] = cache.load {
                computes.incrementAndGet()
                second
            }
        }

        leader.start()
        assertTrue(insideCompute.await(10, TimeUnit.SECONDS))
        follower.start()
        // The follower can only be BLOCKED on the load lock, because the leader
        // is parked inside compute. Waiting for that makes the overlap real.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (follower.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, follower.state)
        release.countDown()
        leader.join(10_000)
        follower.join(10_000)

        assertEquals(1, computes.get())
        assertSame(first, results[0])
        assertSame(first, results[1])
    }

    @Test
    fun `the cache can be read while a load is running`() {
        val cache = AppListCache()
        val insideCompute = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader = Thread {
            cache.load {
                insideCompute.countDown()
                release.await(10, TimeUnit.SECONDS)
                first
            }
        }
        loader.start()
        assertTrue(insideCompute.await(10, TimeUnit.SECONDS))

        // The main thread only ever calls cached() and invalidate(); neither may
        // wait for the slow query.
        assertNull(cache.cached())
        cache.invalidate()

        release.countDown()
        loader.join(10_000)
        // The invalidation landed mid-load, so the result was not kept.
        assertNull(cache.cached())
    }

    @Test
    fun `locale and package changes are both on the invalidation list`() {
        // Guards against a quiet deletion: the app would still compile and run,
        // just with app names stuck in the previous language.
        assertEquals("android.intent.action.LOCALE_CHANGED", InvalidatingBroadcasts.LOCALE_ACTION)
        assertEquals(
            setOf(
                "android.intent.action.PACKAGE_ADDED",
                "android.intent.action.PACKAGE_REMOVED",
                "android.intent.action.PACKAGE_CHANGED",
                "android.intent.action.PACKAGE_REPLACED",
            ),
            InvalidatingBroadcasts.PACKAGE_ACTIONS.toSet(),
        )
    }
}

package rs.pametnakupovina.app.data

import javax.inject.Singleton
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingRepositoryScopeTest {
    // Bez ovoga ekran i ShoppingSyncWorker dobiju svaki svoj repozitorijum i
    // svoj syncMutex, pa isti red pošalju dvaput (provera 03.11.2026).
    @Test fun oneRepositoryForTheWholeApp() {
        assertTrue(ShoppingRepository::class.java.isAnnotationPresent(Singleton::class.java))
    }
}

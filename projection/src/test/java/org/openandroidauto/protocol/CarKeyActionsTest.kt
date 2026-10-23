package org.openandroidauto.protocol
import org.junit.Assert.*
import org.junit.Test
class CarKeyActionsTest {
    @Test fun `voice keys request the selected assistant`() { for (code in listOf(84, 219, 231)) assertEquals(CarKeyActions.Action.ASSISTANT, CarKeyActions.forKey(code)) }
    @Test fun `media keys target the existing local player`() { for (code in listOf(79, 85, 86, 87, 88, 126, 127)) assertEquals(CarKeyActions.Action.MEDIA, CarKeyActions.forKey(code)) }
    @Test fun `call key targets the explicit picker and end call only cancels pending app work`() {
        assertEquals(CarKeyActions.Action.CALLS, CarKeyActions.forKey(5))
        assertEquals(CarKeyActions.Action.CANCEL_PENDING, CarKeyActions.forKey(6))
    }
    @Test fun `navigation and unknown keys remain view events`() { for (code in listOf(3, 4, 19, 23, 0, 65540)) assertEquals(CarKeyActions.Action.NONE, CarKeyActions.forKey(code)) }
}

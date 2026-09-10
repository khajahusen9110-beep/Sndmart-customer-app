package com.example

import com.example.data.model.DeliverySlot
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun deliverySlot_effectiveFee_belowMinOrder_appliesBaseFee() {
        val eveningSlot = DeliverySlot(
            id = "slot-evening",
            name = "Evening (4 PM - 9 PM)",
            minOrderAmount = 199.0,
            isFreeDelivery = true,
            deliveryFee = 30.0
        )

        val subtotal = 25.0
        assertFalse(eveningSlot.isFreeDeliveryEligible(subtotal))
        assertEquals(30.0, eveningSlot.getEffectiveDeliveryFee(subtotal), 0.001)
        assertEquals(174.0, eveningSlot.amountNeededForFreeDelivery(subtotal), 0.001)
    }

    @Test
    fun deliverySlot_effectiveFee_atOrAboveMinOrder_qualifiesForFreeDelivery() {
        val eveningSlot = DeliverySlot(
            id = "slot-evening",
            name = "Evening (4 PM - 9 PM)",
            minOrderAmount = 199.0,
            isFreeDelivery = true,
            deliveryFee = 30.0
        )

        val exactSubtotal = 199.0
        assertTrue(eveningSlot.isFreeDeliveryEligible(exactSubtotal))
        assertEquals(0.0, eveningSlot.getEffectiveDeliveryFee(exactSubtotal), 0.001)
        assertEquals(0.0, eveningSlot.amountNeededForFreeDelivery(exactSubtotal), 0.001)

        val higherSubtotal = 250.0
        assertTrue(eveningSlot.isFreeDeliveryEligible(higherSubtotal))
        assertEquals(0.0, eveningSlot.getEffectiveDeliveryFee(higherSubtotal), 0.001)
    }

    @Test
    fun deliverySlot_noFreeDelivery_alwaysAppliesBaseFee() {
        val standardSlot = DeliverySlot(
            id = "slot-morning",
            name = "Morning (9 AM - 12 PM)",
            minOrderAmount = 0.0,
            isFreeDelivery = false,
            deliveryFee = 30.0
        )

        assertEquals(30.0, standardSlot.getEffectiveDeliveryFee(500.0), 0.001)
        assertFalse(standardSlot.isFreeDeliveryEligible(500.0))
    }
}


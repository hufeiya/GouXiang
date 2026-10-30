package com.neethu.gouxiang.work

import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageRetryTaskTest {
    @Test fun `retry has a stable work name distinct from new text generation`() {
        val text = DerivativeTask(DerivativeKind.MOMENT, 1)
        val image = text.copy(imageRetryRequestId = "existing-request")
        assertEquals("deriv:image:existing-request", DerivativeTask.uniqueName(image))
        assertNotEquals(DerivativeTask.uniqueName(text), DerivativeTask.uniqueName(image))
    }
}

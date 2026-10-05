package org.glasshouse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssColourTest {

    @Test fun darkTheme() = assertEquals(0xFF000000.toInt(), CssColour.parse("rgb(0, 0, 0)"))

    @Test fun lightTheme() = assertEquals(0xFFF5F5F5.toInt(), CssColour.parse("rgb(245, 245, 245)"))

    @Test fun opaqueRgba() = assertEquals(0xFF102030.toInt(), CssColour.parse("rgba(16, 32, 48, 1)"))

    @Test fun transparentIsIgnored() = assertNull(CssColour.parse("rgba(0, 0, 0, 0)"))

    @Test fun otherSyntaxIsIgnored() {
        assertNull(CssColour.parse("transparent"))
        assertNull(CssColour.parse("color(srgb 0 0 0)"))
        assertNull(CssColour.parse("rgb(300, 0, 0)"))
        assertNull(CssColour.parse(""))
    }
}

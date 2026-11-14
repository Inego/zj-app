package inego.zungjunggame.render

import inego.zungjunggame.render.fonts.ScriptProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptProfileTest {

    @Test
    fun tagMappingForFutureLocales() {
        assertEquals(ScriptProfile.LatinCyrillic, ScriptProfile.forTag("en-US"))
        assertEquals(ScriptProfile.LatinCyrillic, ScriptProfile.forTag("fr"))
        assertEquals(ScriptProfile.LatinCyrillic, ScriptProfile.forTag("ru-RU"))
        assertEquals(ScriptProfile.Hans, ScriptProfile.forTag("zh-CN"))
        assertEquals(ScriptProfile.Hans, ScriptProfile.forTag("zh"))
        assertEquals(ScriptProfile.Hant, ScriptProfile.forTag("zh-HK"))
        assertEquals(ScriptProfile.Hant, ScriptProfile.forTag("zh-TW"))
        assertEquals(ScriptProfile.Jpan, ScriptProfile.forTag("ja"))
        assertEquals(ScriptProfile.Jpan, ScriptProfile.forTag("ja-JP"))
    }

}

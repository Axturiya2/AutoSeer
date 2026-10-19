package com.autoseer.scripts

import com.autoseer.scripts.PetRoster.SwitchCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PetRoster] (BUG-8 換上已在場精靈、BUG-9 沒判斷陣亡) and the
 * 「已戰敗」-label → card mapping [SeerLayout.petCardAt].
 */
class PetRosterTest {

    @Test
    fun battleStartsWithPet1OnField() {
        val r = PetRoster()
        assertEquals(1, r.active)
        assertEquals(SwitchCheck.ON_FIELD, r.checkSwitch(1))
        assertEquals(SwitchCheck.GO, r.checkSwitch(2))
    }

    @Test
    fun switchToPetAlreadyOnFieldIsSkipped() {
        // BUG-8 (a)：決策 b 補位剛好補上精靈3，腳本下一步又是 Switch 3
        val r = PetRoster()
        r.activeDefeated()
        r.deployed(3)
        assertEquals(SwitchCheck.ON_FIELD, r.checkSwitch(3))
    }

    @Test
    fun fallenPetIsNotSwitchedIn() {
        val r = PetRoster()
        r.activeDefeated()          // 精靈1 陣亡
        r.deployed(2)
        assertEquals(SwitchCheck.DEFEATED, r.checkSwitch(1))
        assertTrue(r.isDefeated(1))
    }

    @Test
    fun cardLabelsMarkDefeated_outOfRangeIgnored() {
        val r = PetRoster()
        r.markDefeated(listOf(4, 6, 0, 7))
        assertEquals(setOf(4, 6), r.defeatedPets)
    }

    @Test
    fun fallbackTriesOnlySurvivors() {
        val r = PetRoster()
        r.markDefeated(listOf(2, 3))
        r.activeDefeated()          // 精靈1 也倒了
        assertNull(r.active)
        assertEquals(listOf(4, 5, 6), r.fallbackOrder())
    }

    @Test
    fun fallbackSkipsPetOnField() {
        assertEquals(listOf(2, 3, 4, 5, 6), PetRoster().fallbackOrder())
    }

    @Test
    fun allMarkedDefeated_fallbackTriesEveryCard() {
        // 遊戲仍跳換精靈介面＝還有活的 → 辨識有誤，退回逐張嘗試，不直接放棄
        val r = PetRoster()
        r.markDefeated((1..6).toList())
        assertEquals((1..6).toList(), r.fallbackOrder())
    }

    @Test
    fun deployedPetIsNoLongerDefeated() {
        val r = PetRoster()
        r.markDefeated(listOf(5))   // 誤判
        r.deployed(5)               // 實際成功上場＝活的
        assertFalse(r.isDefeated(5))
    }

    @Test
    fun resetRevivesEveryone() {
        val r = PetRoster()
        r.markDefeated(listOf(2, 3))
        r.activeDefeated()
        r.reset()
        assertEquals(1, r.active)
        assertTrue(r.defeatedPets.isEmpty())
    }

    @Test
    fun labelXMapsToItsCard() {
        // 頭像中心
        assertEquals(1, SeerLayout.petCardAt(50))
        assertEquals(6, SeerLayout.petCardAt(956))
        // 字樣在卡片中段（頭像右側約 75px）也歸該卡，不被最近的下一張頭像搶走
        assertEquals(1, SeerLayout.petCardAt(150))
        assertEquals(3, SeerLayout.petCardAt(500))
        assertEquals(5, SeerLayout.petCardAt(900))
        // 略在頭像左緣外
        assertEquals(2, SeerLayout.petCardAt(175))
        assertNull(SeerLayout.petCardAt(0))
    }
}

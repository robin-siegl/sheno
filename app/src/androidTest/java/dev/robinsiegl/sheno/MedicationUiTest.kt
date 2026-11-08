package dev.robinsiegl.sheno

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MedicationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun emptyDatabase() = runBlocking(Dispatchers.IO) { compose.activity.sheno.database.clearAllTables() }
    @Test fun medicationCanBeAddedWithNativeEditorAndAppearsInList() {
        compose.onNodeWithTag("addMedication").performClick()
        compose.onNodeWithTag("medicationName").performTextInput("Example medication")
        compose.onNodeWithTag("saveMedication").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Example medication").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Example medication").assertIsDisplayed()
        runBlocking { assertEquals(1, compose.activity.sheno.repository.medications().size) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Medications", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Example medication").assertIsDisplayed()
    }
}

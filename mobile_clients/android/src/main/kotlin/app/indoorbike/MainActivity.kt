package app.indoorbike

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.ui.screens.WorkoutDetailScreen
import app.indoorbike.ui.screens.WorkoutListScreen
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceTheme
import app.indoorbike.ui.theme.CadenceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CadenceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CadenceColors.Bg0
                ) {
                    var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }

                    LaunchedEffect(Unit) {
                        val jsonStrings = loadWorkoutJsonsFromAssets(this@MainActivity)
                        workouts = WorkoutRepository.parseWorkouts(jsonStrings)
                    }

                    AppNavigator(workouts = workouts)
                }
            }
        }
    }

    private fun loadWorkoutJsonsFromAssets(context: Context): List<String> {
        val jsonStrings = mutableListOf<String>()
        try {
            val assetManager = context.assets
            val allAssets = assetManager.list("") ?: emptyArray()
            val workoutFiles = allAssets.filter { it.startsWith("workouts") && it.endsWith(".json") }
            for (fileName in workoutFiles) {
                val jsonString = assetManager.open(fileName).bufferedReader().use { it.readText() }
                jsonStrings.add(jsonString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return jsonStrings
    }
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val workout: Workout) : Screen
}

@Composable
private fun AppNavigator(workouts: List<Workout>) {
    var screen: Screen by remember { mutableStateOf(Screen.List) }
    var activeTab by remember { mutableStateOf("rides") }
    val eyebrow = remember { currentDayEyebrow() }

    if (workouts.isEmpty()) {
        EmptyState()
        return
    }

    when (val s = screen) {
        Screen.List -> WorkoutListScreen(
            workouts = workouts,
            eyebrow = eyebrow,
            onPick = { screen = Screen.Detail(it) },
            activeTab = activeTab,
            onTabSelected = { activeTab = it }
        )
        is Screen.Detail -> WorkoutDetailScreen(
            workout = s.workout,
            onBack = { screen = Screen.List },
            onStart = { /* TODO: navigate to RideScreen once BLE flow exists */ }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No workouts saved yet.\nDrop a workouts*.json into assets/ to start.",
            color = CadenceColors.Fg2,
            style = CadenceType.body
        )
    }
}

private fun currentDayEyebrow(): String {
    val fmt = SimpleDateFormat("EEEE · h:mm a", Locale.getDefault())
    return fmt.format(Date())
}

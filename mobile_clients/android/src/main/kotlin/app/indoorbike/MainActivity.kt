package app.indoorbike

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CyclingAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }
                    
                    LaunchedEffect(Unit) {
                        val jsonStrings = loadWorkoutJsonsFromAssets(this@MainActivity)
                        workouts = WorkoutRepository.parseWorkouts(jsonStrings)
                    }

                    MainScreen(workouts)
                }
            }
        }
    }

    private fun loadWorkoutJsonsFromAssets(context: Context): List<String> {
        val jsonStrings = mutableListOf<String>()
        try {
            val assetManager = context.assets
            val allAssets = assetManager.list("") ?: emptyArray()
            
            // Match any file starting with "workouts" and ending with ".json"
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

@Composable
fun CyclingAppTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun MainScreen(workouts: List<Workout>) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Indoor Cycling App\nLoaded ${workouts.size} workouts",
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

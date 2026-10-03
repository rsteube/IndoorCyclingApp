.pragma library

// The gradient bands' colours, exactly as the Android app's route HUD draws them
// (ui/screens/simulation/ElevationProfile.kt and the Slate palette): descents cool, flat grey,
// then green and yellow, the hard grades bright, and a wall red. The bands themselves are
// shared/route/RouteFormat's, and the core names the band each stretch is in.
var colors = {
    "SteepDescent": "#6FD1E8",
    "Descent": "#4FA3D1",
    "Flat": "#9AA1B0",
    "Gentle": "#1FB373",
    "Moderate": "#F0C419",
    "Hard": "#F2F4F7",
    "VeryHard": "#C5CAD6",
    "Wall": "#EB3B3B"
}

import SwiftUI

/// The seven power zones, as the Android app draws them: the same boundaries
/// (percent of FTP) and the same colours, so a workout looks the same on
/// either phone. See ui/zones/PowerZones.kt.
enum Zone: Int, CaseIterable {
    case z1 = 1, z2, z3, z4, z5, z6, z7

    static func forPercent(_ percent: Int) -> Zone {
        switch percent {
        case ...55: .z1
        case ...75: .z2
        case ...90: .z3
        case ...105: .z4
        case ...120: .z5
        case ...150: .z6
        default: .z7
        }
    }

    var label: String {
        switch self {
        case .z1: "Recovery"
        case .z2: "Endurance"
        case .z3: "Tempo"
        case .z4: "Threshold"
        case .z5: "VO2"
        case .z6: "Anaerobic"
        case .z7: "Neuro"
        }
    }

    var color: Color {
        switch self {
        case .z1: Color(hex: 0x87A0B9)
        case .z2: Color(hex: 0x639CFF)
        case .z3: Color(hex: 0x1FB373)
        case .z4: Color(hex: 0xF0C419)
        case .z5: Color(hex: 0xFF8A1F)
        case .z6: Color(hex: 0xF17A7A)
        case .z7: Color(hex: 0xD674FF)
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// "12:34", or "1:02:03" from an hour up.
func clock(_ seconds: Int) -> String {
    let s = max(0, seconds)
    let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}

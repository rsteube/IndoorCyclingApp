import MapKit
import SwiftUI
import shared

/// The road on a map: all of it, what has been ridden, and the rider as an
/// arrow pointing the way the road goes. Following the rider by default, or
/// framing the whole route.
struct RouteMap: View {
    let simulation: SimulationModel
    @State private var following = true
    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        let coordinates = simulation.coordinates
        let ridden = Array(coordinates.prefix(simulation.riddenCount(at: simulation.distanceMeters)))
        Map(position: $camera, interactionModes: [.zoom, .pan]) {
            MapPolyline(coordinates: coordinates)
                .stroke(.white.opacity(0.85), lineWidth: 5)
            if ridden.count > 1 {
                MapPolyline(coordinates: ridden)
                    .stroke(.orange, lineWidth: 7)
            }
            if let position = simulation.position {
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: position.latitude, longitude: position.longitude)) {
                    Image(systemName: "location.north.fill")
                        .font(.title3)
                        .foregroundStyle(.white)
                        .padding(6)
                        .background(Circle().fill(.orange))
                        .rotationEffect(.degrees(position.bearingDegrees))
                }
            }
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
        .overlay(alignment: .topTrailing) {
            Button(following ? "Following" : "Whole route") {
                following.toggle()
                frame()
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.bordered)
            .padding(8)
        }
        .onAppear { frame() }
        .onChange(of: simulation.distanceMeters) { _, _ in if following { frame() } }
    }

    private func frame() {
        if following, let position = simulation.position {
            camera = .camera(MapCamera(
                centerCoordinate: CLLocationCoordinate2D(latitude: position.latitude, longitude: position.longitude),
                distance: 1600,
                heading: 0,
                pitch: 0
            ))
        } else if let bounds = simulation.route?.bounds {
            let center = CLLocationCoordinate2D(latitude: bounds.centerLatitude, longitude: bounds.centerLongitude)
            let span = MKCoordinateSpan(
                latitudeDelta: max(bounds.maxLatitude - bounds.minLatitude, 0.005) * 1.3,
                longitudeDelta: max(bounds.maxLongitude - bounds.minLongitude, 0.005) * 1.3
            )
            camera = .region(MKCoordinateRegion(center: center, span: span))
        }
    }
}

/// A route, still, on a map: for the route's own page before a ride.
struct RoutePreviewMap: View {
    let route: RouteTrack

    var body: some View {
        let coordinates = route.latLons(count: 800).map { CLLocationCoordinate2D(latitude: $0.0, longitude: $0.1) }
        let bounds = route.bounds
        Map(initialPosition: .region(MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: bounds.centerLatitude, longitude: bounds.centerLongitude),
            span: MKCoordinateSpan(
                latitudeDelta: max(bounds.maxLatitude - bounds.minLatitude, 0.005) * 1.3,
                longitudeDelta: max(bounds.maxLongitude - bounds.minLongitude, 0.005) * 1.3
            )
        ))) {
            MapPolyline(coordinates: coordinates).stroke(.orange, lineWidth: 5)
            if let start = coordinates.first {
                Marker("Start", systemImage: "flag.fill", coordinate: start).tint(.green)
            }
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
    }
}

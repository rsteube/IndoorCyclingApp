// Renders the app icon and the launch-screen mark from the Slate logo
// (slate-design-system/logo/app-icon-*.svg). From mobile_clients/ios:
//   swift scripts/render_icon.swift IndoorBike/Assets.xcassets/AppIcon.appiconset/AppIcon.png
//   swift scripts/render_icon.swift IndoorBike/Assets.xcassets/LaunchMark.imageset/LaunchMark@3x.png mark

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// The Slate app icon (slate-design-system/logo/app-icon-*.svg), drawn at 1024 px.
// A second argument of "mark" draws only the rider and ridge on a clear
// canvas, for the launch screen.
let mark = CommandLine.arguments.count > 2 && CommandLine.arguments[2] == "mark"
let size = mark ? 360 : 1024
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: (mark ? CGImageAlphaInfo.premultipliedLast : CGImageAlphaInfo.noneSkipLast).rawValue)!
func color(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
// SVG coordinates, y down: the icon is 108 x 108 with the 64-unit drawing
// inset by 28; the mark is the 64-unit drawing alone.
ctx.translateBy(x: 0, y: CGFloat(size))
if mark {
    ctx.scaleBy(x: CGFloat(size) / 64, y: -CGFloat(size) / 64)
} else {
    ctx.scaleBy(x: CGFloat(size) / 108, y: -CGFloat(size) / 108)
    ctx.setFillColor(color(0x3A435E))
    ctx.fill(CGRect(x: 0, y: 0, width: 108, height: 108))
    ctx.translateBy(x: 28, y: 28)
    ctx.scaleBy(x: 0.8125, y: 0.8125)
}
ctx.setLineWidth(3)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)

func polyline(_ points: [(CGFloat, CGFloat)]) {
    ctx.move(to: CGPoint(x: points[0].0, y: points[0].1))
    for p in points.dropFirst() { ctx.addLine(to: CGPoint(x: p.0, y: p.1)) }
}

// The ridge behind the rider.
ctx.setStrokeColor(color(0x9AA1B0))
polyline([(4, 26), (14, 16), (22, 22), (32, 10), (44, 20), (54, 14), (60, 20)])
ctx.strokePath()

// The rider and the bike.
ctx.setStrokeColor(color(0xF2F4F7))
ctx.setFillColor(color(0xF2F4F7))
ctx.strokeEllipse(in: CGRect(x: 40, y: 40, width: 16, height: 16))
polyline([(32, 48), (28, 32), (44, 30), (44, 38), (48, 48)])
polyline([(32, 48), (44, 38)])
polyline([(32, 48), (16, 48), (28, 32)])
polyline([(24, 32), (32, 32)])
ctx.move(to: CGPoint(x: 40, y: 28))
ctx.addQuadCurve(to: CGPoint(x: 48, y: 28), control: CGPoint(x: 44, y: 26))
ctx.addQuadCurve(to: CGPoint(x: 49, y: 33), control: CGPoint(x: 50, y: 30))
ctx.strokePath()
ctx.fillEllipse(in: CGRect(x: 9, y: 41, width: 14, height: 14))
polyline([(16, 55), (16, 58)])
polyline([(6, 58), (26, 58)])
ctx.strokePath()

let image = ctx.makeImage()!
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)

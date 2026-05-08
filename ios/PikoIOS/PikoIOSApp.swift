import ComposeApp
import SwiftUI
import UIKit

@main
struct PikoIOSApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                ComposeView(tabName: "Receive")
                    .tabItem {
                        Label {
                            Text("接收")
                        } icon: {
                            Image(uiImage: LucideTabIcon.download.image)
                        }
                    }

                ComposeView(tabName: "Send")
                    .tabItem {
                        Label {
                            Text("发送")
                        } icon: {
                            Image(uiImage: LucideTabIcon.send.image)
                        }
                    }

                ComposeView(tabName: "Settings")
                    .tabItem {
                        Label {
                            Text("设置")
                        } icon: {
                            Image(uiImage: LucideTabIcon.settings.image)
                        }
                    }
            }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let tabName: String

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(tabName: tabName)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private enum LucideTabIcon {
    case download
    case send
    case settings

    var image: UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24))
        let image = renderer.image { _ in
            UIColor.label.setStroke()

            let path = UIBezierPath()
            path.lineWidth = 2
            path.lineCapStyle = .round
            path.lineJoinStyle = .round

            switch self {
            case .download:
                drawDownload(in: path)
            case .send:
                drawSend(in: path)
            case .settings:
                drawSettings(in: path)
            }

            path.stroke()
        }

        return image.withRenderingMode(.alwaysTemplate)
    }

    private func drawDownload(in path: UIBezierPath) {
        path.move(to: CGPoint(x: 21, y: 15))
        path.addLine(to: CGPoint(x: 21, y: 19))
        path.addQuadCurve(to: CGPoint(x: 19, y: 21), controlPoint: CGPoint(x: 21, y: 21))
        path.addLine(to: CGPoint(x: 5, y: 21))
        path.addQuadCurve(to: CGPoint(x: 3, y: 19), controlPoint: CGPoint(x: 3, y: 21))
        path.addLine(to: CGPoint(x: 3, y: 15))

        path.move(to: CGPoint(x: 7, y: 10))
        path.addLine(to: CGPoint(x: 12, y: 15))
        path.addLine(to: CGPoint(x: 17, y: 10))

        path.move(to: CGPoint(x: 12, y: 15))
        path.addLine(to: CGPoint(x: 12, y: 3))
    }

    private func drawSend(in path: UIBezierPath) {
        path.move(to: CGPoint(x: 22, y: 2))
        path.addLine(to: CGPoint(x: 15, y: 22))
        path.addLine(to: CGPoint(x: 11, y: 13))
        path.addLine(to: CGPoint(x: 2, y: 9))
        path.close()

        path.move(to: CGPoint(x: 22, y: 2))
        path.addLine(to: CGPoint(x: 11, y: 13))
    }

    private func drawSettings(in path: UIBezierPath) {
        let teeth = [
            CGPoint(x: 12, y: 2),
            CGPoint(x: 14, y: 2),
            CGPoint(x: 14.8, y: 5),
            CGPoint(x: 17.4, y: 4.2),
            CGPoint(x: 19.8, y: 6.6),
            CGPoint(x: 18.6, y: 9.4),
            CGPoint(x: 21, y: 11),
            CGPoint(x: 21, y: 13),
            CGPoint(x: 18.6, y: 14.6),
            CGPoint(x: 19.8, y: 17.4),
            CGPoint(x: 17.4, y: 19.8),
            CGPoint(x: 14.8, y: 19),
            CGPoint(x: 14, y: 22),
            CGPoint(x: 10, y: 22),
            CGPoint(x: 9.2, y: 19),
            CGPoint(x: 6.6, y: 19.8),
            CGPoint(x: 4.2, y: 17.4),
            CGPoint(x: 5.4, y: 14.6),
            CGPoint(x: 3, y: 13),
            CGPoint(x: 3, y: 11),
            CGPoint(x: 5.4, y: 9.4),
            CGPoint(x: 4.2, y: 6.6),
            CGPoint(x: 6.6, y: 4.2),
            CGPoint(x: 9.2, y: 5),
            CGPoint(x: 10, y: 2)
        ]

        path.move(to: teeth[0])
        teeth.dropFirst().forEach { point in
            path.addLine(to: point)
        }
        path.close()

        path.move(to: CGPoint(x: 15, y: 12))
        path.addArc(
            withCenter: CGPoint(x: 12, y: 12),
            radius: 3,
            startAngle: 0,
            endAngle: CGFloat.pi * 2,
            clockwise: true
        )
    }
}

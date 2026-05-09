import SwiftUI
import UIKit

enum PikoPalette {
    static let surfaceUIColor = UIColor(red: 246 / 255, green: 247 / 255, blue: 241 / 255, alpha: 1)
    static let accentUIColor = UIColor(red: 31 / 255, green: 97 / 255, blue: 141 / 255, alpha: 1)
    static let warmUIColor = UIColor(red: 210 / 255, green: 111 / 255, blue: 58 / 255, alpha: 1)
    static let leafUIColor = UIColor(red: 73 / 255, green: 130 / 255, blue: 96 / 255, alpha: 1)

    static var surface: Color {
        Color(uiColor: surfaceUIColor)
    }

    static var accent: Color {
        Color(uiColor: accentUIColor)
    }

    static var warm: Color {
        Color(uiColor: warmUIColor)
    }

    static var leaf: Color {
        Color(uiColor: leafUIColor)
    }

    static var pageGradient: LinearGradient {
        LinearGradient(
            colors: [
                accent.opacity(0.18),
                surface,
                warm.opacity(0.14)
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

struct PikoHeroPanel<Action: View>: View {
    let title: String
    let subtitle: String
    let metric: String
    let action: Action

    init(
        title: String,
        subtitle: String,
        metric: String,
        @ViewBuilder action: () -> Action = { EmptyView() }
    ) {
        self.title = title
        self.subtitle = subtitle
        self.metric = metric
        self.action = action()
    }

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(title)
                    .font(.largeTitle.weight(.black))
                Text(subtitle)
                    .font(.subheadline)
                    .lineLimit(2)
                    .opacity(0.82)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 10) {
                Text(metric)
                    .font(.footnote.weight(.bold))
                    .padding(.horizontal, 13)
                    .padding(.vertical, 8)
                    .background(.white.opacity(0.18), in: Capsule())
                action
            }
        }
        .foregroundStyle(.white)
        .padding(20)
        .background(
            LinearGradient(
                colors: [PikoPalette.accent, PikoPalette.leaf, PikoPalette.warm],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 30, style: .continuous)
        )
    }
}

struct PikoSectionPanel<Content: View, Trailing: View>: View {
    let title: String
    let trailing: Trailing
    let content: Content

    init(
        title: String,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() },
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.trailing = trailing()
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(title)
                    .font(.headline.weight(.bold))
                Spacer()
                trailing
            }
            content
        }
        .padding(16)
        .background(.white.opacity(0.72), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .stroke(.white.opacity(0.66), lineWidth: 1)
        )
    }
}

struct PikoPill: View {
    let text: String
    var emphasized = false

    var body: some View {
        Text(text)
            .font(.footnote.weight(emphasized ? .bold : .medium))
            .foregroundStyle(emphasized ? PikoPalette.accent : .secondary)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                (emphasized ? PikoPalette.accent.opacity(0.14) : Color.secondary.opacity(0.12)),
                in: Capsule()
            )
    }
}

struct PikoEmptyPlane<Icon: View>: View {
    let text: String
    let icon: Icon

    init(text: String, @ViewBuilder icon: () -> Icon) {
        self.text = text
        self.icon = icon()
    }

    var body: some View {
        VStack(spacing: 14) {
            icon
                .padding(18)
                .background(.white.opacity(0.52), in: Circle())
            Text(text)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 22)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

enum LucideTabIcon {
    case download
    case send
    case settings

    private var pathData: [String] {
        switch self {
        case .download:
            return [
                "M21,15v4a2,2 0,0 1,-2 2H5a2,2 0,0 1,-2 -2v-4",
                "M7,10l5,5 5,-5",
                "M12,15V3"
            ]
        case .send:
            return [
                "M22,2L15,22 11,13 2,9Z",
                "M22,2L11,13"
            ]
        case .settings:
            return [
                "M12.22,2h-0.44a2,2 0,0 0,-2 2v0.18a2,2 0,0 1,-1 1.73l-0.43,0.25a2,2 0,0 1,-2 0l-0.15,-0.08a2,2 0,0 0,-2.73 0.73l-0.22,0.38a2,2 0,0 0,0.73 2.73l0.15,0.1a2,2 0,0 1,1 1.72v0.51a2,2 0,0 1,-1 1.74l-0.15,0.09a2,2 0,0 0,-0.73 2.73l0.22,0.38a2,2 0,0 0,2.73 0.73l0.15,-0.08a2,2 0,0 1,2 0l0.43,0.25a2,2 0,0 1,1 1.73V20a2,2 0,0 0,2 2h0.44a2,2 0,0 0,2 -2v-0.18a2,2 0,0 1,1 -1.73l0.43,-0.25a2,2 0,0 1,2 0l0.15,0.08a2,2 0,0 0,2.73 -0.73l0.22,-0.39a2,2 0,0 0,-0.73 -2.73l-0.15,-0.08a2,2 0,0 1,-1 -1.74v-0.5a2,2 0,0 1,1 -1.74l0.15,-0.09a2,2 0,0 0,0.73 -2.73l-0.22,-0.38a2,2 0,0 0,-2.73 -0.73l-0.15,0.08a2,2 0,0 1,-2 0l-0.43,-0.25a2,2 0,0 1,-1 -1.73V4a2,2 0,0 0,-2 -2z",
                "M12,12m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"
            ]
        }
    }

    var image: UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24))
        let image = renderer.image { _ in
            UIColor.label.setStroke()

            let path = UIBezierPath()
            path.lineWidth = 2
            path.lineCapStyle = .round
            path.lineJoinStyle = .round
            pathData.forEach { data in
                var parser = LucidePathParser(data)
                path.append(parser.parse())
            }

            path.stroke()
        }

        return image.withRenderingMode(.alwaysTemplate)
    }
}

struct LucidePathParser {
    private let data: String
    private var cursor: String.Index
    private var currentPoint = CGPoint.zero
    private var subpathStart = CGPoint.zero

    init(_ data: String) {
        self.data = data
        cursor = data.startIndex
    }

    mutating func parse() -> UIBezierPath {
        let path = UIBezierPath()
        var command: Character?

        while true {
            skipSeparators()
            guard cursor < data.endIndex else {
                break
            }

            if let explicitCommand = readCommand() {
                command = explicitCommand
            }

            guard let activeCommand = command else {
                break
            }

            switch activeCommand {
            case "M", "m":
                parseMove(path: path, relative: activeCommand == "m")
                command = activeCommand == "m" ? "l" : "L"
            case "L", "l":
                parseLines(path: path, relative: activeCommand == "l")
            case "H", "h":
                parseHorizontalLines(path: path, relative: activeCommand == "h")
            case "V", "v":
                parseVerticalLines(path: path, relative: activeCommand == "v")
            case "A", "a":
                parseArcs(path: path, relative: activeCommand == "a")
            case "Z", "z":
                path.close()
                currentPoint = subpathStart
                command = nil
            default:
                command = nil
            }
        }

        return path
    }

    private mutating func parseMove(path: UIBezierPath, relative: Bool) {
        guard let x = readNumber(), let y = readNumber() else {
            return
        }

        let point = resolvedPoint(x: x, y: y, relative: relative)
        path.move(to: point)
        currentPoint = point
        subpathStart = point
    }

    private mutating func parseLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let x = readNumber(), let y = readNumber() else {
                return
            }

            let point = resolvedPoint(x: x, y: y, relative: relative)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseHorizontalLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let x = readNumber() else {
                return
            }

            let point = CGPoint(x: relative ? currentPoint.x + x : x, y: currentPoint.y)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseVerticalLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let y = readNumber() else {
                return
            }

            let point = CGPoint(x: currentPoint.x, y: relative ? currentPoint.y + y : y)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseArcs(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard
                let radiusX = readNumber(),
                let radiusY = readNumber(),
                let rotation = readNumber(),
                let largeArc = readNumber(),
                let sweep = readNumber(),
                let x = readNumber(),
                let y = readNumber()
            else {
                return
            }

            let endPoint = resolvedPoint(x: x, y: y, relative: relative)
            addArc(
                to: path,
                from: currentPoint,
                end: endPoint,
                radiusX: radiusX,
                radiusY: radiusY,
                rotation: rotation,
                largeArc: largeArc != 0,
                sweep: sweep != 0
            )
            currentPoint = endPoint
        }
    }

    private func resolvedPoint(x: CGFloat, y: CGFloat, relative: Bool) -> CGPoint {
        if relative {
            return CGPoint(x: currentPoint.x + x, y: currentPoint.y + y)
        }

        return CGPoint(x: x, y: y)
    }

    private mutating func addArc(
        to path: UIBezierPath,
        from start: CGPoint,
        end: CGPoint,
        radiusX: CGFloat,
        radiusY: CGFloat,
        rotation: CGFloat,
        largeArc: Bool,
        sweep: Bool
    ) {
        guard radiusX > 0, radiusY > 0, start != end else {
            path.addLine(to: end)
            return
        }

        let phi = rotation * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)
        let dx = (start.x - end.x) / 2
        let dy = (start.y - end.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy
        let y1 = -sinPhi * dx + cosPhi * dy
        var rx = abs(radiusX)
        var ry = abs(radiusY)
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)

        if lambda > 1 {
            let scale = sqrt(lambda)
            rx *= scale
            ry *= scale
        }

        let rx2 = rx * rx
        let ry2 = ry * ry
        let x12 = x1 * x1
        let y12 = y1 * y1
        let denominator = rx2 * y12 + ry2 * x12

        guard denominator != 0 else {
            path.addLine(to: end)
            return
        }

        let sign: CGFloat = largeArc == sweep ? -1 : 1
        let numerator = max(0, rx2 * ry2 - rx2 * y12 - ry2 * x12)
        let coefficient = sign * sqrt(numerator / denominator)
        let centerX1 = coefficient * (rx * y1 / ry)
        let centerY1 = coefficient * (-ry * x1 / rx)
        let center = CGPoint(
            x: cosPhi * centerX1 - sinPhi * centerY1 + (start.x + end.x) / 2,
            y: sinPhi * centerX1 + cosPhi * centerY1 + (start.y + end.y) / 2
        )
        let vectorStart = CGVector(dx: (x1 - centerX1) / rx, dy: (y1 - centerY1) / ry)
        let vectorEnd = CGVector(dx: (-x1 - centerX1) / rx, dy: (-y1 - centerY1) / ry)
        let startAngle = vectorAngle(from: CGVector(dx: 1, dy: 0), to: vectorStart)
        var deltaAngle = vectorAngle(from: vectorStart, to: vectorEnd)

        if !sweep && deltaAngle > 0 {
            deltaAngle -= 2 * .pi
        } else if sweep && deltaAngle < 0 {
            deltaAngle += 2 * .pi
        }

        let steps = max(8, Int(ceil(abs(deltaAngle) / (.pi / 8))))
        for step in 1...steps {
            let angle = startAngle + deltaAngle * CGFloat(step) / CGFloat(steps)
            let point = CGPoint(
                x: center.x + cosPhi * rx * cos(angle) - sinPhi * ry * sin(angle),
                y: center.y + sinPhi * rx * cos(angle) + cosPhi * ry * sin(angle)
            )
            path.addLine(to: point)
        }
    }

    private func vectorAngle(from start: CGVector, to end: CGVector) -> CGFloat {
        let dot = start.dx * end.dx + start.dy * end.dy
        let determinant = start.dx * end.dy - start.dy * end.dx
        return atan2(determinant, dot)
    }

    private mutating func readCommand() -> Character? {
        skipSeparators()
        guard cursor < data.endIndex else {
            return nil
        }

        let character = data[cursor]
        guard "MmLlHhVvAaZz".contains(character) else {
            return nil
        }

        cursor = data.index(after: cursor)
        return character
    }

    private mutating func readNumber() -> CGFloat? {
        skipSeparators()
        guard cursor < data.endIndex else {
            return nil
        }

        let start = cursor
        if data[cursor] == "-" || data[cursor] == "+" {
            cursor = data.index(after: cursor)
        }

        while cursor < data.endIndex {
            let character = data[cursor]
            if character.isNumber || character == "." {
                cursor = data.index(after: cursor)
            } else if character == "e" || character == "E" {
                cursor = data.index(after: cursor)
                if cursor < data.endIndex, data[cursor] == "-" || data[cursor] == "+" {
                    cursor = data.index(after: cursor)
                }
            } else {
                break
            }
        }

        guard start != cursor, let value = Double(String(data[start..<cursor])) else {
            cursor = start
            return nil
        }

        return CGFloat(value)
    }

    private func hasNumberAhead() -> Bool {
        var parser = self
        return parser.readNumber() != nil
    }

    private mutating func skipSeparators() {
        while cursor < data.endIndex {
            let character = data[cursor]
            if character == " " || character == "\n" || character == "\t" || character == "," {
                cursor = data.index(after: cursor)
            } else {
                break
            }
        }
    }
}

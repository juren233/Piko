import SwiftUI
import UIKit

enum PikoPalette {
    static let surfaceUIColor = UIColor.systemBackground
    static let surfaceVariantUIColor = UIColor.secondarySystemBackground
    static let accentUIColor = UIColor.systemBlue

    static var surface: Color {
        Color(uiColor: surfaceUIColor)
    }

    static var accent: Color {
        Color(uiColor: accentUIColor)
    }

    static var surfaceVariant: Color {
        Color(uiColor: surfaceVariantUIColor)
    }

    static var pageBackground: Color {
        surface
    }
}

enum PikoFont {
    private enum ScreenTextScale {
        case compact
        case regular
        case expanded

        var factor: CGFloat {
            switch self {
            case .compact: return 0.92
            case .regular: return 1
            case .expanded: return 1.06
            }
        }
    }

    static var pageTitle: Font { scaled(34, textStyle: .largeTitle, weight: .black) }
    static var pageSubtitle: Font { scaled(15, textStyle: .subheadline) }
    static var sectionTitle: Font { scaled(17, textStyle: .headline, weight: .bold) }
    static var rowTitle: Font { scaled(16, textStyle: .body, weight: .semibold) }
    static var rowSubtitle: Font { scaled(15, textStyle: .subheadline) }
    static var compactTitle: Font { scaled(15, textStyle: .subheadline, weight: .semibold) }
    static var compactSubtitle: Font { scaled(13, textStyle: .caption1, weight: .medium) }
    static var pill: Font { scaled(13, textStyle: .footnote, weight: .medium) }
    static var emphasizedPill: Font { scaled(13, textStyle: .footnote, weight: .bold) }
    static var tabLabel: Font { scaled(11, textStyle: .caption2, weight: .semibold) }
    static var button: Font { scaled(13, textStyle: .caption1, weight: .semibold) }
    static var previewLabel: Font { scaled(12, textStyle: .caption1, weight: .bold) }
    static var badge: Font { scaled(11, textStyle: .caption2, weight: .semibold) }
    static var emptyState: Font { scaled(15, textStyle: .subheadline, weight: .semibold) }
    static var floatingAction: Font { scaled(20, textStyle: .title3, weight: .semibold) }
    static var deviceInitial: Font { scaled(17, textStyle: .headline, weight: .semibold) }
    static var emptyDots: Font { scaled(22, textStyle: .title2, weight: .bold) }
    static var settingsValue: Font { scaled(20, textStyle: .title3, weight: .semibold) }
    static var fileRowTitle: Font { scaled(17, textStyle: .body, weight: .medium) }

    private static var screenTextScale: ScreenTextScale {
        let size = UIScreen.main.bounds.size
        let compactWidth = min(size.width, size.height)
        if compactWidth <= 375 {
            return .compact
        }
        if compactWidth >= 430 {
            return .expanded
        }
        return .regular
    }

    private static func scaled(
        _ baseSize: CGFloat,
        textStyle: UIFont.TextStyle,
        weight: Font.Weight = .regular
    ) -> Font {
        let scaledSize = UIFontMetrics(forTextStyle: textStyle).scaledValue(for: baseSize * screenTextScale.factor)
        return .system(size: scaledSize, weight: weight)
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
        HStack(alignment: .bottom, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(PikoFont.pageTitle)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text(subtitle)
                    .font(PikoFont.pageSubtitle)
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 8) {
                PikoPill(text: metric, emphasized: true)
                action
            }
        }
        .padding(.top, 10)
        .padding(.bottom, 12)
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
                    .font(PikoFont.sectionTitle)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer()
                trailing
            }
            content
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 18)
        .background(PikoPalette.surface.opacity(0.62), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct PikoPill: View {
    let text: String
    var emphasized = false

    var body: some View {
        Text(text)
            .font(emphasized ? PikoFont.emphasizedPill : PikoFont.pill)
            .foregroundStyle(emphasized ? PikoPalette.accent : .secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.88)
            .truncationMode(.tail)
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
            Text(text)
                .font(PikoFont.emptyState)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .truncationMode(.tail)
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
    case inbox
    case file
    case image
    case plus
    case x
    case pause
    case chevronRight
    case check
    case smartphone
    case refreshCw

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
        case .inbox:
            return [
                "M22,12H16l-2,3H10l-2,-3H2",
                "M5.45,5.11L2,12v6a2,2 0,0 0,2 2h16a2,2 0,0 0,2 -2v-6l-3.45,-6.89A2,2 0,0 0,16.76 4H7.24a2,2 0,0 0,-1.79 1.11z"
            ]
        case .file:
            return [
                "M6,22a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2h8a2.4,2.4 0,0 1,1.704 0.706l3.588,3.588A2.4,2.4 0,0 1,20 8v12a2,2 0,0 1,-2 2z",
                "M14,2v5a1,1 0,0 0,1 1h5"
            ]
        case .image:
            return [
                "M5,3h14a2,2 0,0 1,2 2v14a2,2 0,0 1,-2 2H5a2,2 0,0 1,-2 -2V5a2,2 0,0 1,2 -2z",
                "M9,9m-2,0a2,2 0,1 0,4 0a2,2 0,1 0,-4 0",
                "M21,15l-3.086,-3.086a2,2 0,0 0,-2.828 0L6,21"
            ]
        case .plus:
            return [
                "M5,12h14",
                "M12,5v14"
            ]
        case .x:
            return [
                "M18,6L6,18",
                "M6,6l12,12"
            ]
        case .pause:
            return [
                "M6,4h4v16H6z",
                "M14,4h4v16h-4z"
            ]
        case .chevronRight:
            return [
                "M9,18l6,-6 -6,-6"
            ]
        case .check:
            return [
                "M20,6L9,17l-5,-5"
            ]
        case .smartphone:
            return [
                "M7,2h10a2,2 0,0 1,2 2v16a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2z",
                "M12,18h.01"
            ]
        case .refreshCw:
            return [
                "M3,12a9,9 0,0 1,9 -9 9.75,9.75 0,0 1,6.74 2.74L21,8",
                "M21,3v5h-5",
                "M21,12a9,9 0,0 1,-9 9 9.75,9.75 0,0 1,-6.74 -2.74L3,16",
                "M8,16H3v5"
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

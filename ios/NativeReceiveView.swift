import SwiftUI
import UIKit

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel
    @State private var deleteFailureMessage: String?

    var body: some View {
        NativeReceiveTableView(
            model: model,
            onResetDeviceName: model.resetDeviceNickname,
            onCancelReceive: model.cancelReceiveTransfer,
            onDeleteReceiveHistory: { item, deleteFiles, completion in
                model.deleteReceiveHistory(item, deleteFiles: deleteFiles, completion: completion)
            },
            onDeleteFailure: { failedCount in
                if failedCount > 0 {
                    deleteFailureMessage = "有\(failedCount)个文件未删除"
                }
            }
        )
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
        .alert(deleteFailureMessage ?? "", isPresented: Binding(
            get: { deleteFailureMessage != nil },
            set: { if !$0 { deleteFailureMessage = nil } }
        )) {
            Button("好", role: .cancel) { deleteFailureMessage = nil }
        }
    }
}

private struct NativeReceiveTableView: UIViewControllerRepresentable {
    @ObservedObject var model: NativePikoModel
    let onResetDeviceName: () -> Void
    let onCancelReceive: () -> Void
    let onDeleteReceiveHistory: (NativeReceiveHistoryItem, Bool, @escaping (Int) -> Void) -> Void
    let onDeleteFailure: (Int) -> Void

    func makeUIViewController(context: Context) -> NativeReceiveTableViewController {
        NativeReceiveTableViewController()
    }

    func updateUIViewController(_ controller: NativeReceiveTableViewController, context: Context) {
        controller.update(
            model: model,
            onResetDeviceName: onResetDeviceName,
            onCancelReceive: onCancelReceive,
            onDeleteReceiveHistory: onDeleteReceiveHistory,
            onDeleteFailure: onDeleteFailure
        )
    }
}

private enum NativeReceiveTableRow {
    case hero(count: Int)
    case deviceName(String)
    case empty
    case active(NativeReceiveTransferState)
    case history(NativeReceiveHistoryItem)
    case spacer

    var reuseIdentifier: String {
        switch self {
        case .history:
            return "history"
        default:
            return "content"
        }
    }

    var isHistory: Bool {
        if case .history = self {
            return true
        }
        return false
    }
}

private final class NativeReceiveTableViewController: UITableViewController {
    private var rows: [NativeReceiveTableRow] = []
    private var onResetDeviceName: (() -> Void)?
    private var onCancelReceive: (() -> Void)?
    private var onDeleteReceiveHistory: ((NativeReceiveHistoryItem, Bool, @escaping (Int) -> Void) -> Void)?
    private var onDeleteFailure: ((Int) -> Void)?

    init() {
        super.init(style: .plain)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = PikoPalette.pageBackgroundUIColor
        tableView.backgroundColor = PikoPalette.pageBackgroundUIColor
        tableView.separatorStyle = .none
        tableView.showsVerticalScrollIndicator = false
        tableView.estimatedRowHeight = 96
        tableView.rowHeight = UITableView.automaticDimension
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "content")
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "history")
    }

    func update(
        model: NativePikoModel,
        onResetDeviceName: @escaping () -> Void,
        onCancelReceive: @escaping () -> Void,
        onDeleteReceiveHistory: @escaping (NativeReceiveHistoryItem, Bool, @escaping (Int) -> Void) -> Void,
        onDeleteFailure: @escaping (Int) -> Void
    ) {
        self.onResetDeviceName = onResetDeviceName
        self.onCancelReceive = onCancelReceive
        self.onDeleteReceiveHistory = onDeleteReceiveHistory
        self.onDeleteFailure = onDeleteFailure
        rows = Self.makeRows(model: model)
        tableView.reloadData()
    }

    private static func makeRows(model: NativePikoModel) -> [NativeReceiveTableRow] {
        var nextRows: [NativeReceiveTableRow] = [
            .hero(count: model.receiveHistory.count),
            .deviceName(model.currentDeviceName),
        ]
        if model.receiveHistory.isEmpty && model.activeReceive == nil {
            nextRows.append(.empty)
        } else {
            if let activeReceive = model.activeReceive {
                nextRows.append(.active(activeReceive))
            }
            nextRows.append(contentsOf: model.receiveHistory.map(NativeReceiveTableRow.history))
            nextRows.append(.spacer)
        }
        return nextRows
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        rows.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let row = rows[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: row.reuseIdentifier, for: indexPath) as! NativeHostingTableCell
        cell.configure(rootView: viewForRow(row), parent: self)
        return cell
    }

    private func viewForRow(_ row: NativeReceiveTableRow) -> AnyView {
        switch row {
        case let .hero(count):
            return rowView(top: 32, bottom: 24) {
                PikoHeroPanel(
                    title: "Piko",
                    subtitle: "接收记录和本机收件箱",
                    metric: "\(count) 次"
                )
            }
        case let .deviceName(nickname):
            return rowView(bottom: 24) {
                NativeDeviceNicknameBanner(
                    nickname: nickname,
                    onReset: { [weak self] in self?.onResetDeviceName?() }
                )
            }
        case .empty:
            return rowView(bottom: 136) {
                NativeReceiveEmptyState()
            }
        case let .active(transfer):
            return rowView(bottom: 12) {
                NativeActiveReceiveCard(
                    transfer: transfer,
                    onCancel: { [weak self] in self?.onCancelReceive?() }
                )
            }
        case let .history(item):
            return rowView(bottom: 12) {
                NativeSwipeToDeleteReceiveHistoryCard(
                    item: item,
                    onDeleteClick: { [weak self] in
                        self?.presentDeleteConfirmation(for: item)
                    }
                )
            }
        case .spacer:
            return AnyView(
                Color.clear
                    .frame(height: 112)
            )
        }
    }

    private func rowView<Content: View>(
        top: CGFloat = 0,
        leading: CGFloat = 24,
        trailing: CGFloat = 24,
        bottom: CGFloat = 0,
        @ViewBuilder content: () -> Content
    ) -> AnyView {
        AnyView(
            content()
                .padding(EdgeInsets(top: top, leading: leading, bottom: bottom, trailing: trailing))
                .frame(maxWidth: .infinity)
                .background(PikoPalette.pageBackground)
        )
    }

    private func presentDeleteConfirmation(
        for item: NativeReceiveHistoryItem
    ) {
        let alert = UIAlertController(
            title: item.deleteConfirmationTitle,
            message: item.deleteConfirmationBody,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "算了", style: .default))
        alert.addAction(UIAlertAction(title: "仅删除记录", style: .destructive) { [weak self] _ in
            self?.confirmDelete(item, deleteFiles: false)
        })
        alert.addAction(UIAlertAction(title: "删除记录与文件", style: .destructive) { [weak self] _ in
            self?.confirmDelete(item, deleteFiles: true)
        })
        present(alert, animated: true)
    }

    private func confirmDelete(
        _ item: NativeReceiveHistoryItem,
        deleteFiles: Bool
    ) {
        onDeleteReceiveHistory?(item, deleteFiles) { [weak self] failedCount in
            if failedCount > 0 {
                self?.onDeleteFailure?(failedCount)
            }
        }
    }
}

private final class NativeHostingTableCell: UITableViewCell {
    private var hostingController: UIHostingController<AnyView>?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        selectionStyle = .none
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(rootView: AnyView, parent: UIViewController) {
        if let hostingController {
            hostingController.rootView = rootView
            hostingController.view.invalidateIntrinsicContentSize()
            return
        }

        let controller = UIHostingController(rootView: rootView)
        controller.view.backgroundColor = .clear
        hostingController = controller
        parent.addChild(controller)
        contentView.addSubview(controller.view)
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            controller.view.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            controller.view.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            controller.view.topAnchor.constraint(equalTo: contentView.topAnchor),
            controller.view.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
        controller.didMove(toParent: parent)
    }
}

private struct NativeDeviceNicknameBanner: View {
    let nickname: String
    let onReset: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(uiImage: LucideTabIcon.smartphone.image)
                .resizable()
                .frame(width: 22, height: 22)
                .foregroundStyle(PikoPalette.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(nickname)
                    .font(PikoFont.compactTitle)
                    .lineLimit(2)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text("本设备名称")
                    .font(PikoFont.compactSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(1)
            Button(action: onReset) {
                HStack(spacing: 6) {
                    Image(uiImage: LucideTabIcon.refreshCw.image)
                        .resizable()
                        .frame(width: 15, height: 15)
                    Text("换个昵称")
                        .lineLimit(1)
                        .minimumScaleFactor(0.88)
                        .truncationMode(.tail)
                }
                .font(PikoFont.button)
                .foregroundStyle(PikoPalette.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(PikoPalette.accent.opacity(0.1), in: Capsule())
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(PikoPalette.surface.opacity(0.58), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
        )
    }
}

private struct NativeReceiveEmptyState: View {
    var body: some View {
        PikoEmptyPlane(text: "还没有接收过文件") {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(PikoPalette.surfaceVariant.opacity(0.34))
                .frame(width: 76, height: 76)
                .overlay {
                    Image(uiImage: LucideTabIcon.inbox.image)
                        .resizable()
                        .frame(width: 38, height: 38)
                        .foregroundStyle(.secondary.opacity(0.78))
                }
        }
    }
}
private struct NativeActiveReceiveCard: View {
    let transfer: NativeReceiveTransferState
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            NativeActiveReceiveProgressIcon(transfer: transfer)
            VStack(alignment: .leading, spacing: 4) {
                Text(transfer.title)
                    .font(PikoFont.compactTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text(transfer.subtitle)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer()
            Button(action: onCancel) {
                Image(uiImage: LucideTabIcon.x.image)
                    .resizable()
                    .frame(width: 20, height: 20)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.red)
            .offset(x: -8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(PikoPalette.surface.opacity(0.56), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
        )
    }
}

private struct NativeActiveReceiveProgressIcon: View {
    let transfer: NativeReceiveTransferState

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(PikoPalette.accent.opacity(0.08))
                .frame(width: 52, height: 52)
                .overlay {
                    Image(uiImage: transfer.primaryFileType == .image ? LucideTabIcon.image.image : LucideTabIcon.download.image)
                        .resizable()
                        .frame(width: 24, height: 24)
                        .foregroundStyle(PikoPalette.accent)
                }
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .trim(from: 0, to: transfer.progress)
                .stroke(PikoPalette.accent.opacity(0.78), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: 60, height: 60)
        }
        .frame(width: 60, height: 60)
    }
}

private struct NativeSwipeToDeleteReceiveHistoryCard: View {
    let item: NativeReceiveHistoryItem
    let onDeleteClick: () -> Void
    private let deleteWidth: CGFloat = 96
    @State private var settledOffset: CGFloat = 0
    @GestureState private var dragTranslation: CGFloat = 0

    private var currentOffset: CGFloat {
        min(max(settledOffset + dragTranslation, -deleteWidth), 0)
    }

    private var revealFraction: CGFloat {
        min(max(-currentOffset / deleteWidth, 0), 1)
    }

    private var deleteButtonOffset: CGFloat {
        min(max(deleteWidth + currentOffset, 0), deleteWidth)
    }

    var body: some View {
        NativeReceiveHistoryCard(item: item)
            .opacity(0)
            .accessibilityHidden(true)
            .allowsHitTesting(false)
            .overlay(alignment: .trailing) {
                Button(action: onDeleteClick) {
                    Text("删除")
                        .font(PikoFont.sectionTitle)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.white.opacity(Double(revealFraction)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .buttonStyle(.plain)
                .allowsHitTesting(revealFraction >= 0.96)
                .frame(width: deleteWidth)
                .background(
                    Color(uiColor: .systemRed).opacity(0.92),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                )
                .offset(x: deleteButtonOffset)
            }
            .overlay {
                NativeReceiveHistoryCard(item: item)
                    .offset(x: currentOffset)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if settledOffset < 0 {
                            settledOffset = 0
                        }
                    }
                    .gesture(
                        DragGesture(minimumDistance: 8)
                            .updating($dragTranslation) { value, state, _ in
                                let nextOffset = min(max(settledOffset + value.translation.width, -deleteWidth), 0)
                                state = nextOffset - settledOffset
                            }
                            .onEnded { value in
                                let nextOffset = min(max(settledOffset + value.translation.width, -deleteWidth), 0)
                                settledOffset = nextOffset <= -deleteWidth * 0.42 ? -deleteWidth : 0
                            }
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .animation(.easeOut(duration: 0.18), value: settledOffset)
    }
}

private struct NativeReceiveHistoryCard: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        HStack(spacing: 14) {
            NativeReceiveHistoryPreview(item: item)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(PikoFont.rowTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text(item.subtitle)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer()
            Image(uiImage: LucideTabIcon.chevronRight.image)
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(.secondary.opacity(0.7))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(PikoPalette.surface.opacity(0.56), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.secondary.opacity(0.16), lineWidth: 1)
        )
    }
}

private struct NativeReceiveHistoryPreview: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        if item.fileCount > 1 {
            NativeMultiFilePreview(fileType: item.primaryFileType, count: item.fileCount)
        } else if let mediaPreviewData = item.mediaPreviewData {
            NativeMediaPreview(data: mediaPreviewData)
        } else {
            NativeFileTypePreview(fileType: item.primaryFileType)
        }
    }
}

private struct NativeFileTypePreview: View {
    let fileType: NativeFileType

    var body: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(PikoPalette.surfaceVariant.opacity(0.28))
            .frame(width: 60, height: 60)
            .overlay {
                Text(fileType.previewLabel)
                    .font(PikoFont.previewLabel)
                    .foregroundStyle(.primary)
            }
    }
}

private struct NativeMediaPreview: View {
    let data: Data

    var body: some View {
        if let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 60, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(PikoPalette.accent.opacity(0.12))
                .frame(width: 60, height: 60)
                .overlay {
                    Image(uiImage: LucideTabIcon.image.image)
                        .resizable()
                        .frame(width: 24, height: 24)
                        .foregroundStyle(PikoPalette.accent)
                }
        }
    }
}

private struct NativeMultiFilePreview: View {
    let fileType: NativeFileType
    let count: Int

    var body: some View {
        ZStack {
            NativeLayeredPreviewCard()
                .frame(width: 42, height: 42)
                .offset(x: 8, y: -6)
            NativeLayeredPreviewCard()
                .frame(width: 44, height: 44)
                .offset(x: -8, y: 8)
            NativeLayeredPreviewCard(label: fileType.previewLabel)
            .frame(width: 47, height: 47)
            Text("+\(count - 1)")
                .font(PikoFont.badge)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(PikoPalette.surface.opacity(0.92), in: Capsule())
                .offset(x: 18, y: 18)
        }
        .frame(width: 60, height: 60)
    }
}

private struct NativeLayeredPreviewCard: View {
    var label: String?

    var body: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(PikoPalette.surfaceVariant.opacity(0.34))
            .overlay {
                if let label = label {
                    Text(label)
                        .font(PikoFont.previewLabel)
                }
            }
    }
}

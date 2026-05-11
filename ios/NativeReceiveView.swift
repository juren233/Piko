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
        .ignoresSafeArea(.container, edges: [.top, .bottom])
        .background(PikoPalette.pageBackground.ignoresSafeArea())
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
    case gap(CGFloat)
    case spacer

    var reuseIdentifier: String {
        switch self {
        case .hero:
            return "hero"
        case .deviceName:
            return "deviceName"
        case .empty:
            return "empty"
        case .active:
            return "active"
        case .history:
            return "history"
        case .gap:
            return "gap"
        case .spacer:
            return "spacer"
        }
    }

    var isHistory: Bool {
        if case .history = self {
            return true
        }
        return false
    }
}

private enum NativeReceiveLayout {
    static let pageHorizontalInset: CGFloat = 24
    static let contentTrailingInset: CGFloat = 0
    static let historyRowSpacing: CGFloat = 12
    static let deviceNicknameBottomSpacing: CGFloat = 8
    static let deviceNicknameVerticalPadding: CGFloat = 9
    static let emptyStateTopSpacing: CGFloat = 24
    static let emptyStateBottomSpacing: CGFloat = 112
    static let emptyStateMinimumContentHeight: CGFloat = 164
    static let heroEstimatedHeight: CGFloat = 116
    static let deviceNameEstimatedHeight: CGFloat = 64
    static let bottomSpacerHeight: CGFloat = 112

    static func emptyStateRowHeight(for tableHeight: CGFloat) -> CGFloat {
        let occupiedHeight = heroEstimatedHeight + deviceNameEstimatedHeight
        let minimumHeight = emptyStateTopSpacing + emptyStateMinimumContentHeight + emptyStateBottomSpacing
        return max(minimumHeight, tableHeight - occupiedHeight)
    }
}

private final class NativeReceiveTableViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let tableView = UITableView(frame: .zero, style: .plain)
    private var rows: [NativeReceiveTableRow] = []
    private var pendingRows: [NativeReceiveTableRow]?
    private var needsDeferredReload = false
    private var lastEmptyStateRowHeight: CGFloat = 0
    private var onResetDeviceName: (() -> Void)?
    private var onCancelReceive: (() -> Void)?
    private var onDeleteReceiveHistory: ((NativeReceiveHistoryItem, Bool, @escaping (Int) -> Void) -> Void)?
    private var onDeleteFailure: ((Int) -> Void)?

    init() {
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = PikoPalette.pageBackgroundUIColor
        view.insetsLayoutMarginsFromSafeArea = false
        tableView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(tableView)
        NSLayoutConstraint.activate([
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -NativeReceiveLayout.pageHorizontalInset),
            tableView.topAnchor.constraint(equalTo: view.topAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        tableView.backgroundColor = PikoPalette.pageBackgroundUIColor
        tableView.separatorStyle = .none
        tableView.showsVerticalScrollIndicator = false
        tableView.estimatedRowHeight = 96
        tableView.rowHeight = UITableView.automaticDimension
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.insetsLayoutMarginsFromSafeArea = false
        tableView.insetsContentViewsToSafeArea = false
        tableView.layoutMargins = .zero
        tableView.contentInset = .zero
        tableView.scrollIndicatorInsets = .zero
        tableView.dataSource = self
        tableView.delegate = self
        if #available(iOS 15.0, *) {
            tableView.sectionHeaderTopPadding = 0
        }
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "hero")
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "deviceName")
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "empty")
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "active")
        tableView.register(NativeHostingTableCell.self, forCellReuseIdentifier: "history")
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "gap")
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "spacer")
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        reloadEmptyStateIfNeeded()
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
        scheduleRows(Self.makeRows(model: model))
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
            for (index, item) in model.receiveHistory.enumerated() {
                nextRows.append(.history(item))
                if index < model.receiveHistory.count - 1 {
                    nextRows.append(.gap(NativeReceiveLayout.historyRowSpacing))
                }
            }
            nextRows.append(.spacer)
        }
        return nextRows
    }

    private func scheduleRows(_ nextRows: [NativeReceiveTableRow]) {
        if rows.isEmpty && pendingRows == nil && !needsDeferredReload {
            applyRows(nextRows)
            return
        }
        pendingRows = nextRows
        guard !needsDeferredReload else {
            return
        }
        needsDeferredReload = true
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                return
            }
            self.needsDeferredReload = false
            guard let pendingRows = self.pendingRows else {
                return
            }
            self.pendingRows = nil
            self.applyRows(pendingRows)
        }
    }

    private func applyRows(_ nextRows: [NativeReceiveTableRow]) {
        rows = nextRows
        lastEmptyStateRowHeight = emptyStateRowHeight()
        UIView.performWithoutAnimation {
            tableView.reloadData()
            tableView.layoutIfNeeded()
        }
    }

    private func emptyStateRowHeight() -> CGFloat {
        NativeReceiveLayout.emptyStateRowHeight(for: tableView.bounds.height)
    }

    private func reloadEmptyStateIfNeeded() {
        guard rows.contains(where: { row in
            if case .empty = row {
                return true
            }
            return false
        }) else {
            return
        }
        let nextHeight = emptyStateRowHeight()
        guard abs(nextHeight - lastEmptyStateRowHeight) > 0.5 else {
            return
        }
        lastEmptyStateRowHeight = nextHeight
        UIView.performWithoutAnimation {
            tableView.reloadData()
            tableView.layoutIfNeeded()
        }
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        rows.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let row = rows[indexPath.row]
        if case .gap = row {
            return emptyFixedHeightCell(for: row, at: indexPath)
        }
        if case .spacer = row {
            return emptyFixedHeightCell(for: row, at: indexPath)
        }

        let cell = tableView.dequeueReusableCell(withIdentifier: row.reuseIdentifier, for: indexPath) as! NativeHostingTableCell
        cell.configure(rootView: viewForRow(row), parent: self)
        return cell
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        switch rows[indexPath.row] {
        case let .gap(height):
            return height
        case .empty:
            return emptyStateRowHeight()
        case .spacer:
            return NativeReceiveLayout.bottomSpacerHeight
        default:
            return UITableView.automaticDimension
        }
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        switch rows[indexPath.row] {
        case let .gap(height):
            return height
        case .spacer:
            return NativeReceiveLayout.bottomSpacerHeight
        case .hero:
            return NativeReceiveLayout.heroEstimatedHeight
        case .deviceName:
            return NativeReceiveLayout.deviceNameEstimatedHeight
        case .empty:
            return emptyStateRowHeight()
        case .active, .history:
            return 84
        }
    }

    func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        guard case let .history(item) = rows[indexPath.row] else {
            return nil
        }

        let deleteAction = UIContextualAction(style: .destructive, title: "删除") { [weak self] _, _, completion in
            guard let self else {
                completion(false)
                return
            }
            self.presentDeleteConfirmation(for: item, swipeCompletion: completion)
        }
        deleteAction.backgroundColor = .systemRed

        let configuration = UISwipeActionsConfiguration(actions: [deleteAction])
        configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }

    private func emptyFixedHeightCell(for row: NativeReceiveTableRow, at indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: row.reuseIdentifier, for: indexPath)
        cell.backgroundColor = .clear
        cell.contentView.backgroundColor = .clear
        cell.selectionStyle = .none
        return cell
    }

    private func viewForRow(_ row: NativeReceiveTableRow) -> AnyView {
        switch row {
        case let .hero(count):
            return rowView(top: 28, bottom: 8) {
                PikoHeroPanel(
                    title: "Piko",
                    subtitle: "接收记录和本机收件箱",
                    metric: "\(count) 次"
                )
            }
        case let .deviceName(nickname):
            return rowView(bottom: NativeReceiveLayout.deviceNicknameBottomSpacing) {
                NativeDeviceNicknameBanner(
                    nickname: nickname,
                    onReset: { [weak self] in self?.onResetDeviceName?() }
                )
            }
        case .empty:
            return emptyStateCardView(
                height: emptyStateRowHeight(),
                top: NativeReceiveLayout.emptyStateTopSpacing,
                bottom: NativeReceiveLayout.emptyStateBottomSpacing
            ) {
                NativeReceiveEmptyStateContent()
            }
        case let .active(transfer):
            return rowView(bottom: 12) {
                NativeActiveReceiveCard(
                    transfer: transfer,
                    onCancel: { [weak self] in self?.onCancelReceive?() }
                )
            }
        case let .history(item):
            return rowView {
                NativeReceiveHistoryCard(item: item)
            }
        case let .gap(height):
            return AnyView(
                Color.clear
                    .frame(height: height)
            )
        case .spacer:
            return AnyView(
                Color.clear
                    .frame(height: NativeReceiveLayout.bottomSpacerHeight)
            )
        }
    }

    private func rowView<Content: View>(
        top: CGFloat = 0,
        leading: CGFloat = NativeReceiveLayout.pageHorizontalInset,
        trailing: CGFloat = NativeReceiveLayout.contentTrailingInset,
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

    private func emptyStateCardView<Content: View>(
        height: CGFloat,
        top: CGFloat = 0,
        leading: CGFloat = NativeReceiveLayout.pageHorizontalInset,
        trailing: CGFloat = NativeReceiveLayout.contentTrailingInset,
        bottom: CGFloat = 0,
        @ViewBuilder content: () -> Content
    ) -> AnyView {
        let cardHeight = max(CGFloat.zero, height - top - bottom)
        return AnyView(
            content()
                .padding(.horizontal, 22)
                .frame(maxWidth: .infinity)
                .frame(height: cardHeight)
                .padding(EdgeInsets(top: top, leading: leading, bottom: bottom, trailing: trailing))
                .frame(maxWidth: .infinity)
                .frame(height: height, alignment: .top)
                .background(PikoPalette.pageBackground)
        )
    }

    private func presentDeleteConfirmation(
        for item: NativeReceiveHistoryItem,
        swipeCompletion: @escaping (Bool) -> Void
    ) {
        let alert = UIAlertController(
            title: item.deleteConfirmationTitle,
            message: item.deleteConfirmationBody,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "算了", style: .cancel) { _ in
            swipeCompletion(false)
        })
        alert.addAction(UIAlertAction(title: "仅删除记录", style: .destructive) { [weak self] _ in
            guard let self else {
                swipeCompletion(false)
                return
            }
            self.confirmDelete(item, deleteFiles: false, swipeCompletion: swipeCompletion)
        })
        alert.addAction(UIAlertAction(title: "删除记录与文件", style: .destructive) { [weak self] _ in
            guard let self else {
                swipeCompletion(false)
                return
            }
            self.confirmDelete(item, deleteFiles: true, swipeCompletion: swipeCompletion)
        })
        present(alert, animated: true)
    }

    private func confirmDelete(
        _ item: NativeReceiveHistoryItem,
        deleteFiles: Bool,
        swipeCompletion: @escaping (Bool) -> Void
    ) {
        guard let onDeleteReceiveHistory else {
            swipeCompletion(false)
            return
        }
        onDeleteReceiveHistory(item, deleteFiles) { [weak self] failedCount in
            DispatchQueue.main.async {
                if failedCount > 0 {
                    self?.onDeleteFailure?(failedCount)
                }
            }
        }
        swipeCompletion(true)
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
            hostingController.view.setNeedsLayout()
            contentView.setNeedsLayout()
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
        .padding(.vertical, NativeReceiveLayout.deviceNicknameVerticalPadding)
        .background(PikoPalette.surface.opacity(0.58), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(Color.secondary.opacity(0.16), lineWidth: 1)
        )
    }
}

private struct NativeReceiveEmptyStateContent: View {
    var body: some View {
        VStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(PikoPalette.surfaceVariant.opacity(0.34))
                .frame(width: 76, height: 76)
                .overlay {
                    Image(uiImage: LucideTabIcon.inbox.image)
                        .resizable()
                        .frame(width: 38, height: 38)
                        .foregroundStyle(.secondary.opacity(0.78))
                }
            Text("还没有接收过文件")
                .font(PikoFont.emptyState)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity)
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
                .strokeBorder(Color.secondary.opacity(0.16), lineWidth: 1)
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
                .strokeBorder(Color.secondary.opacity(0.16), lineWidth: 1)
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

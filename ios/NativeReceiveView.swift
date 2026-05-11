import SwiftUI
import UIKit
import Foundation
import OSLog

private let nativeReceiveViewLogger = Logger(subsystem: "com.juren233.piko", category: "receive-list")

private func receiveListLog(_ message: String) {
    nativeReceiveViewLogger.notice("\(message, privacy: .public)")
    NSLog("%@", message)
}

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel
    @State private var deleteFailureMessage: String?

    var body: some View {
        NativeReceiveTable(model: model) { failedCount in
            if failedCount > 0 {
                deleteFailureMessage = "有\(failedCount)个文件未删除"
            }
        }
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

private struct NativeReceiveTable: UIViewControllerRepresentable {
    let model: NativePikoModel
    let onDeleteFailure: (Int) -> Void

    func makeUIViewController(context: Context) -> NativeReceiveTableViewController {
        let controller = NativeReceiveTableViewController(style: .plain)
        controller.configure(model: model, onDeleteFailure: onDeleteFailure)
        receiveListLog("[ReceiveList] make controller history=\(self.model.receiveHistory.count) active=\(self.model.activeReceive == nil ? 0 : 1)")
        return controller
    }

    func updateUIViewController(_ controller: NativeReceiveTableViewController, context: Context) {
        controller.configure(model: model, onDeleteFailure: onDeleteFailure)
        receiveListLog("[ReceiveList] update controller history=\(self.model.receiveHistory.count) active=\(self.model.activeReceive == nil ? 0 : 1)")
        controller.apply(rows: NativeReceiveRow.rows(for: model))
    }
}

private enum NativeReceiveRow {
    case hero(count: Int)
    case device(nickname: String)
    case empty
    case active(NativeReceiveTransferState)
    case history(NativeReceiveHistoryItem)
    case spacer

    var diagnosticDescription: String {
        switch self {
        case .hero(let count):
            return "hero(count:\(count))"
        case .device:
            return "device"
        case .empty:
            return "empty"
        case .active(let transfer):
            return "active(id:\(transfer.id),files:\(transfer.files.count),received:\(transfer.receivedBytes),total:\(transfer.totalBytes))"
        case .history(let item):
            return item.receiveListDiagnosticDescription
        case .spacer:
            return "spacer"
        }
    }

    var expectedTableHeight: CGFloat? {
        switch self {
        case .history, .active:
            return 80
        case .spacer:
            return NativeReceiveLayout.bottomSpacerHeight
        case .hero, .device, .empty:
            return nil
        }
    }

    static func rows(for model: NativePikoModel) -> [NativeReceiveRow] {
        var result: [NativeReceiveRow] = [
            .hero(count: model.receiveHistory.count),
            .device(nickname: model.currentDeviceName),
        ]
        if model.receiveHistory.isEmpty && model.activeReceive == nil {
            result.append(.empty)
        } else {
            if let activeReceive = model.activeReceive {
                result.append(.active(activeReceive))
            }
            result.append(contentsOf: model.receiveHistory.map(NativeReceiveRow.history))
            result.append(.spacer)
        }
        return result
    }

    func makeView(model: NativePikoModel) -> AnyView? {
        let probeName = diagnosticDescription
        switch self {
        case .hero(let count):
            return AnyView(
                rowView(top: 28, bottom: 8) {
                    PikoHeroPanel(
                        title: "Piko",
                        subtitle: "接收记录和本机收件箱",
                        metric: "\(count) 次"
                    )
                }
                .receiveListLayoutProbe(probeName)
            )
        case .device(let nickname):
            return AnyView(
                rowView(bottom: NativeReceiveLayout.deviceNicknameBottomSpacing) {
                    NativeDeviceNicknameBanner(
                        nickname: nickname,
                        onReset: model.resetDeviceNickname
                    )
                }
                .receiveListLayoutProbe(probeName)
            )
        case .empty:
            return AnyView(
                emptyStateCardView(
                    top: NativeReceiveLayout.emptyStateTopSpacing,
                    bottom: NativeReceiveLayout.emptyStateBottomSpacing
                ) {
                    NativeReceiveEmptyStateContent()
                }
                .receiveListLayoutProbe(probeName)
            )
        case .active(let transfer):
            return AnyView(
                rowView(
                    leading: NativeReceiveLayout.pageHorizontalInset,
                    trailing: NativeReceiveLayout.fileRowTrailingInset
                ) {
                    NativeActiveReceiveRow(
                        transfer: transfer,
                        onCancel: model.cancelReceiveTransfer
                    )
                }
                .receiveListLayoutProbe(probeName)
            )
        case .history(let item):
            return AnyView(
                rowView(
                    leading: NativeReceiveLayout.pageHorizontalInset,
                    trailing: NativeReceiveLayout.fileRowTrailingInset
                ) {
                    NativeReceiveHistoryRow(item: item)
                }
                .receiveListLayoutProbe(probeName)
            )
        case .spacer:
            return nil
        }
    }
}

private final class NativeReceiveTableViewController: UITableViewController {
    private var model: NativePikoModel?
    private var onDeleteFailure: ((Int) -> Void)?
    private var rows: [NativeReceiveRow] = []
    private var isApplyingAnimatedDelete = false
    private var lastScrollLogOffsetY: CGFloat?

    func configure(model: NativePikoModel, onDeleteFailure: @escaping (Int) -> Void) {
        self.model = model
        self.onDeleteFailure = onDeleteFailure
    }

    func apply(rows: [NativeReceiveRow]) {
        let previousRows = self.rows
        self.rows = rows
        receiveListLog("[ReceiveList] apply previous=\(previousRows.count) next=\(rows.count) previousRows=\(previousRows.diagnosticDescription) nextRows=\(rows.diagnosticDescription)")
        guard isViewLoaded else {
            receiveListLog("[ReceiveList] apply deferred viewLoaded=false")
            return
        }
        guard !isApplyingAnimatedDelete else {
            receiveListLog("[ReceiveList] apply skipped animatedDelete=true nextRows=\(rows.diagnosticDescription)")
            return
        }
        receiveListLog("[ReceiveList] reloadData begin contentOffsetY=\(Double(self.tableView.contentOffset.y)) contentHeight=\(Double(self.tableView.contentSize.height))")
        tableView.reloadData()
        receiveListLog("[ReceiveList] reloadData end contentOffsetY=\(Double(self.tableView.contentOffset.y)) contentHeight=\(Double(self.tableView.contentSize.height))")
        logTableGeometry(reason: "reloadData")
        DispatchQueue.main.async { [weak self] in
            self?.logTableGeometry(reason: "postReloadAsync")
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.backgroundColor = PikoPalette.pageBackgroundUIColor
        tableView.separatorStyle = .none
        tableView.allowsSelection = false
        tableView.showsVerticalScrollIndicator = false
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 84
        tableView.contentInset = .zero
        tableView.scrollIndicatorInsets = .zero
        receiveListLog("[ReceiveList] viewDidLoad estimatedRowHeight=\(Double(self.tableView.estimatedRowHeight))")
        tableView.reloadData()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        logTableGeometry(reason: "viewDidLayoutSubviews")
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        receiveListLog("[ReceiveList] numberOfRows section=\(section) count=\(self.rows.count) rows=\(self.rows.diagnosticDescription)")
        return rows.count
    }

    override func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        guard rows.indices.contains(indexPath.row) else {
            receiveListLog("[ReceiveList] height row=\(indexPath.row) outOfRange rows=\(self.rows.count) fallback=80")
            return 80
        }
        let row = rows[indexPath.row]
        let height: CGFloat
        switch row {
        case .history, .active:
            height = 80
        case .spacer:
            height = NativeReceiveLayout.bottomSpacerHeight
        case .hero, .device, .empty:
            height = UITableView.automaticDimension
        }
        receiveListLog("[ReceiveList] height row=\(indexPath.row) item=\(row.diagnosticDescription) height=\(Double(height))")
        return height
    }

    override func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        guard rows.indices.contains(indexPath.row) else {
            receiveListLog("[ReceiveList] estimate row=\(indexPath.row) outOfRange rows=\(self.rows.count) fallback=80")
            return 80
        }
        let row = rows[indexPath.row]
        let estimate = row.expectedTableHeight ?? tableView.estimatedRowHeight
        receiveListLog("[ReceiveList] estimate row=\(indexPath.row) item=\(row.diagnosticDescription) estimate=\(Double(estimate))")
        return estimate
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        guard let model, rows.indices.contains(indexPath.row) else {
            receiveListLog("[ReceiveList] cell row=\(indexPath.row) missing modelOrRow rows=\(self.rows.count)")
            return UITableViewCell(style: .default, reuseIdentifier: nil)
        }
        let row = rows[indexPath.row]
        receiveListLog("[ReceiveList] cell row=\(indexPath.row) item=\(row.diagnosticDescription) expectedHeight=\(Double(row.expectedTableHeight ?? -1))")
        if case .spacer = row {
            return NativeReceiveSpacerCell(height: NativeReceiveLayout.bottomSpacerHeight)
        }
        guard let rootView = row.makeView(model: model) else {
            receiveListLog("[ReceiveList] cell row=\(indexPath.row) item=\(row.diagnosticDescription) missingHostedView fallback=spacer")
            return NativeReceiveSpacerCell(height: NativeReceiveLayout.bottomSpacerHeight)
        }
        let cell = NativeReceiveHostingCell(style: .default, reuseIdentifier: nil)
        cell.configure(
            rootView: rootView,
            parent: self,
            diagnosticDescription: row.diagnosticDescription,
            expectedHeight: row.expectedTableHeight
        )
        return cell
    }

    override func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        let description = rows.indices.contains(indexPath.row) ? rows[indexPath.row].diagnosticDescription : "outOfRange"
        let cellLayout = receiveListLayoutDescription(for: cell)
        receiveListLog("[ReceiveList] willDisplay row=\(indexPath.row) item=\(description) layout=\(cellLayout)")
    }

    override func tableView(_ tableView: UITableView, canEditRowAt indexPath: IndexPath) -> Bool {
        guard rows.indices.contains(indexPath.row) else {
            return false
        }
        if case .history = rows[indexPath.row] {
            return true
        }
        return false
    }

    override func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        guard rows.indices.contains(indexPath.row), case .history(let item) = rows[indexPath.row] else {
            return nil
        }
        receiveListLog("[ReceiveList] swipe row=\(indexPath.row) history=\(String(item.id.uuidString.prefix(8)))")
        let deleteAction = UIContextualAction(style: .destructive, title: "删除") { [weak self] _, _, swipeCompletion in
            guard let self else {
                swipeCompletion(false)
                return
            }
            self.presentDeleteConfirmation(for: item, swipeCompletion: swipeCompletion)
        }
        deleteAction.backgroundColor = UIColor.systemRed
        let configuration = UISwipeActionsConfiguration(actions: [deleteAction])
        configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }

    override func tableView(_ tableView: UITableView, didEndDisplaying cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        let description = rows.indices.contains(indexPath.row) ? rows[indexPath.row].diagnosticDescription : "outOfRange"
        let cellLayout = receiveListLayoutDescription(for: cell)
        receiveListLog("[ReceiveList] didEndDisplaying row=\(indexPath.row) item=\(description) layout=\(cellLayout)")
    }

    override func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let offsetY = scrollView.contentOffset.y
        guard lastScrollLogOffsetY.map({ abs(offsetY - $0) >= 96 }) ?? true else {
            return
        }
        lastScrollLogOffsetY = offsetY
        logTableGeometry(reason: "scroll")
    }

    private func presentDeleteConfirmation(
        for item: NativeReceiveHistoryItem,
        swipeCompletion: @escaping (Bool) -> Void
    ) {
        receiveListLog("[ReceiveList] presentDeleteConfirmation history=\(String(item.id.uuidString.prefix(8))) files=\(item.fileCount)")
        let alert = UIAlertController(title: item.deleteConfirmationTitle, message: item.deleteConfirmationBody, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "算了", style: .cancel) { _ in
            receiveListLog("[ReceiveList] delete cancelled history=\(String(item.id.uuidString.prefix(8)))")
            swipeCompletion(false)
        })
        alert.addAction(UIAlertAction(title: "仅删除记录", style: .destructive) { [weak self] _ in
            guard let self else {
                swipeCompletion(false)
                return
            }
            self.delete(item, deleteFiles: false, swipeCompletion: swipeCompletion)
        })
        alert.addAction(UIAlertAction(title: "删除记录与文件", style: .destructive) { [weak self] _ in
            guard let self else {
                swipeCompletion(false)
                return
            }
            self.delete(item, deleteFiles: true, swipeCompletion: swipeCompletion)
        })
        present(alert, animated: true)
    }

    private func delete(
        _ item: NativeReceiveHistoryItem,
        deleteFiles: Bool,
        swipeCompletion: @escaping (Bool) -> Void
    ) {
        guard let model else {
            receiveListLog("[ReceiveList] delete aborted missingModel history=\(String(item.id.uuidString.prefix(8)))")
            swipeCompletion(false)
            return
        }
        receiveListLog("[ReceiveList] delete begin history=\(String(item.id.uuidString.prefix(8))) deleteFiles=\(deleteFiles ? 1 : 0) rows=\(self.rows.diagnosticDescription)")
        guard let indexPath = indexPath(for: item) else {
            receiveListLog("[ReceiveList] delete fallback missingIndex history=\(String(item.id.uuidString.prefix(8)))")
            model.deleteReceiveHistory(item, deleteFiles: deleteFiles) { [weak self] failedCount in
                self?.onDeleteFailure?(failedCount)
            }
            tableView.reloadData()
            swipeCompletion(true)
            return
        }
        isApplyingAnimatedDelete = true
        model.deleteReceiveHistory(item, deleteFiles: deleteFiles) { [weak self] failedCount in
            self?.onDeleteFailure?(failedCount)
        }
        animateDelete(item, at: indexPath)
        swipeCompletion(true)
    }

    private func indexPath(for item: NativeReceiveHistoryItem) -> IndexPath? {
        guard let row = rows.firstIndex(where: { row in
            if case .history(let history) = row {
                return history.id == item.id
            }
            return false
        }) else {
            return nil
        }
        return IndexPath(row: row, section: 0)
    }

    private func animateDelete(_ item: NativeReceiveHistoryItem, at indexPath: IndexPath) {
        receiveListLog("[ReceiveList] animateDelete begin row=\(indexPath.row) history=\(String(item.id.uuidString.prefix(8))) rowsBefore=\(self.rows.diagnosticDescription)")
        if let model {
            rows = NativeReceiveRow.rows(for: model)
        } else {
            rows.removeAll { row in
                if case .history(let history) = row {
                    return history.id == item.id
                }
                return false
            }
        }
        receiveListLog("[ReceiveList] animateDelete rowsAfterModelUpdate=\(self.rows.diagnosticDescription)")
        tableView.deleteRows(at: [indexPath], with: .automatic)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.28) { [weak self] in
            guard let self else {
                return
            }
            self.isApplyingAnimatedDelete = false
            if let model = self.model {
                self.rows = NativeReceiveRow.rows(for: model)
            }
            receiveListLog("[ReceiveList] animateDelete finish rows=\(self.rows.diagnosticDescription)")
            self.tableView.reloadData()
        }
    }

    private func logTableGeometry(reason: String) {
        receiveListLog("[ReceiveList] tableGeometry reason=\(reason) frame=\(receiveListFrameDescription(self.tableView.frame)) bounds=\(receiveListFrameDescription(self.tableView.bounds)) safeArea=\(receiveListInsetsDescription(self.tableView.safeAreaInsets)) contentInset=\(receiveListInsetsDescription(self.tableView.contentInset)) adjustedInset=\(receiveListInsetsDescription(self.tableView.adjustedContentInset)) indicatorInset=\(receiveListInsetsDescription(self.tableView.scrollIndicatorInsets)) contentSize=\(receiveListSizeDescription(self.tableView.contentSize)) offsetY=\(Double(self.tableView.contentOffset.y)) visibleRows=\(self.visibleRowsDiagnosticDescription) visibleCells=\(self.visibleCellsDiagnosticDescription)")
    }

    private func receiveListLayoutDescription(for cell: UITableViewCell) -> String {
        if let hostingCell = cell as? NativeReceiveHostingCell {
            return hostingCell.layoutDiagnosticDescription
        }
        if let spacerCell = cell as? NativeReceiveSpacerCell {
            return spacerCell.layoutDiagnosticDescription
        }
        return "nonHostingCell"
    }

    private var visibleRowsDiagnosticDescription: String {
        (tableView.indexPathsForVisibleRows ?? []).map { indexPath in
            let item = rows.indices.contains(indexPath.row) ? rows[indexPath.row].diagnosticDescription : "outOfRange"
            return "row:\(indexPath.row),item:\(item)"
        }.joined(separator: ";")
    }

    private var visibleCellsDiagnosticDescription: String {
        let indexedCells: [(indexPath: IndexPath, cell: UITableViewCell)] = tableView.visibleCells.compactMap { cell in
            guard let indexPath = tableView.indexPath(for: cell) else {
                return nil
            }
            return (indexPath, cell)
        }.sorted { lhs, rhs in
            if lhs.indexPath.section != rhs.indexPath.section {
                return lhs.indexPath.section < rhs.indexPath.section
            }
            return lhs.indexPath.row < rhs.indexPath.row
        }
        var previousMaxY: CGFloat?
        return indexedCells.map { entry in
            let indexPath = entry.indexPath
            let cell = entry.cell
            let item = rows.indices.contains(indexPath.row) ? rows[indexPath.row].diagnosticDescription : "outOfRange"
            let gap = previousMaxY.map { cell.frame.minY - $0 } ?? 0
            previousMaxY = cell.frame.maxY
            return "row:\(indexPath.row),item:\(item),gap:\(Int(gap.rounded())),layout:\(receiveListLayoutDescription(for: cell))"
        }.joined(separator: ";")
    }
}

private extension Array where Element == NativeReceiveRow {
    var diagnosticDescription: String {
        map(\.diagnosticDescription).joined(separator: "|")
    }
}

private final class NativeReceiveHostingCell: UITableViewCell {
    private var host: UIHostingController<AnyView>?
    private var diagnosticDescription = "unset"
    private var expectedHeight: CGFloat?
    private var lastLayoutDiagnosticDescription = ""

    func configure(
        rootView: AnyView,
        parent: UIViewController,
        diagnosticDescription: String,
        expectedHeight: CGFloat?
    ) {
        detachHost()
        self.diagnosticDescription = diagnosticDescription
        self.expectedHeight = expectedHeight
        selectionStyle = .none
        backgroundColor = PikoPalette.pageBackgroundUIColor
        contentView.backgroundColor = PikoPalette.pageBackgroundUIColor
        preservesSuperviewLayoutMargins = false
        contentView.preservesSuperviewLayoutMargins = false
        contentView.layoutMargins = .zero

        let controller = UIHostingController(rootView: rootView)
        controller.view.backgroundColor = PikoPalette.pageBackgroundUIColor
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        parent.addChild(controller)
        contentView.addSubview(controller.view)
        NSLayoutConstraint.activate([
            controller.view.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            controller.view.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            controller.view.topAnchor.constraint(equalTo: contentView.topAnchor),
            controller.view.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
        controller.didMove(toParent: parent)
        host = controller
        receiveListLog("[ReceiveList] hostingCell configure item=\(diagnosticDescription) expected=\(Int((expectedHeight ?? -1).rounded()))")
    }

    func detachHost() {
        guard let host else {
            return
        }
        host.willMove(toParent: nil)
        host.view.removeFromSuperview()
        host.removeFromParent()
        self.host = nil
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        detachHost()
        diagnosticDescription = "unset"
        expectedHeight = nil
        lastLayoutDiagnosticDescription = ""
    }

    deinit {
        detachHost()
    }

    var layoutDiagnosticDescription: String {
        let hostFrame = host.map { receiveListFrameDescription($0.view.frame) } ?? "none"
        return "cell:\(receiveListFrameDescription(frame)),content:\(receiveListFrameDescription(contentView.frame)),host:\(hostFrame),expected:\(Int((expectedHeight ?? -1).rounded()))"
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let description = layoutDiagnosticDescription
        guard description != lastLayoutDiagnosticDescription else {
            return
        }
        lastLayoutDiagnosticDescription = description
        receiveListLog("[ReceiveList] hostingCell layout item=\(diagnosticDescription) layout=\(description)")
    }
}

private final class NativeReceiveSpacerCell: UITableViewCell {
    private let expectedHeight: CGFloat
    private var lastLayoutDiagnosticDescription = ""

    init(height: CGFloat) {
        expectedHeight = height
        super.init(style: .default, reuseIdentifier: nil)
        selectionStyle = .none
        backgroundColor = PikoPalette.pageBackgroundUIColor
        contentView.backgroundColor = PikoPalette.pageBackgroundUIColor
        preservesSuperviewLayoutMargins = false
        contentView.preservesSuperviewLayoutMargins = false
        contentView.layoutMargins = .zero
    }

    required init?(coder: NSCoder) {
        return nil
    }

    var layoutDiagnosticDescription: String {
        "cell:\(receiveListFrameDescription(frame)),content:\(receiveListFrameDescription(contentView.frame)),expected:\(Int(expectedHeight.rounded()))"
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let description = layoutDiagnosticDescription
        guard description != lastLayoutDiagnosticDescription else {
            return
        }
        lastLayoutDiagnosticDescription = description
        receiveListLog("[ReceiveList] spacerCell layout expected=\(Int(expectedHeight.rounded())) layout=\(description)")
    }
}

private extension NativeReceiveHistoryItem {
    var receiveListDiagnosticDescription: String {
        let previewBytes = mediaPreviewData?.count ?? 0
        let firstFile = files.first?.displayName.receiveListLogPreview ?? "none"
        return "history(id:\(String(id.uuidString.prefix(8))),files:\(fileCount),type:\(primaryFileType.rawValue),titleLen:\(title.count),title:\(title.receiveListLogPreview),subtitle:\(subtitle.receiveListLogPreview),previewBytes:\(previewBytes),firstFile:\(firstFile))"
    }

    var receiveListCompactDiagnosticDescription: String {
        let previewBytes = mediaPreviewData?.count ?? 0
        return "id:\(String(id.uuidString.prefix(8))),titleLen:\(title.count),subtitle:\(subtitle.receiveListLogPreview),previewBytes:\(previewBytes)"
    }
}

private extension String {
    var receiveListLogPreview: String {
        String(prefix(32))
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "|", with: "/")
    }
}

private func receiveListFrameDescription(_ rect: CGRect) -> String {
    "x:\(Int(rect.origin.x.rounded())),y:\(Int(rect.origin.y.rounded())),w:\(Int(rect.width.rounded())),h:\(Int(rect.height.rounded()))"
}

private func receiveListSizeDescription(_ size: CGSize) -> String {
    "w:\(Int(size.width.rounded())),h:\(Int(size.height.rounded()))"
}

private func receiveListInsetsDescription(_ insets: UIEdgeInsets) -> String {
    "t:\(Int(insets.top.rounded())),l:\(Int(insets.left.rounded())),b:\(Int(insets.bottom.rounded())),r:\(Int(insets.right.rounded()))"
}

private func receiveListImageDescription(_ image: UIImage) -> String {
    "\(Int((image.size.width * image.scale).rounded()))x\(Int((image.size.height * image.scale).rounded()))"
}

private struct NativeReceiveLayoutProbe: View {
    let name: String
    @State private var lastSizeDescription = ""

    var body: some View {
        GeometryReader { proxy in
            Color.clear
                .onAppear {
                    log(size: proxy.size, globalFrame: proxy.frame(in: .global), event: "appear")
                }
                .onChange(of: proxy.size) { size in
                    log(size: size, globalFrame: proxy.frame(in: .global), event: "change")
                }
        }
    }

    private func log(size: CGSize, globalFrame: CGRect, event: String) {
        let description = "size=\(receiveListSizeDescription(size)),global:\(receiveListFrameDescription(globalFrame))"
        guard description != lastSizeDescription else {
            return
        }
        lastSizeDescription = description
        receiveListLog("[ReceiveList] swiftUILayout event=\(event) item=\(name) \(description)")
    }
}

private extension View {
    func receiveListLayoutProbe(_ name: String) -> some View {
        background(NativeReceiveLayoutProbe(name: name))
    }
}

private func rowView<Content: View>(
    top: CGFloat = 0,
    leading: CGFloat = NativeReceiveLayout.pageHorizontalInset,
    trailing: CGFloat = NativeReceiveLayout.contentTrailingInset,
    bottom: CGFloat = 0,
    @ViewBuilder content: () -> Content
) -> some View {
    content()
        .padding(EdgeInsets(top: top, leading: leading, bottom: bottom, trailing: trailing))
        .frame(maxWidth: .infinity)
        .background(PikoPalette.pageBackground)
}

private func emptyStateCardView<Content: View>(
    top: CGFloat = 0,
    leading: CGFloat = NativeReceiveLayout.pageHorizontalInset,
    trailing: CGFloat = NativeReceiveLayout.contentTrailingInset,
    bottom: CGFloat = 0,
    @ViewBuilder content: () -> Content
) -> some View {
    content()
        .padding(.horizontal, 22)
        .frame(maxWidth: .infinity)
        .frame(minHeight: NativeReceiveLayout.emptyStateMinimumContentHeight)
        .padding(EdgeInsets(top: top, leading: leading, bottom: bottom, trailing: trailing))
        .frame(maxWidth: .infinity)
        .background(PikoPalette.pageBackground)
}

private enum NativeReceiveLayout {
    static let pageHorizontalInset: CGFloat = 24
    static let fileRowTrailingInset: CGFloat = 24
    static let contentTrailingInset: CGFloat = 24
    static let bottomSpacerHeight: CGFloat = 112
    static let deviceNicknameBottomSpacing: CGFloat = 8
    static let deviceNicknameVerticalPadding: CGFloat = 9
    static let emptyStateTopSpacing: CGFloat = 24
    static let emptyStateBottomSpacing: CGFloat = 112
    static let emptyStateMinimumContentHeight: CGFloat = 164
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

private struct NativeActiveReceiveRow: View {
    let transfer: NativeReceiveTransferState
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            NativeActiveReceiveProgressIcon(transfer: transfer)
            NativeReceiveTextColumn {
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
            Button(action: onCancel) {
                Image(uiImage: LucideTabIcon.x.image)
                    .resizable()
                    .frame(width: 20, height: 20)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.red)
            .frame(width: 44, height: 44)
            .fixedSize(horizontal: true, vertical: false)
            .offset(x: -8)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
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

private struct NativeReceiveHistoryRow: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        let diagnostic = item.receiveListCompactDiagnosticDescription
        HStack(spacing: 12) {
            NativeReceiveHistoryPreview(item: item)
                .receiveListLayoutProbe("historyPreviewFrame(\(diagnostic))")
            NativeReceiveTextColumn {
                Text(item.title)
                    .font(PikoFont.rowTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                    .receiveListLayoutProbe("historyTitle(\(diagnostic),title:\(item.title.receiveListLogPreview))")
                Text(item.subtitle)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .receiveListLayoutProbe("historySubtitle(\(diagnostic))")
            }
            .receiveListLayoutProbe("historyTextColumn(\(diagnostic))")
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .receiveListLayoutProbe("historyHStack(\(diagnostic))")
    }
}

private struct NativeReceiveTextColumn<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .layoutPriority(1)
    }
}

private struct NativeReceiveHistoryPreview: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        let diagnostic = item.receiveListCompactDiagnosticDescription
        if item.fileCount > 1 {
            NativeMultiFilePreview(fileType: item.primaryFileType, count: item.fileCount)
                .receiveListLayoutProbe("historyPreviewBranch(kind:multi,\(diagnostic))")
        } else if let mediaPreviewData = item.mediaPreviewData {
            NativeMediaPreview(data: mediaPreviewData, diagnostic: diagnostic)
                .receiveListLayoutProbe("historyPreviewBranch(kind:media,\(diagnostic))")
        } else {
            NativeFileTypePreview(fileType: item.primaryFileType)
                .receiveListLayoutProbe("historyPreviewBranch(kind:fileType,\(diagnostic))")
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
    let diagnostic: String

    var body: some View {
        if let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 60, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .receiveListLayoutProbe("mediaPreview(bytes:\(data.count),pixels:\(receiveListImageDescription(image)),\(diagnostic))")
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
                .receiveListLayoutProbe("mediaPreviewInvalid(bytes:\(data.count),\(diagnostic))")
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

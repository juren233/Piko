import SwiftUI
import UIKit

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel
    @State private var deleteFailureMessage: String?
    @State private var pendingDeleteHistory: NativeReceiveHistoryItem?

    var body: some View {
        List {
            rowView(top: 28, bottom: 8) {
                PikoHeroPanel(
                    title: "Piko",
                    subtitle: "接收记录和本机收件箱",
                    metric: "\(model.receiveHistory.count) 次"
                )
            }
            .nativeReceiveListRow()

            rowView(bottom: NativeReceiveLayout.deviceNicknameBottomSpacing) {
                NativeDeviceNicknameBanner(
                    nickname: model.currentDeviceName,
                    onReset: model.resetDeviceNickname
                )
            }
            .nativeReceiveListRow()

            if model.receiveHistory.isEmpty && model.activeReceive == nil {
                emptyStateCardView(
                    top: NativeReceiveLayout.emptyStateTopSpacing,
                    bottom: NativeReceiveLayout.emptyStateBottomSpacing
                ) {
                    NativeReceiveEmptyStateContent()
                }
                .nativeReceiveListRow()
            } else {
                if let activeReceive = model.activeReceive {
                    NativeActiveReceiveRow(
                        transfer: activeReceive,
                        onCancel: model.cancelReceiveTransfer
                    )
                    .nativeReceiveFileListRow()
                }
                ForEach(model.receiveHistory, id: \.id) { item in
                    NativeReceiveHistoryRow(item: item)
                    .nativeReceiveFileListRow()
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button("删除", role: .destructive) {
                            pendingDeleteHistory = item
                        }
                    }
                }
                Color.clear
                    .frame(height: NativeReceiveLayout.bottomSpacerHeight)
                    .nativeReceiveListRow()
            }
        }
        .listStyle(.plain)
        .ignoresSafeArea(.container, edges: [.top, .bottom])
        .background(PikoPalette.pageBackground.ignoresSafeArea())
        .systemBarBackgrounds()
        .alert(pendingDeleteHistory?.deleteConfirmationTitle ?? "", isPresented: Binding(
            get: { pendingDeleteHistory != nil },
            set: { if !$0 { pendingDeleteHistory = nil } }
        )) {
            Button("算了", role: .cancel) {
                pendingDeleteHistory = nil
            }
            Button("仅删除记录", role: .destructive) {
                if let item = pendingDeleteHistory {
                    confirmDelete(item, deleteFiles: false)
                }
            }
            Button("删除记录与文件", role: .destructive) {
                if let item = pendingDeleteHistory {
                    confirmDelete(item, deleteFiles: true)
                }
            }
        } message: {
            if let item = pendingDeleteHistory {
                Text(item.deleteConfirmationBody)
            }
        }
        .alert(deleteFailureMessage ?? "", isPresented: Binding(
            get: { deleteFailureMessage != nil },
            set: { if !$0 { deleteFailureMessage = nil } }
        )) {
            Button("好", role: .cancel) { deleteFailureMessage = nil }
        }
    }

    private func confirmDelete(_ item: NativeReceiveHistoryItem, deleteFiles: Bool) {
        pendingDeleteHistory = nil
        model.deleteReceiveHistory(item, deleteFiles: deleteFiles) { failedCount in
            if failedCount > 0 {
                deleteFailureMessage = "有\(failedCount)个文件未删除"
            }
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
}

private extension View {
    func nativeReceiveListRow() -> some View {
        listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(PikoPalette.pageBackground)
    }

    func nativeReceiveFileListRow() -> some View {
        listRowInsets(EdgeInsets(
            top: 0,
            leading: NativeReceiveLayout.pageHorizontalInset,
            bottom: 0,
            trailing: 16
        ))
        .listRowSeparator(.visible)
        .listRowBackground(PikoPalette.pageBackground)
    }
}

private enum NativeReceiveLayout {
    static let pageHorizontalInset: CGFloat = 24
    static let contentTrailingInset: CGFloat = 0
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
                ProgressView(value: transfer.progress)
                    .progressViewStyle(.linear)
                    .tint(PikoPalette.accent)
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
        HStack(spacing: 12) {
            NativeReceiveHistoryPreview(item: item)
            NativeReceiveTextColumn {
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
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
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

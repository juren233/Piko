import SwiftUI

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel
    @State private var pendingDeleteItem: NativeReceiveHistoryItem?
    @State private var deleteReceivedFiles = false
    @State private var deleteFailureMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                PikoHeroPanel(
                    title: "Piko",
                    subtitle: "接收记录和本机收件箱",
                    metric: "\(model.receiveHistory.count) 次"
                )
                NativeDeviceNicknameBanner(
                    nickname: model.currentDeviceName,
                    onReset: model.resetDeviceNickname
                )
                if model.receiveHistory.isEmpty && model.activeReceive == nil {
                    NativeReceiveEmptyState()
                } else {
                    NativeReceiveHistoryList(
                        activeReceive: model.activeReceive,
                        items: model.receiveHistory,
                        onCancelReceive: model.cancelReceiveTransfer,
                        onDeleteHistory: { item in
                            deleteReceivedFiles = false
                            pendingDeleteItem = item
                        }
                    )
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 136)
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
        .overlay {
            if let item = pendingDeleteItem {
                NativeDeleteReceiveHistoryDialog(
                    item: item,
                    deleteReceivedFiles: deleteReceivedFiles,
                    onDeleteReceivedFilesChange: { deleteReceivedFiles = $0 },
                    onCancel: { pendingDeleteItem = nil },
                    onConfirm: {
                        pendingDeleteItem = nil
                        model.deleteReceiveHistory(item, deleteFiles: deleteReceivedFiles) { failedCount in
                            if failedCount > 0 {
                                deleteFailureMessage = "有\(failedCount)个文件未删除"
                            }
                        }
                    }
                )
            }
        }
        .alert(deleteFailureMessage ?? "", isPresented: Binding(
            get: { deleteFailureMessage != nil },
            set: { if !$0 { deleteFailureMessage = nil } }
        )) {
            Button("好", role: .cancel) { deleteFailureMessage = nil }
        }
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

private struct NativeReceiveHistoryList: View {
    let activeReceive: NativeReceiveTransferState?
    let items: [NativeReceiveHistoryItem]
    let onCancelReceive: () -> Void
    let onDeleteHistory: (NativeReceiveHistoryItem) -> Void

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 12) {
                if let activeReceive {
                    NativeActiveReceiveCard(
                        transfer: activeReceive,
                        onCancel: onCancelReceive
                    )
                }
                ForEach(items) { item in
                    NativeReceiveHistoryCard(item: item)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                onDeleteHistory(item)
                            } label: {
                                Text("删除")
                            }
                        }
                }
            }
        }
    }
}

private struct NativeDeleteReceiveHistoryDialog: View {
    let item: NativeReceiveHistoryItem
    let deleteReceivedFiles: Bool
    let onDeleteReceivedFilesChange: (Bool) -> Void
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.26)
                .ignoresSafeArea()
                .onTapGesture(perform: onCancel)
            VStack(alignment: .leading, spacing: 16) {
                Text(item.deleteConfirmationTitle)
                    .font(PikoFont.compactTitle)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(item.deleteConfirmationBody)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .center) {
                    Button {
                        onDeleteReceivedFilesChange(!deleteReceivedFiles)
                    } label: {
                        HStack(spacing: 8) {
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .stroke(deleteReceivedFiles ? PikoPalette.accent : Color.secondary.opacity(0.48), lineWidth: 1.5)
                                .frame(width: 20, height: 20)
                                .overlay {
                                    if deleteReceivedFiles {
                                        Image(uiImage: LucideTabIcon.check.image)
                                            .resizable()
                                            .frame(width: 14, height: 14)
                                            .foregroundStyle(PikoPalette.accent)
                                    }
                                }
                            Text("同时删除文件")
                                .font(PikoFont.rowSubtitle)
                                .foregroundStyle(.primary)
                        }
                    }
                    .buttonStyle(.plain)
                    Spacer(minLength: 12)
                    HStack(spacing: 12) {
                        Button("算了", action: onCancel)
                            .font(PikoFont.rowSubtitle)
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                        Button("删除", action: onConfirm)
                            .font(PikoFont.rowSubtitle)
                            .buttonStyle(.plain)
                            .foregroundStyle(.red)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: 340)
            .background(PikoPalette.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(Color.secondary.opacity(0.18), lineWidth: 1)
            )
            .padding(.horizontal, 24)
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

import Foundation
import SwiftUI
import UIKit

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel
    let onScrollProgressChange: (CGFloat) -> Void

    @State private var deleteTarget: NativeReceiveHistoryItem?
    @State private var deleteFailureMessage: String?

    var body: some View {
        List {
            Section {
                ReceiveStatusSummary(model: model)
                    .listRowInsets(.init(top: 12, leading: 20, bottom: 12, trailing: 20))
            }

            if let activeReceive = model.activeReceive {
                Section("当前接收") {
                    NativeActiveReceiveRow(
                        transfer: activeReceive,
                        onAccept: model.acceptReceiveTransfer,
                        onCancel: model.cancelReceiveTransfer
                    )
                }
            }

            Section("接收记录") {
                if model.receiveHistory.isEmpty {
                    NativeUnavailableRow(
                        title: "等待接收文件",
                        systemImage: "tray.and.arrow.down",
                        description: "Piko 正在监听直连传输，收到文件后会显示在这里。"
                    )
                } else {
                    ForEach(model.receiveHistory) { item in
                        NativeReceiveHistoryRow(item: item)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    deleteTarget = item
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.visible)
        .navigationTitle("Piko")
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            onScrollProgressChange(1)
        }
        .confirmationDialog(
            deleteTarget?.deleteConfirmationTitle ?? "删除接收记录",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let item = deleteTarget {
                Button("只删除记录", role: .destructive) {
                    delete(item, deleteFiles: false)
                }
                Button("删除记录和文件", role: .destructive) {
                    delete(item, deleteFiles: true)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(deleteTarget?.deleteConfirmationBody ?? "")
        }
        .alert(
            "删除失败",
            isPresented: Binding(
                get: { deleteFailureMessage != nil },
                set: { if !$0 { deleteFailureMessage = nil } }
            )
        ) {
            Button("好", role: .cancel) { deleteFailureMessage = nil }
        } message: {
            Text(deleteFailureMessage ?? "")
        }
    }

    private func delete(_ item: NativeReceiveHistoryItem, deleteFiles: Bool) {
        model.deleteReceiveHistory(item, deleteFiles: deleteFiles) { failedCount in
            DispatchQueue.main.async {
                guard failedCount > 0 else { return }
                deleteFailureMessage = "有\(failedCount)个文件未删除"
            }
        }
    }
}

private struct NativeUnavailableRow: View {
    let title: String
    let systemImage: String
    let description: String

    var body: some View {
        if #available(iOS 17.0, *) {
            ContentUnavailableView(
                title,
                systemImage: systemImage,
                description: Text(description)
            )
        } else {
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 8)
        }
    }
}

private struct ReceiveStatusSummary: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "iphone")
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 44, height: 44)
                    .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.currentDeviceName)
                        .font(.headline)
                    Text("本设备名称")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button("更换", action: model.resetDeviceNickname)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }

            LabeledContent("接收状态") {
                Text(model.activeReceive == nil ? "可接收" : "进行中")
                    .foregroundStyle(model.activeReceive == nil ? Color.green : Color.accentColor)
            }

            LabeledContent("历史记录") {
                Text("\(model.receiveHistory.count) 次")
            }
        }
        .accessibilityElement(children: .contain)
    }
}

private struct NativeActiveReceiveRow: View {
    let transfer: NativeReceiveTransferState
    let onAccept: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: transfer.primaryFileType == .image ? "photo" : "doc")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 44, height: 44)
                    .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    Text(transfer.title)
                        .font(.headline)
                    Text(transfer.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            ProgressView(value: transfer.progress)

            HStack {
                if transfer.requiresConfirmation {
                    Button("接收", systemImage: "checkmark.circle", action: onAccept)
                        .buttonStyle(.borderedProminent)
                }

                Button(transfer.requiresConfirmation ? "拒绝" : "取消", systemImage: "xmark.circle", role: .destructive, action: onCancel)
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.vertical, 4)
    }
}

private struct NativeReceiveHistoryRow: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.body)
                    .lineLimit(2)
                Text(item.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            NativeReceiveHistoryPreview(item: item)
        }
        .labelStyle(.titleAndIcon)
        .padding(.vertical, 4)
    }
}

private struct NativeReceiveHistoryPreview: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        Group {
            if let data = item.mediaPreviewData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundStyle(.tint)
            }
        }
        .frame(width: 44, height: 44)
        .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityHidden(true)
    }

    private var systemImage: String {
        switch item.primaryFileType {
        case .document:
            return "doc.text"
        case .spreadsheet:
            return "tablecells"
        case .image:
            return "photo"
        case .video:
            return "film"
        case .archive:
            return "archivebox"
        case .other:
            return "doc"
        }
    }
}

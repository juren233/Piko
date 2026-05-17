import SwiftUI
import UIKit

struct NativeSendView: View {
    @ObservedObject var model: NativePikoModel
    let onScrollProgressChange: (CGFloat) -> Void
    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false

    var body: some View {
        List {
            if model.transferIsVisible {
                Section("传输进度") {
                    NativeSendTransferRow(model: model)
                }
            }

            NativeSendDeviceSection(
                title: "我的设备",
                devices: model.myDevices,
                selectedDeviceIds: model.selectedDeviceIds,
                onToggle: model.toggleDevice,
                emptyText: "暂无设备"
            )

            NativeSendDeviceSection(
                title: "局域网设备",
                devices: model.lanDevices,
                selectedDeviceIds: model.selectedDeviceIds,
                onToggle: model.toggleDevice,
                emptyText: model.discoveryLabel
            )

            NativeSendDeviceSection(
                title: "我的好友",
                devices: model.friendDevices,
                selectedDeviceIds: model.selectedDeviceIds,
                onToggle: model.toggleDevice,
                emptyText: "暂无好友设备"
            )

            Section("图片/视频") {
                Button("选择图片/视频", systemImage: "photo.on.rectangle") {
                    showingPhotoPicker = true
                }
                if model.mediaItems.isEmpty {
                    NativeUnavailableRow(
                        title: "还没有选择图片/视频",
                        systemImage: "photo",
                        description: "从系统选择器添加需要传输的图片或视频。"
                    )
                } else {
                    ForEach(model.mediaItems) { item in
                        NativeSendMediaPreviewRow(
                            item: item,
                            onRemove: { model.removeItem(item.id) }
                        )
                    }
                }
            }

            Section("文件") {
                Button("添加文件", systemImage: "doc.badge.plus") {
                    showingDocumentPicker = true
                }
                if model.fileItems.isEmpty {
                    NativeUnavailableRow(
                        title: "还没有选择文件",
                        systemImage: "doc",
                        description: "从系统文件选择器添加需要传输的文件。"
                    )
                } else {
                    ForEach(model.fileItems) { item in
                        NativeSendItemRow(
                            item: item,
                            selected: model.selectedItemIds.contains(item.id),
                            onToggle: { model.toggleItem(item.id) },
                            onRemove: { model.removeItem(item.id) }
                        )
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("发送")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("发送", systemImage: "paperplane", action: model.sendSelectedItems)
                    .disabled(!model.canSend)
            }
        }
        .onAppear {
            onScrollProgressChange(1)
            model.refreshFriendsPresence()
        }
        .sheet(isPresented: $showingPhotoPicker) {
            NativeMediaPicker { items in
                model.addItems(items)
            }
        }
        .sheet(isPresented: $showingDocumentPicker) {
            NativeDocumentPicker { items in
                model.addItems(items)
            }
        }
        .alert("P2P 传输失败", isPresented: Binding(
            get: { model.transferFailureMessage != nil },
            set: { if !$0 { model.transferFailureMessage = nil } }
        )) {
            Button("复制") {
                UIPasteboard.general.string = model.transferFailureMessage ?? ""
            }
            Button("好", role: .cancel) {
                model.transferFailureMessage = nil
            }
        } message: {
            Text(model.transferFailureMessage ?? "")
        }
        .alert("传输状态", isPresented: Binding(
            get: { model.transferToastMessage != nil },
            set: { if !$0 { model.transferToastMessage = nil } }
        )) {
            Button("好", role: .cancel) {
                model.transferToastMessage = nil
            }
        } message: {
            Text(model.transferToastMessage ?? "")
        }
    }
}

private struct NativeSendDeviceSection: View {
    let title: String
    let devices: [NativeSendDevice]
    let selectedDeviceIds: Set<String>
    let onToggle: (String) -> Void
    let emptyText: String

    var body: some View {
        Section(title) {
            if devices.isEmpty {
                NativeUnavailableRow(
                    title: emptyText,
                    systemImage: "iphone.gen2",
                    description: "发现设备后会显示在这里。"
                )
            } else {
                ForEach(devices) { device in
                    Button {
                        onToggle(device.id)
                    } label: {
                        HStack(spacing: 12) {
                            Label {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(device.name)
                                        .font(.body)
                                        .lineLimit(1)
                                    if let subtitle = device.subtitle {
                                        Text(subtitle)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                }
                            } icon: {
                                Image(systemName: device.transportPath == .p2p ? "person.crop.circle" : "iphone.gen2")
                                    .foregroundStyle(device.isConnectable ? Color.accentColor : Color.secondary)
                            }
                            Spacer()
                            if selectedDeviceIds.contains(device.id) {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!device.isConnectable)
                    .opacity(device.isConnectable ? 1 : 0.55)
                }
            }
        }
    }
}

private struct NativeSendTransferRow: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.transferTitle)
                        .font(.headline)
                    Text(model.transferSubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: model.transferPrimaryFileType == .image ? "photo" : "doc")
                    .foregroundStyle(.tint)
            }

            if let progress = model.transferProgress {
                ProgressView(value: progress)
            }

            HStack {
                Button("暂停", systemImage: "pause.circle", action: model.pauseTransfer)
                    .buttonStyle(.bordered)
                Button("取消", systemImage: "xmark.circle", role: .destructive, action: model.cancelTransfer)
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.vertical, 4)
    }
}

private struct NativeSendItemRow: View {
    let item: NativeTransferItem
    let selected: Bool
    let onToggle: () -> Void
    let onRemove: (() -> Void)?

    var body: some View {
        HStack {
            Button(action: onToggle) {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.displayName)
                            .font(.body)
                            .lineLimit(1)
                        Text(item.sizeLabel)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: item.systemImage)
                        .foregroundStyle(.tint)
                }
            }
            .buttonStyle(.plain)

            Spacer()

            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                .onTapGesture(perform: onToggle)

            if let onRemove {
                Button(role: .destructive, action: onRemove) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct NativeSendMediaPreviewRow: View {
    let item: NativeTransferItem
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            NativeSendMediaPreviewThumbnail(item: item)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.displayName)
                    .font(.body)
                    .lineLimit(2)
                Text("\(item.fileType.previewText) · \(item.sizeLabel)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button(role: .destructive, action: onRemove) {
                Image(systemName: "minus.circle.fill")
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
}

private struct NativeSendMediaPreviewThumbnail: View {
    let item: NativeTransferItem

    var body: some View {
        Group {
            if let data = item.previewData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: item.systemImage)
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.tint.opacity(0.12))
            }
        }
        .frame(width: 58, height: 58)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityHidden(true)
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

private extension NativeFileType {
    var previewText: String {
        switch self {
        case .video:
            return "视频"
        case .image:
            return "图片"
        default:
            return previewLabel
        }
    }
}

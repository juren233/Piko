import SwiftUI

struct NativeImageSection: View {
    @ObservedObject var model: NativePikoModel
    @Binding var showingPhotoPicker: Bool

    var body: some View {
        PikoSectionPanel(
            title: "图片",
            trailing: {
                PikoPill(text: model.imageSectionExpanded ? "收起" : "展开", emphasized: true)
            }
        ) {
            if model.imageItems.isEmpty {
                PikoEmptyPlane(text: "还没有读取到最近图片") {
                    Button("选择图片") {
                        showingPhotoPicker = true
                    }
                    .buttonStyle(.borderless)
                }
            } else if model.imageSectionExpanded {
                VStack(spacing: 10) {
                    ForEach(Array(model.imageItems.chunked(into: 3).enumerated()), id: \.offset) { _, rowItems in
                        HStack(spacing: 10) {
                            ForEach(rowItems) { item in
                                NativeImageTile(
                                    item: item,
                                    selected: model.selectedItemIds.contains(item.id),
                                    onTap: { model.toggleItem(item.id) }
                                )
                            }
                            ForEach(0..<(3 - rowItems.count), id: \.self) { _ in
                                Color.clear.aspectRatio(1, contentMode: .fit)
                            }
                        }
                    }
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(model.imageItems) { item in
                            NativeImageTile(
                                item: item,
                                selected: model.selectedItemIds.contains(item.id),
                                onTap: { model.toggleItem(item.id) }
                            )
                            .frame(width: 92)
                        }
                    }
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            model.toggleImageSectionExpanded()
        }
    }
}

struct NativeFileSection: View {
    @ObservedObject var model: NativePikoModel
    @Binding var showingDocumentPicker: Bool

    var body: some View {
        PikoSectionPanel(
            title: "文件",
            trailing: {
                Button {
                    showingDocumentPicker = true
                } label: {
                    HStack(spacing: 4) {
                        Image(uiImage: LucideTabIcon.plus.image)
                            .resizable()
                            .frame(width: 16, height: 16)
                        Text("添加")
                            .font(PikoFont.button)
                            .lineLimit(1)
                            .minimumScaleFactor(0.88)
                            .truncationMode(.tail)
                    }
                }
                .buttonStyle(.borderless)
            }
        ) {
            if model.fileItems.isEmpty {
                PikoEmptyPlane(text: "点击选择需要传输的文件") {
                    Image(uiImage: LucideTabIcon.file.image)
                        .resizable()
                        .frame(width: 36, height: 36)
                        .foregroundStyle(.secondary)
                }
            } else {
                VStack(spacing: 8) {
                    ForEach(model.fileItems) { item in
                        NativeFileRow(
                            item: item,
                            onRemove: { model.removeItem(item.id) }
                        )
                    }
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if model.fileItems.isEmpty {
                showingDocumentPicker = true
            }
        }
    }
}

private struct NativeImageTile: View {
    let item: NativeTransferItem
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(PikoPalette.accent.opacity(0.12))
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        Image(uiImage: LucideTabIcon.image.image)
                            .resizable()
                            .frame(width: 28, height: 28)
                            .foregroundStyle(PikoPalette.accent)
                    }
                if selected {
                    Image(uiImage: LucideTabIcon.check.image)
                        .resizable()
                        .foregroundStyle(.white)
                        .frame(width: 10, height: 10)
                        .padding(5)
                        .background(PikoPalette.accent, in: Circle())
                        .padding(6)
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(selected ? PikoPalette.accent : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct NativeFileRow: View {
    let item: NativeTransferItem
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(PikoPalette.surfaceVariant.opacity(0.28))
                .frame(width: 44, height: 44)
                .overlay {
                    Text(item.fileType.previewLabel)
                        .font(PikoFont.previewLabel)
                }
            VStack(alignment: .leading, spacing: 3) {
                Text(item.displayName)
                    .font(PikoFont.fileRowTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                Text(item.sizeLabel)
                    .font(PikoFont.compactSubtitle)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Spacer()
            Button(action: onRemove) {
                Image(uiImage: LucideTabIcon.x.image)
                    .resizable()
                    .frame(width: 20, height: 20)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("移除")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(PikoPalette.surfaceVariant.opacity(0.24), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}

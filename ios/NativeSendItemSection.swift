import SwiftUI

struct NativeItemSection: View {
    @ObservedObject var model: NativePikoModel
    @Binding var showingPhotoPicker: Bool
    @Binding var showingDocumentPicker: Bool

    var body: some View {
        PikoSectionPanel(
            title: "待发送",
            trailing: {
                HStack(spacing: 8) {
                    Button("图片") {
                        showingPhotoPicker = true
                    }
                    Button("文件") {
                        showingDocumentPicker = true
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        ) {
            if model.items.isEmpty {
                PikoEmptyPlane(text: "请选择图片或文件") {
                    Image(systemName: "tray.and.arrow.up")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
            } else {
                VStack(spacing: 10) {
                    ForEach(model.items) { item in
                        NativeTransferItemRow(
                            item: item,
                            selected: model.selectedItemIds.contains(item.id),
                            onTap: { model.toggleItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

private struct NativeTransferItemRow: View {
    let item: NativeTransferItem
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: item.systemImage)
                    .frame(width: 30)
                    .foregroundStyle(PikoPalette.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.displayName)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    Text(item.sizeLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(PikoPalette.accent)
                }
            }
            .padding(14)
            .background(
                selected ? PikoPalette.accent.opacity(0.12) : Color.secondary.opacity(0.08),
                in: RoundedRectangle(cornerRadius: 22, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(selected ? PikoPalette.accent : Color.secondary.opacity(0.16), lineWidth: selected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

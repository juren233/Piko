import SwiftUI

struct NativeDeviceSection: View {
    let title: String
    let devices: [NativeSendDevice]
    let selectedDeviceIds: Set<String>
    let onDeviceClick: (String) -> Void
    var emptyText: String?

    var body: some View {
        PikoSectionPanel(
            title: title,
            trailing: {
                if let emptyText = emptyText {
                    PikoPill(text: emptyText)
                }
            }
        ) {
            if devices.isEmpty {
                PikoEmptyPlane(text: emptyText ?? "暂无设备") {
                    Text("· · ·")
                        .font(PikoFont.emptyDots)
                        .foregroundStyle(.secondary)
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(devices) { device in
                            NativeDeviceRow(
                                device: device,
                                selected: selectedDeviceIds.contains(device.id),
                                onTap: { onDeviceClick(device.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private struct NativeDeviceRow: View {
    let device: NativeSendDevice
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    Circle()
                        .fill(selected ? PikoPalette.accent.opacity(0.12) : PikoPalette.surface)
                        .frame(width: 56, height: 56)
                        .overlay(
                            Circle()
                                .stroke(selected ? PikoPalette.accent.opacity(0.38) : Color.secondary.opacity(0.24), lineWidth: 1)
                        )
                    Text(String(device.name.prefix(1)))
                        .font(PikoFont.deviceInitial)
                        .foregroundStyle(selected ? PikoPalette.accent : .secondary)
                    if selected {
                        Image(uiImage: LucideTabIcon.check.image)
                            .resizable()
                            .foregroundStyle(.white)
                            .frame(width: 10, height: 10)
                            .padding(4)
                            .background(PikoPalette.accent, in: Circle())
                            .offset(x: 2, y: -2)
                    }
                }
                .frame(width: 56, height: 56)
                Text(device.name)
                    .font(PikoFont.compactSubtitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
                if let subtitle = device.subtitle {
                    Text(subtitle)
                        .font(PikoFont.badge)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .frame(width: 88)
            .padding(.horizontal, 8)
            .padding(.vertical, 12)
            .background(
                selected ? PikoPalette.accent.opacity(0.08) : PikoPalette.surfaceVariant.opacity(0.24),
                in: RoundedRectangle(cornerRadius: 20, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(selected ? PikoPalette.accent.opacity(0.52) : Color.secondary.opacity(0.2), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

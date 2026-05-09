import SwiftUI

struct NativeDeviceSection: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        PikoSectionPanel(
            title: "局域网设备",
            trailing: { PikoPill(text: model.discoveryLabel) }
        ) {
            if model.lanDevices.isEmpty {
                PikoEmptyPlane(text: "暂无局域网设备") {
                    Text("· · ·")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(.secondary)
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(model.lanDevices) { device in
                            NativeDeviceRow(
                                device: device,
                                selected: model.selectedDeviceIds.contains(device.id),
                                onTap: { model.toggleDevice(device.id) }
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
            HStack(spacing: 12) {
                Text(String(device.name.prefix(1)))
                    .font(.headline.weight(.bold))
                    .frame(width: 44, height: 44)
                    .background(
                        selected ? PikoPalette.accent.opacity(0.18) : Color.secondary.opacity(0.14),
                        in: RoundedRectangle(cornerRadius: selected ? 16 : 22, style: .continuous)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(device.name)
                        .font(.body.weight(.semibold))
                    Text(device.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(PikoPalette.accent)
                }
            }
            .frame(width: 220)
            .padding(14)
            .background(
                selected ? PikoPalette.accent.opacity(0.12) : Color.secondary.opacity(0.08),
                in: RoundedRectangle(cornerRadius: 24, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(selected ? PikoPalette.accent : Color.secondary.opacity(0.16), lineWidth: selected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

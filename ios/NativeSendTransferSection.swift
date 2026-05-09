import SwiftUI

struct NativeTransferSection: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        PikoSectionPanel(
            title: "传输",
            trailing: { PikoPill(text: model.transferProgress.map { "\(($0 * 100).rounded())%" } ?? "待命", emphasized: true) }
        ) {
            Text(model.transferLabel)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
            if let progress = model.transferProgress {
                ProgressView(value: progress)
                    .tint(PikoPalette.accent)
            }
        }
    }
}

struct NativeFloatingSendButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("发送", systemImage: "paperplane.fill")
                .font(.headline)
                .padding(.horizontal, 26)
                .frame(height: 58)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .background(PikoPalette.accent, in: Capsule())
        .overlay(
            Capsule()
                .stroke(.white.opacity(0.35), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.12), radius: 18, y: 8)
        .padding(.bottom, 92)
    }
}

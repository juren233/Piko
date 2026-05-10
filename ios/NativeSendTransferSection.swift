import SwiftUI

struct NativeTransferSection: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(PikoPalette.surfaceVariant.opacity(0.28))
                    .frame(width: 48, height: 48)
                    .overlay {
                        Image(uiImage: model.transferPrimaryFileType == .image ? LucideTabIcon.image.image : LucideTabIcon.file.image)
                            .resizable()
                            .frame(width: 24, height: 24)
                            .foregroundStyle(.secondary)
                    }
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.transferTitle)
                        .font(PikoFont.rowTitle)
                        .lineLimit(1)
                        .minimumScaleFactor(0.88)
                        .truncationMode(.tail)
                    Text(model.transferSubtitle)
                        .font(PikoFont.compactSubtitle)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer()
                Button(action: model.pauseTransfer) {
                    Image(uiImage: LucideTabIcon.pause.image)
                        .resizable()
                        .frame(width: 18, height: 18)
                        .padding(11)
                        .background(PikoPalette.accent.opacity(0.1), in: Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(PikoPalette.accent)
                Button(action: model.cancelTransfer) {
                    Image(uiImage: LucideTabIcon.x.image)
                        .resizable()
                        .frame(width: 18, height: 18)
                        .padding(11)
                        .background(Color.red.opacity(0.1), in: Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.red)
            }
            if let progress = model.transferProgress {
                ProgressView(value: progress)
                    .tint(PikoPalette.accent)
            }
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

struct NativeFloatingSendButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(uiImage: LucideTabIcon.send.image)
                    .resizable()
                    .frame(width: 21, height: 21)
                Text("发送")
                    .font(PikoFont.floatingAction)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
                    .truncationMode(.tail)
            }
                .padding(.horizontal, 26)
                .frame(height: 58)
        }
        .buttonStyle(.plain)
        .foregroundStyle(PikoPalette.accent)
        .frame(minWidth: 172, maxWidth: 260)
        .background(PikoPalette.surface.opacity(0.72), in: Capsule())
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.35), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.12), radius: 18, y: 8)
        .padding(.bottom, 104)
    }
}

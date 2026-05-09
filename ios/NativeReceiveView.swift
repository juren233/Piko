import SwiftUI

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel

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
                if model.receiveHistory.isEmpty {
                    NativeReceiveEmptyState()
                } else {
                    NativeReceiveHistoryList(items: model.receiveHistory)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 136)
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
    }
}

private struct NativeDeviceNicknameBanner: View {
    let nickname: String
    let onReset: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(uiImage: LucideTabIcon.inbox.image)
                .resizable()
                .frame(width: 22, height: 22)
                .foregroundStyle(PikoPalette.accent)
            Text("本设备名称：\(nickname)")
                .font(.subheadline)
                .lineLimit(1)
            Spacer()
            Button("换个昵称", action: onReset)
                .font(.caption.weight(.semibold))
                .foregroundStyle(PikoPalette.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(PikoPalette.accent.opacity(0.1), in: Capsule())
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
    let items: [NativeReceiveHistoryItem]

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                PikoPill(text: "最近接收", emphasized: true)
                Spacer()
            }
            VStack(spacing: 12) {
                ForEach(items) { item in
                    NativeReceiveHistoryCard(item: item)
                }
            }
        }
    }
}

private struct NativeReceiveHistoryCard: View {
    let item: NativeReceiveHistoryItem

    var body: some View {
        HStack(spacing: 14) {
            NativeReceiveHistoryPreview(item: item)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                Text(item.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(">")
                .font(.title3.weight(.semibold))
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
        } else if item.imagePreviewDescription != nil {
            NativeImagePreview()
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
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.primary)
            }
    }
}

private struct NativeImagePreview: View {
    var body: some View {
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
                .font(.caption2.weight(.semibold))
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
                        .font(.caption.weight(.bold))
                }
            }
    }
}
